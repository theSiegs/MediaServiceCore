package com.liskovsoft.youtubeapi.lounge.sender;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The phone side of YouTube's "Link with TV code" (the lounge protocol, the one the receiver in
 * {@link com.liskovsoft.youtubeapi.lounge.LoungeService} answers): pairs with a screen by the code it shows, then opens a
 * video on it at a position. No Google account is needed. Blocking: call it off the main thread.
 */
public final class LoungeSender {
    private static final String GET_SCREEN_URL = "https://www.youtube.com/api/lounge/pairing/get_screen";
    private static final String GET_TOKEN_URL = "https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch";
    private static final String BIND_URL = "https://www.youtube.com/api/lounge/bc/bind";
    private static final String LOUNGE_TOKEN_HEADER = "X-YouTube-LoungeId-Token";
    private static final String APP = "youtube-desktop";
    private static final Pattern SID = Pattern.compile("\\[\\s*\"c\"\\s*,\\s*\"([^\"]+)\"");
    private static final Pattern GSESSIONID = Pattern.compile("\\[\\s*\"S\"\\s*,\\s*\"([^\"]+)\"");

    private final OkHttpClient mClient;
    private final String mSenderId;
    private final String mSenderName;
    private final Random mRandom = new Random();

    /** A screen paired by its code: kept by the caller, as the token works for days and can be renewed */
    public static final class Screen {
        public final String screenId;
        public final String loungeToken;
        @Nullable public final String name;

        public Screen(String screenId, String loungeToken, @Nullable String name) {
            this.screenId = screenId;
            this.loungeToken = loungeToken;
            this.name = name;
        }
    }

    /** The lounge didn't take a request: a wrong or expired code, or a token to renew */
    public static final class LoungeException extends IOException {
        public final int httpCode;

        public LoungeException(String message, int httpCode) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    /**
     * @param senderId a stable id for this phone (the screen lists remotes by it)
     * @param senderName what the screen shows for this phone
     */
    public LoungeSender(OkHttpClient client, String senderId, String senderName) {
        mClient = client;
        mSenderId = senderId != null ? senderId : UUID.randomUUID().toString();
        mSenderName = senderName;
    }

    /** Pairs with the screen showing this code (digits only; spaces and dashes are ignored) */
    public Screen pair(String code) throws IOException {
        String digits = code != null ? code.replaceAll("[^0-9]", "") : "";

        if (digits.isEmpty()) {
            throw new LoungeException("No code", 0);
        }

        String json = post(new Request.Builder().url(GET_SCREEN_URL)
                .post(new FormBody.Builder().add("pairing_code", digits).build()).build());
        Screen screen = parseScreen(json);

        if (screen == null) {
            throw new LoungeException("No screen for this code", 404);
        }

        return screen;
    }

    /** A fresh lounge token for a paired screen; null when the screen is gone */
    @Nullable
    public String renewToken(String screenId) throws IOException {
        String json = post(new Request.Builder().url(GET_TOKEN_URL)
                .post(new FormBody.Builder().add("screen_ids", screenId).build()).build());
        return parseToken(json, screenId);
    }

    /** Opens the video on the screen at this position (ms) */
    public void play(Screen screen, String videoId, long positionMs) throws IOException {
        int rid = 1 + mRandom.nextInt(10_000);

        // Join the screen's lounge as a remote
        HttpUrl bindUrl = baseParams(screen, rid++).addQueryParameter("CVER", "1").build();
        String bound = post(new Request.Builder().url(bindUrl).header(LOUNGE_TOKEN_HEADER, screen.loungeToken)
                .post(new FormBody.Builder().add("count", "0").build()).build());
        String[] session = parseSession(bound);

        if (session == null) {
            throw new LoungeException("The screen didn't take this phone", 0);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("videoId", videoId);
        fields.put("currentTime", String.valueOf(Math.max(0, positionMs) / 1000));
        fields.put("currentIndex", "-1");
        fields.put("audioOnly", "false");
        fields.put("listId", "");
        fields.put("videoIds", videoId);

        FormBody.Builder body = new FormBody.Builder().add("count", "1").add("ofs", "0").add("req0__sc", "setPlaylist");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.add("req0_" + field.getKey(), field.getValue());
        }

        HttpUrl commandUrl = baseParams(screen, rid)
                .addQueryParameter("SID", session[0])
                .addQueryParameter("gsessionid", session[1])
                .addQueryParameter("AID", "0")
                .addQueryParameter("v", "2")
                .build();
        post(new Request.Builder().url(commandUrl).header(LOUNGE_TOKEN_HEADER, screen.loungeToken).post(body.build()).build());
    }

    private HttpUrl.Builder baseParams(Screen screen, int rid) {
        return HttpUrl.get(BIND_URL).newBuilder()
                .addQueryParameter("device", "REMOTE_CONTROL")
                .addQueryParameter("id", mSenderId)
                .addQueryParameter("name", mSenderName)
                .addQueryParameter("app", APP)
                .addQueryParameter("mdx-version", "3")
                .addQueryParameter("loungeIdToken", screen.loungeToken)
                .addQueryParameter("VER", "8")
                .addQueryParameter("RID", String.valueOf(rid))
                .addQueryParameter("zx", Long.toString(mRandom.nextLong() & Long.MAX_VALUE, 36));
    }

    private String post(Request request) throws IOException {
        try (Response response = mClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";

            if (!response.isSuccessful()) {
                throw new LoungeException("HTTP " + response.code(), response.code());
            }

            return text;
        }
    }

    /** {"screen": {"screenId", "loungeToken", "name", ...}}, as get_screen answers */
    @Nullable
    static Screen parseScreen(String json) {
        try {
            JsonObject screen = new JsonParser().parse(json).getAsJsonObject().getAsJsonObject("screen");
            String screenId = string(screen, "screenId");
            String token = string(screen, "loungeToken");
            return screenId != null && token != null ? new Screen(screenId, token, string(screen, "name")) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {"screens": [{"screenId", "loungeToken", ...}]}, as get_lounge_token_batch answers */
    @Nullable
    static String parseToken(String json, String screenId) {
        try {
            JsonArray screens = new JsonParser().parse(json).getAsJsonObject().getAsJsonArray("screens");
            for (JsonElement element : screens) {
                JsonObject screen = element.getAsJsonObject();
                if (screenId.equals(string(screen, "screenId"))) {
                    return string(screen, "loungeToken");
                }
            }
        } catch (RuntimeException e) {
            // Unreadable: no token
        }
        return null;
    }

    /** The session id and gsessionid in a bind answer (length-prefixed JSON chunks); null when either is missing */
    @Nullable
    static String[] parseSession(String bound) {
        if (bound == null) {
            return null;
        }

        Matcher sid = SID.matcher(bound);
        Matcher gsession = GSESSIONID.matcher(bound);
        return sid.find() && gsession.find() ? new String[] {sid.group(1), gsession.group(1)} : null;
    }

    @Nullable
    private static String string(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
