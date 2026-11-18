"""JavaScript semantics the evaluator relies on, so Python gives the same answers as the TypeScript
evaluator (`packages/eval`): `String(x)`, `===`, UTF-16 string ordering and `Date.parse`."""

from __future__ import annotations

import calendar
import math
import re
from email.utils import parsedate_to_datetime
from typing import Any


def is_number(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def js_number(x: float | int) -> str:
    """`String(n)` for a JS number: shortest round-trip digits, exponent outside 1e-7..1e21."""
    if isinstance(x, int) and abs(x) < 10**21:
        return str(x)
    x = float(x)
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    if x == 0:
        return "0"
    sign = "-" if x < 0 else ""
    r = repr(abs(x))
    exp = 0
    if "e" in r:
        r, e = r.split("e")
        exp = int(e)
    int_part, _, frac = r.partition(".")
    all_digits = int_part + frac
    lead = len(all_digits) - len(all_digits.lstrip("0"))
    digits = all_digits.strip("0")
    n = len(int_part) - lead + exp  # value = 0.<digits> * 10**n
    k = len(digits)
    if k <= n <= 21:
        out = digits + "0" * (n - k)
    elif 0 < n <= 21:
        out = digits[:n] + "." + digits[n:]
    elif -6 < n <= 0:
        out = "0." + "0" * (-n) + digits
    else:
        e = n - 1
        mant = digits if k == 1 else digits[0] + "." + digits[1:]
        out = f"{mant}e{'+' if e >= 0 else '-'}{abs(e)}"
    return sign + out


def js_string(v: Any) -> str:
    """`String(v)` for JSON-like values."""
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if is_number(v):
        return js_number(v)
    if isinstance(v, str):
        return v
    if isinstance(v, (list, tuple)):
        return ",".join("" if x is None else js_string(x) for x in v)
    return "[object Object]"


def strict_equals(a: Any, b: Any) -> bool:
    """`a === b` for scalars. `True` is not `1`, and `1 == 1.0`."""
    if is_number(a) and is_number(b):
        return a == b
    if isinstance(a, bool) and isinstance(b, bool):
        return a == b
    if isinstance(a, str) and isinstance(b, str):
        return a == b
    return False


def _utf16(s: str) -> bytes:
    return s.encode("utf-16-be", "surrogatepass")


def compare(a: Any, b: Any) -> float | None:
    """Like `a - b` / `a < b` in JS for number/number and string/string; otherwise None.
    Strings compare by UTF-16 code units, as JavaScript does."""
    if is_number(a) and is_number(b):
        return a - b
    if isinstance(a, str) and isinstance(b, str):
        ua, ub = _utf16(a), _utf16(b)
        return -1 if ua < ub else 1 if ua > ub else 0
    return None


_ISO = re.compile(
    r"^([+-]\d{6}|\d{4})(?:-(\d{2})(?:-(\d{2}))?)?"
    r"(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:\d{2})?)?$",
    re.ASCII,
)


def parse_time(s: str) -> float | None:
    """`Date.parse` for ISO 8601 and RFC 2822 dates, in epoch milliseconds.
    A date-time without an offset is read as UTC (JavaScript would use the machine's zone)."""
    m = _ISO.match(s)
    if m:
        y, mo, d, h, mi, sec, frac, tz = m.groups()
        try:
            year, month, day = int(y), int(mo or 1), int(d or 1)
            hour, minute, second = int(h or 0), int(mi or 0), int(sec or 0)
            if not (1 <= month <= 12 and 1 <= day <= calendar.monthrange(year, month)[1] and hour <= 24 and minute < 60 and second < 60):
                return None
            if hour == 24 and (minute or second or (frac and int(frac))):
                return None
            ms = int((frac or "0")[:3].ljust(3, "0"))
            days = (calendar.timegm((year, month, 1, 0, 0, 0)) // 86400) + day - 1
            t = ((days * 24 + hour) * 60 + minute) * 60 + second
            if tz and tz != "Z":
                off = int(tz[1:3]) * 60 + int(tz[4:6])
                t -= off * 60 if tz[0] == "+" else -off * 60
            return float(t * 1000 + ms)
        except (ValueError, OverflowError):
            return None
    try:
        dt = parsedate_to_datetime(s)
    except (TypeError, ValueError, IndexError):
        return None
    if dt is None:
        return None
    if dt.tzinfo is None:
        return float(calendar.timegm(dt.timetuple()) * 1000)
    return dt.timestamp() * 1000


def to_time(v: Any) -> float | None:
    if is_number(v) and math.isfinite(v):
        return float(v)
    if isinstance(v, str):
        return parse_time(v)
    return None


_regex_cache: dict[str, re.Pattern[str] | None] = {}
_NAMED_GROUP = re.compile(r"\(\?<(?![=!])")


def js_regex(src: str) -> re.Pattern[str] | None:
    """Compiles a JavaScript regex source for Python's `re` (ASCII \\d and \\w, `(?<name>` groups).
    Returns None when it doesn't compile, which makes the clause not match."""
    if src not in _regex_cache:
        try:
            _regex_cache[src] = re.compile(_NAMED_GROUP.sub("(?P<", src), re.ASCII)
        except re.error:
            _regex_cache[src] = None
    return _regex_cache[src]
