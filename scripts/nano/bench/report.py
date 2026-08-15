#!/usr/bin/env python3
"""Merge nano benchmark outputs into a Markdown report section."""

import argparse
import csv
import json
import math
import os
import statistics


EVENT_PERIODS = {"tick500": 0.5, "tick5s": 5, "tcpEcho": 1, "udpEcho": 1}


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def fmt(value, unit=""):
    if value is None:
        return "N/A"
    return "{:.2f}{}".format(value, unit)


def read_memory(path):
    if not path:
        return [], []
    with open(path, newline="") as source:
        rows = list(csv.DictReader(source))
    rows = [row for row in rows if number(row.get("timestamp_epoch_s")) is not None]
    rows.sort(key=lambda row: number(row["timestamp_epoch_s"]))
    if not rows:
        return [], []
    cutoff = number(rows[-1]["timestamp_epoch_s"]) - 600
    return rows, [row for row in rows if number(row["timestamp_epoch_s"]) >= cutoff]


def median_kib(rows, field):
    values = [number(row.get(field)) for row in rows]
    values = [value for value in values if value is not None]
    return statistics.median(values) / 1024 if values else None


def max_kib(rows, field):
    values = [number(row.get(field)) for row in rows]
    values = [value for value in values if value is not None]
    return max(values) / 1024 if values else None


def read_latencies(path):
    if not path:
        return []
    with open(path, newline="") as source:
        values = [number(row.get("latency_ms")) for row in csv.DictReader(source)]
    return [value for value in values if value is not None]


def read_startup(value):
    if value is None:
        return None
    if os.path.isfile(value):
        with open(value) as source:
            value = source.read().strip().splitlines()[-1]
    return number(value)


def gap_rows(path):
    if not path:
        return [], None
    with open(path) as source:
        data = json.load(source)
    duration = number(data.get("actual_duration_s"))
    aggregates = {}
    pong = {
        "pings_sent": 0,
        "pongs_matched": 0,
        "pong_timeouts": 0,
        "pongs_pending": 0,
        "pongs_unmatched": 0,
        "action_errors": 0,
        "poll_errors": 0,
    }
    for node in data.get("nodes", {}).values():
        for key in pong:
            pong[key] += int(node.get(key, 0))
        for event, result in node.get("events", {}).items():
            total = aggregates.setdefault(
                event,
                {
                    "observed": 0,
                    "gaps": 0,
                    "gap_occurrences": 0,
                    "resets": 0,
                    "invalid": 0,
                    "last_seqs": [],
                },
            )
            for key in ("observed", "gaps", "gap_occurrences", "resets", "invalid"):
                total[key] += int(result.get(key, 0))
            last_seq = result.get("last_seq")
            if isinstance(last_seq, int) and not isinstance(last_seq, bool):
                total["last_seqs"].append(last_seq)
    for event, total in aggregates.items():
        period = EVENT_PERIODS.get(event)
        total["expected"] = math.floor(duration / period) if duration is not None and period else None
    rows = [(event, values) for event, values in sorted(aggregates.items())]
    return rows, pong


def final_seq_range(values):
    if not values:
        return "N/A"
    low, high = min(values), max(values)
    return str(low) if low == high else "{}-{}".format(low, high)


def swap_delta_mib(rows):
    if len(rows) < 2:
        return None
    first = number(rows[0].get("SwapUsed_kB"))
    last = number(rows[-1].get("SwapUsed_kB"))
    return (last - first) / 1024 if first is not None and last is not None else None


def build_section(args):
    memory, steady = read_memory(args.memory)
    latencies = read_latencies(args.latency)
    gaps, pong = gap_rows(args.gaps)
    startup = read_startup(args.startup)

    lines = [
        "## {}".format(args.label),
        "",
        "| Metric | Result |",
        "| --- | ---: |",
        "| Steady-state RSS, median of final 10 min | {} |".format(
            fmt(median_kib(steady, "VmRSS_kB"), " MiB")
        ),
        "| Peak VmHWM | {} |".format(fmt(max_kib(memory, "VmHWM_kB"), " MiB")),
        "| Steady-state PSS, median of final 10 min | {} |".format(
            fmt(median_kib(steady, "Pss_kB"), " MiB")
        ),
        "| Latency p50 | {} |".format(fmt(percentile(latencies, 0.50), " ms")),
        "| Latency p95 | {} |".format(fmt(percentile(latencies, 0.95), " ms")),
        "| Latency p99 | {} |".format(fmt(percentile(latencies, 0.99), " ms")),
        "| Latency max | {} |".format(fmt(max(latencies) if latencies else None, " ms")),
        "| System swap delta | {} |".format(fmt(swap_delta_mib(memory), " MiB")),
        "| Startup to HTTP 200 | {} |".format(fmt(startup, " ms")),
        "",
    ]

    if gaps:
        lines.extend([
            "| Event | Expected | Final seq | Observed | Missing sequences | Gap occurrences | Counter resets | Invalid values |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ])
        lines.extend(
            "| {} | {} | {} | {} | {} | {} | {} | {} |".format(
                event,
                values["expected"] if values["expected"] is not None else "N/A",
                final_seq_range(values["last_seqs"]), values["observed"], values["gaps"],
                values["gap_occurrences"], values["resets"], values["invalid"]
            )
            for event, values in gaps
        )
        lines.extend([
            "",
            "Missing sequences are observation-level gaps, so they are an upper bound on "
            "emission loss. Final seq versus Expected is the authoritative emission check. "
            "Expected is per node; for multi-node trials, Final seq is the per-node range. "
            "tcpEcho and udpEcho advance only while their transport is connected and ready, "
            "so a small startup deficit is normal.",
            "",
        ])

    if pong is not None:
        lines.extend([
            "Ping/pong: {} sent, {} matched, {} timed out, {} pending, {} unmatched; "
            "REST errors: {} action, {} poll.".format(
                pong["pings_sent"], pong["pongs_matched"], pong["pong_timeouts"],
                pong["pongs_pending"], pong["pongs_unmatched"], pong["action_errors"],
                pong["poll_errors"]
            ),
            "",
        ])

    lines.append(
        "Samples: {} memory ({} in final window), {} matched latency.".format(
            len(memory), len(steady), len(latencies)
        )
    )
    return "\n".join(lines) + "\n"


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--memory")
    parser.add_argument("--latency")
    parser.add_argument("--gaps")
    parser.add_argument("--startup", help="milliseconds or a file containing milliseconds")
    parser.add_argument("--output", default="BENCH_REPORT.md")
    parser.add_argument("--append", action="store_true")
    args = parser.parse_args()
    if not any((args.memory, args.latency, args.gaps, args.startup)):
        parser.error("provide at least one benchmark input")
    return args


def main():
    args = parse_args()
    section = build_section(args)
    mode = "a" if args.append else "w"
    needs_separator = args.append and os.path.exists(args.output) and os.path.getsize(args.output) > 0
    with open(args.output, mode) as output:
        if needs_separator:
            output.write("\n")
        output.write(section)
    print("Wrote {}".format(args.output))


if __name__ == "__main__":
    main()
