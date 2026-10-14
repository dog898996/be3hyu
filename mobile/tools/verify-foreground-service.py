#!/usr/bin/env python3
"""Require the requested service's own AMS record to be foreground."""
import re
import sys


def foreground_present(dump, package):
    component = re.escape(package + '/.runtime.AutonomyService')
    # ServiceRecord also occurs in connection/history lines; only AMS's
    # indented * ServiceRecord headers begin a live service record.
    records = re.split(r'(?m)^\s*\* ServiceRecord\{', dump)[1:]
    for record in records:
        header, _, body = record.partition('\n')
        if re.search(r'\s' + component + r'(?:\s|\})', header):
            if re.search(r'(?m)^\s*isForeground=true(?:\s|$)', body):
                return True
    return False


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify-foreground-service.py PACKAGE < dumpsys.txt')
    raise SystemExit(0 if foreground_present(sys.stdin.read(), sys.argv[1]) else 1)
