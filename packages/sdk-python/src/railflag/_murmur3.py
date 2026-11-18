"""MurmurHash3 x86 32-bit, matching `packages/eval/src/murmur3.ts` bit for bit."""

from __future__ import annotations

_C1 = 0xCC9E2D51
_C2 = 0x1B873593
_M = 0xFFFFFFFF


def _rotl(x: int, r: int) -> int:
    return ((x << r) | (x >> (32 - r))) & _M


def hash_bytes(data: bytes, seed: int = 0) -> int:
    h = seed & _M
    n = len(data) & ~3
    for i in range(0, n, 4):
        k = data[i] | (data[i + 1] << 8) | (data[i + 2] << 16) | (data[i + 3] << 24)
        k = (k * _C1) & _M
        k = _rotl(k, 15)
        k = (k * _C2) & _M
        h ^= k
        h = _rotl(h, 13)
        h = (h * 5 + 0xE6546B64) & _M
    rem = len(data) & 3
    k = 0
    if rem == 3:
        k ^= data[n + 2] << 16
    if rem >= 2:
        k ^= data[n + 1] << 8
    if rem >= 1:
        k ^= data[n]
        k = (k * _C1) & _M
        k = _rotl(k, 15)
        k = (k * _C2) & _M
        h ^= k
    h ^= len(data) & _M
    h ^= h >> 16
    h = (h * 0x85EBCA6B) & _M
    h ^= h >> 13
    h = (h * 0xC2B2AE35) & _M
    h ^= h >> 16
    return h


def _utf16le(s: str) -> bytes:
    return s.encode("utf-16-le", "surrogatepass")


def murmur3(s: str, seed: int = 0) -> int:
    """Over UTF-8 bytes, with unpaired surrogates encoded as U+FFFD like JavaScript's TextEncoder."""
    well_formed = _utf16le(s).decode("utf-16-le", "replace")
    return hash_bytes(well_formed.encode("utf-8"), seed)


def murmur3_latin1(s: str, seed: int = 0) -> int:
    """Over the low byte of each UTF-16 code unit, which is how AB Tasty's SDKs hash."""
    return hash_bytes(_utf16le(s)[0::2], seed)
