package com.vaonis.vesperahelper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;

/** Ethernet / VPN / LTE. Never the Vespera Wi‑Fi. */
final class InternetNetwork {
    private InternetNetwork() {}

    static boolean available(Context context) {
        return find(context) != null;
    }

    static Network find(Context context) {
        if (context == null) return null;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network vespera = VesperaConnectionService.getActiveNetwork();
        Network best = null;
        int bestScore = -1;
        for (Network network : cm.getAllNetworks()) {
            if (network.equals(vespera)) continue;
            int score = score(cm, network);
            if (score > bestScore) {
                bestScore = score;
                best = network;
            }
        }
        return best;
    }

    static HttpURLConnection openConnection(Context context, URL url) throws Exception {
        Network network = find(context);
        if (network != null) {
            try {
                return (HttpURLConnection) network.openConnection(url);
            } catch (Exception ignored) {
            }
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static int score(ConnectivityManager cm, Network network) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return -1;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && isVesperaLink(cm, network)) {
            return -1;
        }
        boolean internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        boolean cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        if (!internet && !ethernet) return -1;
        if (!ethernet && !vpn && !cell && !wifi) return -1;
        if (wifi && !internet) return -1;
        int score = 0;
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 8;
        if (internet) score += 4;
        if (ethernet) score += 6;
        else if (vpn) score += 4;
        else if (cell) score += 3;
        else if (wifi) score += 1;
        return score;
    }

    private static boolean isVesperaLink(ConnectivityManager cm, Network network) {
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return false;
        for (LinkAddress addr : lp.getLinkAddresses()) {
            InetAddress address = addr.getAddress();
            if (!(address instanceof Inet4Address)) continue;
            String host = address.getHostAddress();
            if (host != null && host.startsWith("10.0.0.")) return true;
        }
        return false;
    }
}
