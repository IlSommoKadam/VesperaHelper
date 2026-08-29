package com.vaonis.vesperahelper;

import java.util.Locale;

/** ISO 3166-1 alpha-2 → flag emoji (regional indicators). */
final class CountryFlags {
    private CountryFlags() {}

    static String emoji(String iso2) {
        if (iso2 == null) return "";
        String code = iso2.trim().toUpperCase(Locale.US);
        if (code.length() != 2) return "";
        char a = code.charAt(0);
        char b = code.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        return new String(Character.toChars(0x1F1E6 + (a - 'A')))
                + new String(Character.toChars(0x1F1E6 + (b - 'A')));
    }
}
