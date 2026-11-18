package com.railflag.openfeature;

/** MurmurHash3 x86 32-bit, matching packages/eval/src/murmur3.ts bit for bit. Results are unsigned. */
final class Murmur3 {
    private Murmur3() {}

    @SuppressWarnings("fallthrough") // the tail bytes fall through on purpose, as in the reference implementation
    static long hashBytes(byte[] data, int len, long seed) {
        final int c1 = 0xcc9e2d51, c2 = 0x1b873593;
        int h = (int) seed;
        int n = len & ~3;
        for (int i = 0; i < n; i += 4) {
            int k = (data[i] & 0xff) | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff) << 16 | (data[i + 3] & 0xff) << 24;
            k *= c1;
            k = Integer.rotateLeft(k, 15);
            k *= c2;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        int k = 0;
        switch (len & 3) {
            case 3:
                k ^= (data[n + 2] & 0xff) << 16;
                // fall through
            case 2:
                k ^= (data[n + 1] & 0xff) << 8;
                // fall through
            case 1:
                k ^= data[n] & 0xff;
                k *= c1;
                k = Integer.rotateLeft(k, 15);
                k *= c2;
                h ^= k;
                break;
            default:
                break;
        }
        h ^= len;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h & 0xffffffffL;
    }

    /**
     * Over UTF-8, with lone surrogates encoded as U+FFFD like JavaScript's TextEncoder.
     * (String.getBytes(UTF_8) would write '?' for them.)
     */
    static long hash(String s, long seed) {
        byte[] buf = new byte[s.length() * 3];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cp = c;
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = Character.toCodePoint(c, s.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                cp = 0xFFFD;
            }
            if (cp < 0x80) {
                buf[n++] = (byte) cp;
            } else if (cp < 0x800) {
                buf[n++] = (byte) (0xC0 | cp >> 6);
                buf[n++] = (byte) (0x80 | cp & 0x3F);
            } else if (cp < 0x10000) {
                buf[n++] = (byte) (0xE0 | cp >> 12);
                buf[n++] = (byte) (0x80 | cp >> 6 & 0x3F);
                buf[n++] = (byte) (0x80 | cp & 0x3F);
            } else {
                buf[n++] = (byte) (0xF0 | cp >> 18);
                buf[n++] = (byte) (0x80 | cp >> 12 & 0x3F);
                buf[n++] = (byte) (0x80 | cp >> 6 & 0x3F);
                buf[n++] = (byte) (0x80 | cp & 0x3F);
            }
        }
        return hashBytes(buf, n, seed);
    }

    /** Over the low byte of each UTF-16 code unit, which is how AB Tasty's SDKs and Unleash's Node client hash. */
    static long hashLatin1(String s, long seed) {
        byte[] buf = new byte[s.length()];
        for (int i = 0; i < buf.length; i++) buf[i] = (byte) s.charAt(i);
        return hashBytes(buf, buf.length, seed);
    }
}
