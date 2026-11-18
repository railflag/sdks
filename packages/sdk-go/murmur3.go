package railflag

import (
	"math/bits"
	"unicode/utf16"
	"unicode/utf8"
)

// MurmurHash3 x86 32-bit, matching packages/eval/src/murmur3.ts bit for bit.

func hashBytes(data []byte, seed uint32) uint32 {
	const c1, c2 = 0xCC9E2D51, 0x1B873593
	h := seed
	n := len(data) &^ 3
	for i := 0; i < n; i += 4 {
		k := uint32(data[i]) | uint32(data[i+1])<<8 | uint32(data[i+2])<<16 | uint32(data[i+3])<<24
		k *= c1
		k = bits.RotateLeft32(k, 15)
		k *= c2
		h ^= k
		h = bits.RotateLeft32(h, 13)
		h = h*5 + 0xE6546B64
	}
	var k uint32
	switch len(data) & 3 {
	case 3:
		k ^= uint32(data[n+2]) << 16
		fallthrough
	case 2:
		k ^= uint32(data[n+1]) << 8
		fallthrough
	case 1:
		k ^= uint32(data[n])
		k *= c1
		k = bits.RotateLeft32(k, 15)
		k *= c2
		h ^= k
	}
	h ^= uint32(len(data))
	h ^= h >> 16
	h *= 0x85EBCA6B
	h ^= h >> 13
	h *= 0xC2B2AE35
	h ^= h >> 16
	return h
}

// murmur3 hashes the UTF-8 bytes of s, with lone surrogates encoded as U+FFFD like JavaScript's
// TextEncoder.
func murmur3(s string, seed uint32) uint32 {
	if utf8.ValidString(s) {
		return hashBytes([]byte(s), seed)
	}
	runes := utf16.Decode(utf16Units(s)) // lone surrogates → U+FFFD
	buf := make([]byte, 0, len(s))
	for _, r := range runes {
		buf = utf8.AppendRune(buf, r)
	}
	return hashBytes(buf, seed)
}

// murmur3Latin1 hashes the low byte of each UTF-16 code unit, which is how AB Tasty's SDKs and
// Unleash's Node client hash.
func murmur3Latin1(s string, seed uint32) uint32 {
	if isASCII(s) {
		return hashBytes([]byte(s), seed)
	}
	units := utf16Units(s)
	buf := make([]byte, len(units))
	for i, u := range units {
		buf[i] = byte(u)
	}
	return hashBytes(buf, seed)
}
