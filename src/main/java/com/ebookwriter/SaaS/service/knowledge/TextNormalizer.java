package com.ebookwriter.SaaS.service.knowledge;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Content normalisation applied to every extracted text before it is stored or
 * sent to OpenAI: decode bytes (UTF-8, falling back to Latin-1), drop the BOM and
 * control characters, unify line endings, strip trailing whitespace and collapse
 * long runs of blank lines. Pure and static so it is trivially testable.
 */
public final class TextNormalizer {

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern TRAILING_WS = Pattern.compile("[ \\t]+\\n");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{4,}");
    private static final Pattern ANY_WS = Pattern.compile("\\s+");

    /** Marker appended to a document cut at the per-document limit. */
    public static final String TRUNCATION_MARKER = "\n[… truncated by Scrivetta: document too long …]";

    private TextNormalizer() {
    }

    /** Decode bytes as UTF-8 when valid, else as ISO-8859-1 (never throws). */
    public static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * True when the bytes look like text: no NUL bytes and almost no other
     * control characters (≤ 2%) in the first 8 KB. Encoding-agnostic on purpose, so
     * Latin-1 or Windows-1252 notes still count as text.
     */
    public static boolean looksLikeText(byte[] bytes) {
        int n = Math.min(bytes.length, 8192);
        int control = 0;
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) return false;
            if (b < 0x09 || (b > 0x0D && b < 0x20 && b != 0x1B)) control++;
        }
        return control <= Math.max(2, n / 50);
    }

    /** Normalise text (see class doc). Never returns null. */
    public static String normalize(String text) {
        if (text == null) return "";
        String s = text;
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = CONTROL.matcher(s).replaceAll("");
        s = TRAILING_WS.matcher(s + "\n").replaceAll("\n");
        s = MANY_BLANK_LINES.matcher(s).replaceAll("\n\n\n");
        return s.strip();
    }

    /** Cut {@code text} to {@code maxChars}, preferring a line boundary near the end. */
    public static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int cut = text.lastIndexOf('\n', maxChars);
        if (cut < maxChars * 0.8) cut = maxChars;
        return text.substring(0, cut) + TRUNCATION_MARKER;
    }

    /** Hash of the content with whitespace and case folded — identical material, same hash. */
    public static String contentHash(String text) {
        String folded = ANY_WS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return sha256(folded.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
