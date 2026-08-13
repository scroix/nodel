#!/usr/bin/env python3
"""Serve local TCP and UDP echo peers for the nano fixture recipes."""

import argparse
import signal
import socketserver
import threading


class ReusableThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ReusableThreadingUDPServer(socketserver.ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True


class TCPHandler(socketserver.StreamRequestHandler):
    def handle(self):
        for line in self.rfile:
            self.wfile.write(line)
            self.wfile.flush()


class UDPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        sock.sendto(data, self.client_address)


def port(value):
    parsed = int(value)
    if not 1 <= parsed <= 65535:
        raise argparse.ArgumentTypeError('port must be between 1 and 65535')
    return parsed


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tcp-port', type=port, default=7401)
    parser.add_argument('--udp-port', type=port, default=7402)
    return parser.parse_args()


def main():
    args = parse_args()
    stopping = threading.Event()

    def stop(signum, frame):
        stopping.set()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    with ReusableThreadingTCPServer(('127.0.0.1', args.tcp_port), TCPHandler) as tcp:
        with ReusableThreadingUDPServer(('127.0.0.1', args.udp_port), UDPHandler) as udp:
            threads = [
                threading.Thread(target=tcp.serve_forever, name='tcp-echo'),
                threading.Thread(target=udp.serve_forever, name='udp-echo'),
            ]
            for thread in threads:
                thread.start()

            print(
                f'echo peer listening on 127.0.0.1 '
                f'(TCP {args.tcp_port}, UDP {args.udp_port})',
                flush=True,
            )
            stopping.wait()

            tcp.shutdown()
            udp.shutdown()
            for thread in threads:
                thread.join()


if __name__ == '__main__':
    main()

