#!/usr/bin/env python3
"""Drive the classic Nodel REST API and record fixture continuity."""

import argparse
import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


EVENTS = ("tick500", "tick5s", "tcpEcho", "udpEcho")
INTEGER = re.compile(r"^-?\d+$")


class EventCounter:
    def __init__(self):
        self.last_seq = None
        self.observed = 0
        self.gaps = 0
        self.gap_occurrences = 0
        self.resets = 0
        self.invalid = 0

    def observe(self, value):
        if isinstance(value, bool):
            self.invalid += 1
            return
        if isinstance(value, int):
            seq = value
        elif isinstance(value, str) and INTEGER.match(value):
            seq = int(value)
        else:
            self.invalid += 1
            return

        if self.last_seq is None:
            self.last_seq = seq
        elif seq < self.last_seq:
            self.resets += 1
            self.last_seq = seq
        elif seq > self.last_seq:
            if seq > self.last_seq + 1:
                self.gaps += seq - self.last_seq - 1
                self.gap_occurrences += 1
            self.last_seq = seq
        self.observed += 1

    def result(self):
        return {
            "observed": self.observed,
            "gaps": self.gaps,
            "gap_occurrences": self.gap_occurrences,
            "resets": self.resets,
            "invalid": self.invalid,
            "last_seq": self.last_seq,
        }


class NodeState:
    def __init__(self, name, start):
        self.name = name
        self.cursor = -1
        self.next_ping = start
        self.pending = {}
        self.events = {name: EventCounter() for name in EVENTS}
        self.pings_sent = 0
        self.pongs_matched = 0
        self.pongs_unmatched = 0
        self.pong_timeouts = 0
        self.action_errors = 0
        self.poll_errors = 0
        self.error_examples = []

    def error(self, kind, exc):
        if kind == "action":
            self.action_errors += 1
        else:
            self.poll_errors += 1
        if len(self.error_examples) < 5:
            self.error_examples.append("{}: {}".format(kind, exc))

    def result(self):
        return {
            "events": {name: counter.result() for name, counter in self.events.items()},
            "pings_sent": self.pings_sent,
            "pongs_matched": self.pongs_matched,
            "pongs_unmatched": self.pongs_unmatched,
            "pong_timeouts": self.pong_timeouts,
            "pongs_pending": len(self.pending),
            "action_errors": self.action_errors,
            "poll_errors": self.poll_errors,
            "error_examples": self.error_examples,
        }


def request_json(url, timeout, body=None):
    headers = {"Accept": "application/json", "User-Agent": "nodel-nano-bench/1"}
    method = "GET"
    data = None
    if body is not None:
        method = "POST"
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError("HTTP {} from {}".format(response.status, url))
        return json.loads(response.read().decode("utf-8"))


def node_url(base_url, node, suffix):
    return "{}/REST/nodes/{}/{}".format(
        base_url.rstrip("/"), urllib.parse.quote(node, safe=""), suffix
    )


def send_ping(base_url, state, timeout, now):
    token = "{}-{}".format(state.name, uuid.uuid4().hex)
    url = node_url(base_url, state.name, "actions/ping/call")
    try:
        request_json(url, timeout, {"arg": token})
    except (OSError, ValueError, urllib.error.URLError) as exc:
        state.error("action", exc)
        return
    state.pending[token] = (now, time.time())
    state.pings_sent += 1


def poll_activity(base_url, state, timeout, latency_writer):
    query = urllib.parse.urlencode({"from": state.cursor})
    url = node_url(base_url, state.name, "activity") + "?" + query
    try:
        entries = request_json(url, timeout)
        if not isinstance(entries, list):
            raise ValueError("activity response is not an array")
    except (OSError, ValueError, urllib.error.URLError) as exc:
        state.error("poll", exc)
        return

    entries.sort(key=lambda item: item.get("seq", 0))
    for entry in entries:
        nodel_seq = entry.get("seq")
        if isinstance(nodel_seq, int) and nodel_seq != 0:
            state.cursor = nodel_seq + 1
        if entry.get("source") != "local" or entry.get("type") != "event":
            continue

        alias = str(entry.get("alias", ""))
        matched_name = next((name for name in EVENTS if name.lower() == alias.lower()), None)
        if matched_name is not None:
            state.events[matched_name].observe(entry.get("arg"))
            continue
        if alias.lower() != "pong":
            continue

        token = str(entry.get("arg", ""))
        pending = state.pending.pop(token, None)
        if pending is None:
            state.pongs_unmatched += 1
            continue
        sent_monotonic, sent_epoch = pending
        latency_writer.writerow(
            ["{:.6f}".format(sent_epoch), state.name, token,
             "{:.3f}".format((time.monotonic() - sent_monotonic) * 1000)]
        )
        state.pongs_matched += 1


