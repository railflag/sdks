package railflag

import (
	"fmt"
	"strconv"
	"unicode/utf16"
	"unicode/utf8"
)

// parseJSON decodes JSON into the values the evaluator works on: map[string]any, []any, float64,
// string, bool and nil. Numbers are float64, as in JavaScript.
//
// It differs from encoding/json in one way: a lone UTF-16 surrogate escape ("\ud800") is kept as
// its 3-byte WTF-8 form instead of becoming U+FFFD, so strings keep the same UTF-16 code units the
// TypeScript evaluator sees. That matters for AB Tasty and Unleash hashing and for string ordering.
func parseJSON(data []byte) (any, error) {
	p := &jsonParser{data: data}
	p.skipSpace()
	v, err := p.value()
	if err != nil {
		return nil, err
	}
	p.skipSpace()
	if p.pos != len(p.data) {
		return nil, p.errorf("unexpected data after the JSON value")
	}
	return v, nil
}

type jsonParser struct {
	data []byte
	pos  int
}

func (p *jsonParser) errorf(format string, args ...any) error {
	return fmt.Errorf("railflag: invalid JSON at offset %d: %s", p.pos, fmt.Sprintf(format, args...))
}

func (p *jsonParser) skipSpace() {
	for p.pos < len(p.data) {
		switch p.data[p.pos] {
		case ' ', '\t', '\n', '\r':
			p.pos++
		default:
			return
		}
	}
}

func (p *jsonParser) value() (any, error) {
	if p.pos >= len(p.data) {
		return nil, p.errorf("unexpected end of input")
	}
	switch c := p.data[p.pos]; {
	case c == '{':
		return p.object()
	case c == '[':
		return p.array()
	case c == '"':
		return p.string()
	case c == 't':
		return true, p.literal("true")
	case c == 'f':
		return false, p.literal("false")
	case c == 'n':
		return nil, p.literal("null")
	case c == '-' || (c >= '0' && c <= '9'):
		return p.number()
	default:
		return nil, p.errorf("unexpected character %q", c)
	}
}

func (p *jsonParser) literal(word string) error {
	if p.pos+len(word) > len(p.data) || string(p.data[p.pos:p.pos+len(word)]) != word {
		return p.errorf("expected %s", word)
	}
	p.pos += len(word)
	return nil
}

func (p *jsonParser) object() (any, error) {
	p.pos++ // {
	out := map[string]any{}
	p.skipSpace()
	if p.pos < len(p.data) && p.data[p.pos] == '}' {
		p.pos++
		return out, nil
	}
	for {
		p.skipSpace()
		if p.pos >= len(p.data) || p.data[p.pos] != '"' {
			return nil, p.errorf("expected a string key")
		}
		k, err := p.string()
		if err != nil {
			return nil, err
		}
		p.skipSpace()
		if p.pos >= len(p.data) || p.data[p.pos] != ':' {
			return nil, p.errorf("expected ':'")
		}
		p.pos++
		p.skipSpace()
		v, err := p.value()
		if err != nil {
			return nil, err
		}
		out[k] = v
		p.skipSpace()
		if p.pos >= len(p.data) {
			return nil, p.errorf("unexpected end of input")
		}
		if p.data[p.pos] == ',' {
			p.pos++
			continue
		}
		if p.data[p.pos] == '}' {
			p.pos++
			return out, nil
		}
		return nil, p.errorf("expected ',' or '}'")
	}
}

func (p *jsonParser) array() (any, error) {
	p.pos++ // [
	out := []any{}
	p.skipSpace()
	if p.pos < len(p.data) && p.data[p.pos] == ']' {
		p.pos++
		return out, nil
	}
	for {
		p.skipSpace()
		v, err := p.value()
		if err != nil {
			return nil, err
		}
		out = append(out, v)
		p.skipSpace()
		if p.pos >= len(p.data) {
			return nil, p.errorf("unexpected end of input")
		}
		if p.data[p.pos] == ',' {
			p.pos++
			continue
		}
		if p.data[p.pos] == ']' {
			p.pos++
			return out, nil
		}
		return nil, p.errorf("expected ',' or ']'")
	}
}

func (p *jsonParser) number() (any, error) {
	start := p.pos
	for p.pos < len(p.data) {
		c := p.data[p.pos]
		if (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' {
			p.pos++
			continue
		}
		break
	}
	f, err := strconv.ParseFloat(string(p.data[start:p.pos]), 64)
	if err != nil {
		return nil, p.errorf("bad number %q", p.data[start:p.pos])
	}
	return f, nil
}

func (p *jsonParser) hex4() (rune, error) {
	if p.pos+4 > len(p.data) {
		return 0, p.errorf("short \\u escape")
	}
	n, err := strconv.ParseUint(string(p.data[p.pos:p.pos+4]), 16, 16)
	if err != nil {
		return 0, p.errorf("bad \\u escape")
	}
	p.pos += 4
	return rune(n), nil
}

func (p *jsonParser) string() (string, error) {
	p.pos++ // opening quote
	var buf []byte
	start := p.pos
	for {
		if p.pos >= len(p.data) {
			return "", p.errorf("unterminated string")
		}
		c := p.data[p.pos]
		if c == '"' {
			if buf == nil {
				s := string(p.data[start:p.pos])
				p.pos++
				return s, nil
			}
			buf = append(buf, p.data[start:p.pos]...)
			p.pos++
			return string(buf), nil
		}
		if c < 0x20 {
			return "", p.errorf("control character in string")
		}
		if c != '\\' {
			p.pos++
			continue
		}
		buf = append(buf, p.data[start:p.pos]...)
		p.pos++
		if p.pos >= len(p.data) {
			return "", p.errorf("unterminated escape")
		}
		e := p.data[p.pos]
		p.pos++
		switch e {
		case '"', '\\', '/':
			buf = append(buf, e)
		case 'b':
			buf = append(buf, '\b')
		case 'f':
			buf = append(buf, '\f')
		case 'n':
			buf = append(buf, '\n')
		case 'r':
			buf = append(buf, '\r')
		case 't':
			buf = append(buf, '\t')
		case 'u':
			r, err := p.hex4()
			if err != nil {
				return "", err
			}
			if utf16.IsSurrogate(r) && r < 0xDC00 && p.pos+6 <= len(p.data) && p.data[p.pos] == '\\' && p.data[p.pos+1] == 'u' {
				save := p.pos
				p.pos += 2
				lo, err := p.hex4()
				if err == nil && lo >= 0xDC00 && lo <= 0xDFFF {
					buf = utf8.AppendRune(buf, utf16.DecodeRune(r, lo))
					break
				}
				p.pos = save
			}
			buf = appendWTF8(buf, r)
		default:
			return "", p.errorf("bad escape \\%c", e)
		}
		start = p.pos
	}
}

// appendWTF8 appends r as UTF-8, writing a lone surrogate as its 3-byte (WTF-8) encoding.
func appendWTF8(b []byte, r rune) []byte {
	if utf16.IsSurrogate(r) {
		return append(b, byte(0xE0|r>>12), byte(0x80|(r>>6)&0x3F), byte(0x80|r&0x3F))
	}
	return utf8.AppendRune(b, r)
}
