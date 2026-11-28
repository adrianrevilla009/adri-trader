package com.adrianrevilla.trading.core.domain;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 Base32 decoder, the format authenticator apps use for secrets. Java has no built-in one. */
public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    /** Spaces, dashes and '=' padding are ignored; lower case is accepted. Anything else is an error. */
    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("the Base32 text is required");
        }
        String clean = text.replaceAll("[ \\t\\r\\n=-]", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("the Base32 text is empty");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("not a Base32 character: '" + c + "'");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