def expire_pings(state, now, pong_timeout):
    expired = [token for token, sent in state.pending.items() if now - sent[0] >= pong_timeout]
    for token in expired:
        del state.pending[token]
    state.pong_timeouts += len(expired)


def verify_label(base_url, state, timeout):
    params = request_json(node_url(base_url, state.name, "params"), timeout)
    if not isinstance(params, dict):
        raise RuntimeError("{} params response is not an object".format(state.name))
    actual = next((value for key, value in params.items() if key.lower() == "label"), None)
    if actual != state.name:
        raise RuntimeError(
            "{} label round-trip failed: expected {!r}, got {!r}".format(
                state.name, state.name, actual
            )
        )


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8085")
    parser.add_argument("--duration", type=float, default=3600)
    parser.add_argument("--nodes", type=int, default=1)
    parser.add_argument("--rate", type=float, default=1.0, help="ping calls per second per node")
    parser.add_argument("--poll-interval", type=float, default=0.1)
    parser.add_argument("--request-timeout", type=float, default=2.0)
    parser.add_argument("--pong-timeout", type=float, default=10.0)
    parser.add_argument("--output-dir", default=".")
    args = parser.parse_args(argv)
    if min(args.duration, args.rate, args.poll_interval, args.request_timeout, args.pong_timeout) <= 0:
        parser.error("duration, rate, intervals, and timeouts must be positive")
    if args.nodes < 1:
        parser.error("nodes must be at least 1")
    return args


def main(argv=None):
    args = parse_args(argv)
    os.makedirs(args.output_dir, exist_ok=True)
    latency_path = os.path.join(args.output_dir, "latency.csv")
    gaps_path = os.path.join(args.output_dir, "gaps.json")
    nodes = [NodeState("NanoFix{}".format(index), 0) for index in range(1, args.nodes + 1)]

    for state in nodes:
        verify_label(args.base_url, state, args.request_timeout)

    start = time.monotonic()
    deadline = start + args.duration
    next_poll = start
    for state in nodes:
        state.next_ping = start

    try:
        with open(latency_path, "w", newline="", buffering=1) as latency_file:
            latency_writer = csv.writer(latency_file)
            latency_writer.writerow(["sent_epoch_s", "node", "token", "latency_ms"])
            while time.monotonic() < deadline:
                now = time.monotonic()
                for state in nodes:
                    if now >= state.next_ping:
                        send_ping(args.base_url, state, args.request_timeout, now)
                        state.next_ping = max(state.next_ping + 1.0 / args.rate, now + 1.0 / args.rate)

                if now >= next_poll:
                    for state in nodes:
                        poll_activity(args.base_url, state, args.request_timeout, latency_writer)
                    next_poll = time.monotonic() + args.poll_interval

                now = time.monotonic()
                for state in nodes:
                    expire_pings(state, now, args.pong_timeout)
                wake_at = min([deadline, next_poll] + [state.next_ping for state in nodes])
                time.sleep(max(0.0, min(0.05, wake_at - time.monotonic())))

            for state in nodes:
                poll_activity(args.base_url, state, args.request_timeout, latency_writer)
    except KeyboardInterrupt:
        print("Interrupted; writing partial results.", file=sys.stderr)
    finally:
        finished = time.monotonic()
        results = {
            "base_url": args.base_url,
            "configured_duration_s": args.duration,
            "actual_duration_s": round(finished - start, 3),
            "nodes_requested": args.nodes,
            "ping_rate_per_node_hz": args.rate,
            "poll_interval_s": args.poll_interval,
            "nodes": {state.name: state.result() for state in nodes},
        }
        temp_path = gaps_path + ".tmp"
        with open(temp_path, "w") as output:
            json.dump(results, output, indent=2, sort_keys=True)
            output.write("\n")
        os.replace(temp_path, gaps_path)

    total_gaps = sum(counter.gaps for state in nodes for counter in state.events.values())
    total_latencies = sum(state.pongs_matched for state in nodes)
    print("Recorded {} latencies and {} missing event sequences.".format(total_latencies, total_gaps))
    return 0


if __name__ == "__main__":
    sys.exit(main())
