package com.axiom.dispatch;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Strips anything secret-shaped out of a connector's configuration.
 *
 * <p>Applied on the way IN and on the way OUT. On the way in, so a secret typed
 * into the config blob never reaches the database in plaintext — the connector
 * table has no encryption and is read by the health and catalogue endpoints. On
 * the way out, so a value written before this rule existed, or by a direct SQL
 * insert, still cannot be read back through the API.
 *
 * <p>Sanitising only on read would be the usual mistake: the row would still
 * hold the plaintext, and every backup, replica and support export would carry
 * it.
 */
public final class ConfigSanitiser {

    public static final String MASK = "********";

    /** Substrings that mark a config key as secret-shaped. */
    private static final String[] SECRET_KEY_MARKERS = {
            "secret", "token", "password", "passwd", "apikey", "api_key",
            "authorization", "auth", "credential", "privatekey", "private_key", "signingkey"
    };

    private ConfigSanitiser() {}

    public static boolean secretShaped(String key) {
        if (key == null) return false;
        String normalised = key.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
        for (String marker : SECRET_KEY_MARKERS) {
            if (normalised.contains(marker.replace("_", ""))) return true;
        }
        return false;
    }

    /** Drop secret-shaped entries entirely before persisting. */
    public static Map<String, Object> forStorage(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (secretShaped(key)) return;
            clean.put(key, value instanceof Map<?, ?> nested ? forStorage(cast(nested)) : value);
        });
        return clean;
    }

    /** Mask (rather than drop) on read, so an operator can see that something is there. */
    public static Map<String, Object> forDisplay(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return Map.of();
        Map<String, Object> shown = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (secretShaped(key)) {
                shown.put(key, MASK);
            } else if (value instanceof Map<?, ?> nested) {
                shown.put(key, forDisplay(cast(nested)));
            } else {
                shown.put(key, value);
            }
        });
        return shown;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
