"""Shared helpers of tools/validate: the report every check writes to, and value masking.

Exports hold passwords, so nothing here prints a value unless --show-values was given: a difference is
described by its position, the lengths and the first index where the two texts differ.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
PROTON_FIXTURE = os.path.join(REPO, "core", "src", "test", "resources", "formats", "proton", "bitwarden.json")

# Characters that XML 1.0 forbids even as character references; the .xlsx writer escapes them as _xHHHH_.
XML_FORBIDDEN = set(range(0x00, 0x09)) | {0x0B, 0x0C} | set(range(0x0E, 0x20)) | {0xFFFE, 0xFFFF}


class Report:
    def __init__(self, show_values=False):
        self.show_values = show_values
        self.passed = self.failed = self.skipped = 0

    def section(self, title):
        print(f"\n== {title}")
        sys.stdout.flush()

    def ok(self, msg):
        self.passed += 1
        print(f"PASS  {msg}")

    def fail(self, msg, details=()):
        self.failed += 1
        print(f"FAIL  {msg}")
        details = [d for d in details if d]
        for d in details[:8]:
            print(f"        {d}")
        if len(details) > 8:
            print(f"        … and {len(details) - 8} more")

    def check(self, cond, msg, details=()):
        if cond:
            self.ok(msg)
        else:
            self.fail(msg, details)
        return cond

    def info(self, msg):
        print(f"INFO  {msg}")

    def skip(self, msg):
        self.skipped += 1
        print(f"SKIP  {msg}")

    def value(self, v):
        """A value as it may be printed: itself with --show-values, otherwise only its length."""
        if v is None:
            return "None"
        if self.show_values:
            return repr(v)[:120]
        return f"<{len(v)} characters>" if isinstance(v, str) else f"<{type(v).__name__}>"

    def differ(self, got, want):
        """How two texts differ, without showing them unless --show-values."""
        if self.show_values:
            return f"got {repr(got)[:110]} expected {repr(want)[:110]}"
        if got is None or want is None:
            return f"got {self.value(got)} expected {self.value(want)}"
        i = next((k for k, (a, b) in enumerate(zip(got, want)) if a != b), min(len(got), len(want)))
        return f"got {len(got)} characters, expected {len(want)}; first difference at index {i}"


def has_lone_surrogate(s):
    for i, ch in enumerate(s):
        c = ord(ch)
        if 0xD800 <= c <= 0xDBFF:
            if i + 1 >= len(s) or not 0xDC00 <= ord(s[i + 1]) <= 0xDFFF:
                return True
        elif 0xDC00 <= c <= 0xDFFF:
            if i == 0 or not 0xD800 <= ord(s[i - 1]) <= 0xDBFF:
                return True
    return False
