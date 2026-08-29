package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists Ethernet DHCP vs static preferences for the Connections tab. */
final class EthernetStore {
    private static final String PREFS = "vespera_ethernet";
    private static final String KEY_MODE = "mode";
    private static final String KEY_IP = "ip";
    private static final String KEY_PREFIX = "prefix";
    private static final String KEY_GW = "gateway";
    private static final String KEY_DNS1 = "dns1";
    private static final String KEY_DNS2 = "dns2";

    static final String MODE_DHCP = "dhcp";
    static final String MODE_STATIC = "static";

    private final SharedPreferences prefs;

    private EthernetStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static EthernetStore from(Context context) {
        return new EthernetStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    String mode() {
        String mode = prefs.getString(KEY_MODE, MODE_DHCP);
        return MODE_STATIC.equals(mode) ? MODE_STATIC : MODE_DHCP;
    }

    boolean isStatic() {
        return MODE_STATIC.equals(mode());
    }

    String ip() { return prefs.getString(KEY_IP, ""); }
    String prefix() { return prefs.getString(KEY_PREFIX, "24"); }
    String gateway() { return prefs.getString(KEY_GW, ""); }
    String dns1() { return prefs.getString(KEY_DNS1, "8.8.8.8"); }
    String dns2() { return prefs.getString(KEY_DNS2, "8.8.4.4"); }

    void setMode(String mode) {
        prefs.edit().putString(KEY_MODE,
                MODE_STATIC.equals(mode) ? MODE_STATIC : MODE_DHCP).apply();
    }

    void setStatic(String ip, String prefix, String gateway, String dns1, String dns2) {
        prefs.edit()
                .putString(KEY_MODE, MODE_STATIC)
                .putString(KEY_IP, safe(ip))
                .putString(KEY_PREFIX, safe(prefix))
                .putString(KEY_GW, safe(gateway))
                .putString(KEY_DNS1, safe(dns1))
                .putString(KEY_DNS2, safe(dns2))
                .apply();
    }

    void setDhcp() {
        prefs.edit().putString(KEY_MODE, MODE_DHCP).apply();
    }

    void rememberFromStatus(EthernetNet.Status status) {
        if (status == null || !status.ok) return;
        SharedPreferences.Editor ed = prefs.edit();
        if (EthernetStore.MODE_STATIC.equals(status.mode)) {
            ed.putString(KEY_MODE, MODE_STATIC);
        } else if (EthernetStore.MODE_DHCP.equals(status.mode)) {
            ed.putString(KEY_MODE, MODE_DHCP);
        }
        if (status.ip != null && !status.ip.isEmpty() && !"-".equals(status.ip)) {
            ed.putString(KEY_IP, status.ip);
        }
        if (status.prefix != null && !status.prefix.isEmpty() && !"-".equals(status.prefix)) {
            ed.putString(KEY_PREFIX, status.prefix);
        }
        if (status.gateway != null && !status.gateway.isEmpty() && !"-".equals(status.gateway)) {
            ed.putString(KEY_GW, status.gateway);
        }
        if (status.dns1 != null && !status.dns1.isEmpty() && !"-".equals(status.dns1)) {
            ed.putString(KEY_DNS1, status.dns1);
        }
        if (status.dns2 != null && !status.dns2.isEmpty() && !"-".equals(status.dns2)) {
            ed.putString(KEY_DNS2, status.dns2);
        }
        ed.apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
