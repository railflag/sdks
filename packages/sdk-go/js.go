package railflag

// JavaScript semantics the evaluator relies on, so Go gives the same answers as the TypeScript
// evaluator (packages/eval): String(x), ===, UTF-16 string ordering and Date.parse.

import (
	"math"
	"net/mail"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"
)

func isNumber(v any) bool {
	_, ok := v.(float64)
	return ok
}

// jsNumber is String(n) for a JavaScript number: shortest round-trip digits, exponent notation
// outside 1e-7 ≤ |n| < 1e21.
func jsNumber(x float64) string {
	switch {
	case math.IsNaN(x):
		return "NaN"
	case math.IsInf(x, 1):
		return "Infinity"
	case math.IsInf(x, -1):
		return "-Infinity"
	case x == 0:
		return "0"
	}
	sign := ""
	if x < 0 {
		sign, x = "-", -x
	}
	// "d.ddddde±XX" with the fewest digits that read back as x.
	s := strconv.FormatFloat(x, 'e', -1, 64)
	mant, exp, _ := strings.Cut(s, "e")
	e, _ := strconv.Atoi(exp)
	digits := strings.Replace(mant, ".", "", 1)
	k := len(digits)
	n := e + 1 // x = 0.<digits> × 10^n
	var out string
	switch {
	case k <= n && n <= 21:
		out = digits + strings.Repeat("0", n-k)
	case 0 < n && n <= 21:
		out = digits[:n] + "." + digits[n:]
	case -6 < n && n <= 0:
		out = "0." + strings.Repeat("0", -n) + digits
	default:
		m := digits
		if k > 1 {
			m = digits[:1] + "." + digits[1:]
		}
		es := "+"
		if n-1 < 0 {
			es = "-"
		}
		out = m + "e" + es + strconv.Itoa(abs(n-1))
	}
	return sign + out
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// jsString is String(v) for JSON-like values.
func jsString(v any) string {
	switch t := v.(type) {
	case nil:
		return "null"
	case bool:
		if t {
			return "true"
		}
		return "false"
	case float64:
		return jsNumber(t)
	case string:
		return t
	case []any:
		parts := make([]string, len(t))
		for i, x := range t {
			if x != nil {
				parts[i] = jsString(x)
			}
		}
		return strings.Join(parts, ",")
	default:
		return "[object Object]"
	}
}

// strictEquals is a === b for scalars.
func strictEquals(a, b any) bool {
	switch x := a.(type) {
	case float64:
		y, ok := b.(float64)
		return ok && x == y
	case bool:
		y, ok := b.(bool)
		return ok && x == y
	case string:
		y, ok := b.(string)
		return ok && x == y
	}
	return false
}

// utf16Units returns the UTF-16 code units of s. Strings may hold lone surrogates in WTF-8 form
// (see parseJSON); any other invalid byte becomes U+FFFD.
func utf16Units(s string) []uint16 {
	out := make([]uint16, 0, len(s))
	for i := 0; i < len(s); {
		c := s[i]
		if c < utf8.RuneSelf {
			out = append(out, uint16(c))
			i++
			continue
		}
		if c == 0xED && i+2 < len(s) && s[i+1] >= 0xA0 && s[i+1] <= 0xBF && s[i+2]&0xC0 == 0x80 {
			out = append(out, uint16(0xD000|uint16(s[i+1]&0x3F)<<6|uint16(s[i+2]&0x3F)))
			i += 3
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		if r >= 0x10000 {
			hi, lo := utf16.EncodeRune(r)
			out = append(out, uint16(hi), uint16(lo))
		} else {
			out = append(out, uint16(r))
		}
		i += size
	}
	return out
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= utf8.RuneSelf {
			return false
		}
	}
	return true
}

func compareUnits(a, b []uint16) int {
	for i := 0; i < len(a) && i < len(b); i++ {
		if a[i] != b[i] {
			if a[i] < b[i] {
				return -1
			}
			return 1
		}
	}
	return len(a) - len(b)
}

// compareStrings orders strings by UTF-16 code units, as JavaScript does.
func compareStrings(a, b string) int {
	if isASCII(a) && isASCII(b) {
		return strings.Compare(a, b)
	}
	return compareUnits(utf16Units(a), utf16Units(b))
}

func hasPrefix(s, prefix string) bool {
	if utf8.ValidString(s) && utf8.ValidString(prefix) {
		return strings.HasPrefix(s, prefix)
	}
	a, p := utf16Units(s), utf16Units(prefix)
	return len(p) <= len(a) && compareUnits(a[:len(p)], p) == 0
}

func hasSuffix(s, suffix string) bool {
	if utf8.ValidString(s) && utf8.ValidString(suffix) {
		return strings.HasSuffix(s, suffix)
	}
	a, p := utf16Units(s), utf16Units(suffix)
	return len(p) <= len(a) && compareUnits(a[len(a)-len(p):], p) == 0
}

func contains(s, sub string) bool {
	if utf8.ValidString(s) && utf8.ValidString(sub) {
		return strings.Contains(s, sub)
	}
	a, p := utf16Units(s), utf16Units(sub)
	for i := 0; i+len(p) <= len(a); i++ {
		if compareUnits(a[i:i+len(p)], p) == 0 {
			return true
		}
	}
	return false
}

// compare is a - b for two numbers or the UTF-16 order of two strings; ok is false otherwise.
func compare(a, b any) (c float64, ok bool) {
	if x, isNum := a.(float64); isNum {
		if y, isNum := b.(float64); isNum {
			return x - y, true
		}
		return 0, false
	}
	if x, isStr := a.(string); isStr {
		if y, isStr := b.(string); isStr {
			return float64(compareStrings(x, y)), true
		}
	}
	return 0, false
}

var isoDate = regexp.MustCompile(`^([+-]\d{6}|\d{4})(?:-(\d{2})(?:-(\d{2}))?)?(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:\d{2})?)?$`)

// parseTime is Date.parse for ISO 8601 and RFC 2822 dates, in epoch milliseconds. A date-time
// without an offset is read as UTC (JavaScript would use the machine's time zone).
func parseTime(s string) (float64, bool) {
	if m := isoDate.FindStringSubmatch(s); m != nil {
		num := func(x string, dflt int) int {
			if x == "" {
				return dflt
			}
			n, _ := strconv.Atoi(x)
			return n
		}
		year, month, day := num(m[1], 0), num(m[2], 1), num(m[3], 1)
		hour, minute, second := num(m[4], 0), num(m[5], 0), num(m[6], 0)
		if month < 1 || month > 12 || day < 1 || day > daysIn(year, month) || hour > 24 || minute >= 60 || second >= 60 {
			return 0, false
		}
		frac := m[7]
		if hour == 24 && (minute != 0 || second != 0 || strings.Trim(frac, "0") != "") {
			return 0, false
		}
		ms := 0
		if frac != "" {
			f := (frac + "00")[:3]
			ms, _ = strconv.Atoi(f)
		}
		t := time.Date(year, time.Month(month), day, hour, minute, second, 0, time.UTC).Unix()
		if tz := m[8]; tz != "" && tz != "Z" {
			off := (num(tz[1:3], 0)*60 + num(tz[4:6], 0)) * 60
			if tz[0] == '+' {
				t -= int64(off)
			} else {
				t += int64(off)
			}
		}
		return float64(t*1000 + int64(ms)), true
	}
	t, err := mail.ParseDate(s)
	if err != nil {
		return 0, false
	}
	return float64(t.UnixMilli()), true
}

func daysIn(year, month int) int {
	return time.Date(year, time.Month(month)+1, 0, 0, 0, 0, 0, time.UTC).Day()
}

func toTime(v any) (float64, bool) {
	switch t := v.(type) {
	case float64:
		if math.IsInf(t, 0) || math.IsNaN(t) {
			return 0, false
		}
		return t, true
	case string:
		return parseTime(t)
	}
	return 0, false
}

var regexCache sync.Map // string → *regexp.Regexp (nil when it doesn't compile)

// jsRegex compiles a JavaScript regex source with Go's RE2. Sources RE2 can't run (lookaround,
// backreferences) return nil, which makes the clause not match.
func jsRegex(src string) *regexp.Regexp {
	if v, ok := regexCache.Load(src); ok {
		return v.(*regexp.Regexp)
	}
	re, err := regexp.Compile(src)
	if err != nil {
		re = nil
	}
	regexCache.Store(src, re)
	return re
}
