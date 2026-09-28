#!/usr/bin/env python3
"""fd-passing smoke test for wgtunnel (Linux only).

Acts as the Android Kotlin side: opens an abstract-namespace Unix socket,
spawns `wgtunnel recvcheck <name>`, accepts its connection and hands it one
fd (a pipe read-end) via SCM_RIGHTS, then asserts the child prints FD-OK.

Usage: ./fdcheck.py [path-to-wgtunnel-binary]
"""
import array
import os
import socket
import subprocess
import sys
import time

BIN = sys.argv[1] if len(sys.argv) > 1 else "./wgtunnel"
NAME = f"wgtun_test_{os.getpid()}_{int(time.time())}"


def main() -> int:
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind("\0" + NAME)
    srv.listen(1)
    srv.settimeout(10)

    r, w = os.pipe()
    try:
        proc = subprocess.Popen(
            [BIN, "recvcheck", NAME],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        try:
            conn, _ = srv.accept()
        except socket.timeout:
            print("FAIL: child never connected")
            proc.kill()
            return 1
        with conn:
            fds = array.array("i", [r])
            conn.sendmsg(
                [b"\x01"],
                [(socket.SOL_SOCKET, socket.SCM_RIGHTS, fds)],
            )
        try:
            out = proc.communicate(timeout=10)[0]
        except subprocess.TimeoutExpired:
            print("FAIL: child hung")
            proc.kill()
            return 1
        print(out.strip())
        if proc.returncode == 0 and "FD-OK" in out:
            print("FDCHECK-PASS")
            return 0
        print(f"FAIL: exit={proc.returncode}")
        return 1
    finally:
        os.close(r)
        os.close(w)
        srv.close()


if __name__ == "__main__":
    sys.exit(main())
