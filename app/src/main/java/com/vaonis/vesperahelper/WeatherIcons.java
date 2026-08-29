package com.vaonis.vesperahelper;

import android.content.Context;

/** Maps Open-Meteo / WMO weather codes to icons and short labels. */
final class WeatherIcons {
    private WeatherIcons() {}

    static int drawableFor(int weatherCode) {
        if (weatherCode == 0 || weatherCode == 1) return R.drawable.ic_wx_clear;
        if (weatherCode == 2 || weatherCode == 3) return R.drawable.ic_wx_cloud;
        if (weatherCode == 45 || weatherCode == 48) return R.drawable.ic_wx_fog;
        if (weatherCode >= 71 && weatherCode <= 77) return R.drawable.ic_wx_snow;
        if (weatherCode == 85 || weatherCode == 86) return R.drawable.ic_wx_snow;
        if (weatherCode >= 95 && weatherCode <= 99) return R.drawable.ic_wx_storm;
        if (weatherCode >= 51 && weatherCode <= 67) return R.drawable.ic_wx_rain;
        if (weatherCode >= 80 && weatherCode <= 82) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    static String shortLabel(Context context, int weatherCode) {
        if (context == null) return "";
        if (weatherCode == 0 || weatherCode == 1) {
            return context.getString(R.string.forecast_wx_clear);
        }
        if (weatherCode == 2 || weatherCode == 3) {
            return context.getString(R.string.forecast_wx_cloud);
        }
        if (weatherCode == 45 || weatherCode == 48) {
            return context.getString(R.string.forecast_wx_fog);
        }
        if ((weatherCode >= 71 && weatherCode <= 77)
                || weatherCode == 85 || weatherCode == 86) {
            return context.getString(R.string.forecast_wx_snow);
        }
        if (weatherCode >= 95 && weatherCode <= 99) {
            return context.getString(R.string.forecast_wx_storm);
        }
        if ((weatherCode >= 51 && weatherCode <= 67)
                || (weatherCode >= 80 && weatherCode <= 82)) {
            return context.getString(R.string.forecast_wx_rain);
        }
        return context.getString(R.string.forecast_wx_cloud);
    }
}
