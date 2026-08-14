#!/usr/bin/env python3
"""Small stdlib-only checks for the nano benchmark tools."""

import csv
import json
import os
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import driver
import report


class FakeNodel(BaseHTTPRequestHandler):
    seq = 1
    fixture_seq = 0
    activity = {}

    def reply(self, value):
        body = json.dumps(value).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path.endswith("/params"):
            self.reply({"label": "NanoFix1"})
            return
        if parsed.path.endswith("/activity"):
            cursor = int(urllib.parse.parse_qs(parsed.query).get("from", ["-1"])[0])
            self.reply([item for item in self.activity.values() if item["seq"] >= cursor])
            return
        self.send_error(404)

    def do_POST(self):
        if not self.path.endswith("/actions/ping/call"):
            self.send_error(404)
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).fixture_seq += 1
        values = {"pong": body["arg"]}
        values.update({name: self.fixture_seq for name in driver.EVENTS})
        for alias, arg in values.items():
            self.activity[alias] = {
                "seq": self.seq,
                "source": "local",
                "type": "event",
                "alias": alias,
                "arg": arg,
            }
            type(self).seq += 1
        self.reply(True)

    def log_message(self, *_args):
        pass


class BenchTests(unittest.TestCase):
    def test_event_gaps_and_reset(self):
        counter = driver.EventCounter()
        for value in (1, 3, 1, 2):
            counter.observe(value)
        self.assertEqual(counter.gaps, 1)
        self.assertEqual(counter.gap_occurrences, 1)
        self.assertEqual(counter.resets, 1)

    def test_driver_and_report(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeNodel)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                driver.main([
                    "--base-url", "http://127.0.0.1:{}".format(server.server_port),
                    "--duration", "0.3",
                    "--nodes", "1",
                    "--rate", "5",
                    "--poll-interval", "0.02",
                    "--output-dir", temp,
                ])
                with open(os.path.join(temp, "gaps.json")) as source:
                    gaps = json.load(source)
                self.assertGreater(gaps["nodes"]["NanoFix1"]["pongs_matched"], 0)
                self.assertTrue(all(
                    item["gaps"] == 0
                    for item in gaps["nodes"]["NanoFix1"]["events"].values()
                ))
                gaps["actual_duration_s"] = 12.9
                final_seqs = {"tick500": 24, "tick5s": 2, "tcpEcho": 11, "udpEcho": 12}
                for event, final_seq in final_seqs.items():
                    gaps["nodes"]["NanoFix1"]["events"][event]["last_seq"] = final_seq
                with open(os.path.join(temp, "gaps.json"), "w") as output:
                    json.dump(gaps, output)

                memory = os.path.join(temp, "memory.csv")
                with open(memory, "w", newline="") as output:
                    writer = csv.writer(output)
                    writer.writerow([
                        "timestamp_epoch_s", "VmRSS_kB", "VmHWM_kB", "Pss_kB", "SwapUsed_kB"
                    ])
                    writer.writerow([0, 1024, 2048, 512, 100])
                    writer.writerow([700, 3072, 4096, 2048, 612])
                args = SimpleNamespace(
                    label="self-test",
                    memory=memory,
                    latency=os.path.join(temp, "latency.csv"),
                    gaps=os.path.join(temp, "gaps.json"),
                    startup="123",
                )
                section = report.build_section(args)
                self.assertIn("3.00 MiB", section)
                self.assertIn("123.00 ms", section)
                self.assertIn("| Event | Expected | Final seq |", section)
                self.assertIn("| tick500 | 25 | 24 |", section)
                self.assertIn("| tcpEcho | 12 | 11 |", section)
                self.assertIn("authoritative emission check", section)
                self.assertEqual(report.final_seq_range([24, 23]), "23-24")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
