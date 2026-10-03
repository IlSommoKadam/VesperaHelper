package com.vaonis.vesperahelper;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Telegram Bot API over the default uplink (Ethernet/VPN), same path as meteo. */
final class TelegramBotClient {
    private static final String TAG = "VesperaTelegram";
    private static final String HOST = "api.telegram.org";

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
        // Path contains the bot token: never log it.
        String path = "/bot" + token.trim() + "/" + method;
        try {
            HostDns.HttpResult http = jsonBody == null
                    ? HostDns.httpsGet(context, HOST, path)
                    : HostDns.httpsPost(context, HOST, path,
                            jsonBody.getBytes(StandardCharsets.UTF_8));
            String body = http.body == null ? "" : http.body;
            if (http.code < 200 || http.code >= 300) {
                return new Result(false, "HTTP " + http.code + " " + telegramDescription(body));
            }
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", false)) {
                return new Result(false, telegramDescription(body));
            }
            return new Result(true, "");
        } catch (Exception failure) {
            String detail = failure.getMessage();
            if (detail == null || detail.isEmpty()) detail = failure.getClass().getSimpleName();
            Log.w(TAG, method + " " + detail);
            return new Result(false, detail);
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
}
