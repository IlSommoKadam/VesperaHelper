package com.vaonis.vesperahelper;

/** Equatorial to horizontal altitude. Enough to know if a target is still up. */
final class SkyPosition {
    /** Below this altitude the target is treated as out of the observing window. */
    static final double MIN_OBSERVE_ALT_DEG = 15.0;

    private SkyPosition() {}

    /**
     * Altitude in degrees. {@code raDeg} may be negative (west of 0°).
     * Returns NaN if the inputs are unusable.
     */
    static double altitudeDeg(double latDeg, double lonDeg, double raDeg, double decDeg,
            long nowMs) {
        if (Double.isNaN(latDeg) || Double.isNaN(lonDeg)
                || Double.isNaN(raDeg) || Double.isNaN(decDeg)) {
            return Double.NaN;
        }
        if (decDeg > 90.0 || decDeg < -90.0) return Double.NaN;
        if (latDeg > 90.0 || latDeg < -90.0) return Double.NaN;
        double ra = norm360(raDeg);
        double jd = nowMs / 86_400_000.0 + 2440587.5;
        double days = jd - 2451545.0;
        double gmst = norm360(280.46061837 + 360.98564736629 * days);
        double lst = norm360(gmst + lonDeg);
        double ha = norm360(lst - ra);
        if (ha > 180.0) ha -= 360.0;
        double lat = Math.toRadians(latDeg);
        double dec = Math.toRadians(decDeg);
        double haRad = Math.toRadians(ha);
        double sinAlt = Math.sin(lat) * Math.sin(dec)
                + Math.cos(lat) * Math.cos(dec) * Math.cos(haRad);
        if (sinAlt > 1.0) sinAlt = 1.0;
        if (sinAlt < -1.0) sinAlt = -1.0;
        return Math.toDegrees(Math.asin(sinAlt));
    }

    private static double norm360(double value) {
        double v = value % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
}
