#!/usr/bin/env python3
"""Turns a build log into GitHub annotations, so failures show on the run's summary page.

    report.py LOG "Title" [notice]

Kotlin ("e: ..."), Xcode/clang/Swift ("error: ...") and Gradle ("What went wrong")
errors each become one annotation; the end of the log becomes another. With
"notice", the log's lines starting with "MEWZ:" are shown as notices (test results).
"""
import os
import re
import sys


def escape(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def emit(kind: str, title: str, message: str) -> None:
    print(f"::{kind} title={escape(title).replace(',', ' ')}::{escape(message)}")


def main() -> None:
    path, title = sys.argv[1], sys.argv[2]
    mode = sys.argv[3] if len(sys.argv) > 3 else "error"
    if not os.path.exists(path):
        emit("warning", title, f"No log at {path}")
        return
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()

    if mode == "notice":
        found = [l.split("MEWZ:", 1)[1].strip() for l in lines if "MEWZ:" in l]
        # Notices are capped per step, so batch them.
        for i in range(0, len(found), 25):
            emit("notice", f"{title} ({i // 25 + 1})", "\n".join(found[i:i + 25]))
        return

    errors = []
    for i, l in enumerate(lines):
        if re.match(r"^e: ", l) or re.search(r"(^|\s)error: ", l) or "** BUILD FAILED **" in l:
            errors.append(l.strip())
        elif l.startswith("* What went wrong:"):
            errors.append("\n".join(lines[i:i + 12]))
        elif re.search(r"Test.*FAILED$|tests completed, \d+ failed", l):
            errors.append("\n".join(lines[max(0, i - 2):i + 25]))
    seen = []
    for e in errors:
        if e not in seen:
            seen.append(e)
    for e in seen[:40]:
        emit("error", title, e[:6000])
    emit("warning" if seen else "notice", f"{title}: end of log", "\n".join(lines[-60:])[-6000:])


if __name__ == "__main__":
    main()
