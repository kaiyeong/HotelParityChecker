package com.hotel.parity.admin;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Google Hotels property tokens (PIDs) from raw tokens,
 * /travel/hotels/entity/<PID> URLs, or /travel/search?...&qs=... URLs.
 */
public final class GoogleTravelUrlParser {

    private static final Pattern RAW_PID_PATTERN = Pattern.compile("^Ch[a-zA-Z0-9_-]{20,}$");
    private static final Pattern ENTITY_PATH_PATTERN = Pattern.compile("/travel/hotels/entity/(Ch[a-zA-Z0-9_-]{20,})");
    private static final Pattern QS_PARAM_PATTERN = Pattern.compile("(?:^|[?&])qs=([^&#]+)");
    private static final Pattern PROTOBUF_PID_PATTERN = Pattern.compile("([\\u0000-\\u00ff])(Ch[a-zA-Z0-9_-]{20,})");

    private GoogleTravelUrlParser() {
    }

    public static String extractPid(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (RAW_PID_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        Matcher entityMatcher = ENTITY_PATH_PATTERN.matcher(trimmed);
        if (entityMatcher.find()) {
            return entityMatcher.group(1);
        }

        Matcher qsMatcher = QS_PARAM_PATTERN.matcher(trimmed);
        if (qsMatcher.find()) {
            try {
                String qsEncoded = URLDecoder.decode(qsMatcher.group(1), StandardCharsets.UTF_8.name());
                String pidFromQs = extractPidFromQs(qsEncoded);
                if (pidFromQs != null) {
                    return pidFromQs;
                }
            } catch (Exception ignored) {
                // Fall through
            }
        }

        return null;
    }

    private static String extractPidFromQs(String qs) {
        if (qs == null || qs.trim().isEmpty()) {
            return null;
        }
        String normalized = qs.trim().replace('-', '+').replace('_', '/');
        int mod = normalized.length() % 4;
        if (mod > 0) {
            StringBuilder padded = new StringBuilder(normalized);
            for (int i = mod; i < 4; i++) {
                padded.append('=');
            }
            normalized = padded.toString();
        }
        byte[] decoded = Base64.getDecoder().decode(normalized);
        String latin1 = new String(decoded, StandardCharsets.ISO_8859_1);
        Matcher m = PROTOBUF_PID_PATTERN.matcher(latin1);
        if (m.find()) {
            int len = m.group(1).charAt(0) & 0xFF;
            String candidate = m.group(2);
            if (len >= 20 && len <= candidate.length()) {
                return candidate.substring(0, len);
            }
            return candidate;
        }
        return null;
    }
}
