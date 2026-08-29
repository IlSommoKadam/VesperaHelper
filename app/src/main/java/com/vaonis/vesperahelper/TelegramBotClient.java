package com.vaonis.vesperahelper;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/** Telegram Bot API over Ethernet/Internet, never the Vespera Wi‑Fi. */
final class TelegramBotClient {
    private static final String TAG = "VesperaTelegram";
    private static final int TIMEOUT_MS = 10_000;

    static final class Result {
        final boolean ok;
        final String error;

        Result(boolean ok, String error) {
            this.ok = ok;
            this.error = error == null ? "" : error;
        }
    }

    private TelegramBotClient() {}

    static Result getMe(Context context, String token) {
        return request(context, token, "getMe", null);
    }

    static Result sendMessage(Context context, String token, String chatId, String text) {
        if (chatId == null || chatId.trim().isEmpty()) {
            return new Result(false, "chat_id empty");
        }
        if (text == null || text.trim().isEmpty()) {
            return new Result(false, "text empty");
        }
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId.trim());
            body.put("text", text);
            body.put("disable_web_page_preview", true);
            return request(context, token, "sendMessage", body.toString());
        } catch (Exception failure) {
            return new Result(false, failure.getClass().getSimpleName());
        }
    }

    private static Result request(Context context, String token, String method, String jsonBody) {
        if (token == null || token.trim().isEmpty()) {
            return new Result(false, "token empty");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token.trim() + "/" + method);
            conn = (HttpsURLConnection) InternetNetwork.openConnection(context, url);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (jsonBody != null) {
                byte[] raw = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setFixedLengthStreamingMode(raw.length);
                OutputStream out = conn.getOutputStream();
                out.write(raw);
                out.flush();
            } else {
                conn.setRequestMethod("GET");
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                return new Result(false, "HTTP " + code + " " + telegramDescription(body));
            }
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", false)) {
                return new Result(false, telegramDescription(body));
            }
            return new Result(true, "");
        } catch (Exception failure) {
            Log.w(TAG, method + " " + failure.getClass().getSimpleName());
            return new Result(false, failure.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String telegramDescription(String body) {
        if (body == null || body.isEmpty()) return "empty";
        try {
            JSONObject json = new JSONObject(body);
            String desc = json.optString("description", "").trim();
            if (!desc.isEmpty()) return desc;
        } catch (Exception ignored) {
        }
        return body.length() > 120 ? body.substring(0, 120) : body;
    }

    private static String readAll(InputStream stream) {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }
}
