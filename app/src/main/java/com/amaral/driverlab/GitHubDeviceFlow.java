package com.amaral.driverlab;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class GitHubDeviceFlow {
    interface Callback {
        void onCode(String userCode, String verificationUri);
        void onAuthorized(String token);
        void onError(Throwable error);
    }

    private GitHubDeviceFlow() {}

    static void authorize(String clientId, Callback callback) {
        new Thread(() -> {
            try {
                if (clientId == null || clientId.trim().isEmpty()) {
                    throw new IllegalStateException("GITHUB_CLIENT_ID não foi configurado no build");
                }
                JSONObject device = postForm(
                        "https://github.com/login/device/code",
                        "client_id=" + encode(clientId.trim()));
                String deviceCode = device.getString("device_code");
                String userCode = device.getString("user_code");
                String verificationUri = device.getString("verification_uri");
                int intervalSeconds = Math.max(5, device.optInt("interval", 5));
                long expiresAt = System.currentTimeMillis()
                        + device.optLong("expires_in", 900) * 1000L;
                callback.onCode(userCode, verificationUri);

                while (System.currentTimeMillis() < expiresAt) {
                    Thread.sleep(intervalSeconds * 1000L);
                    JSONObject tokenResponse = postForm(
                            "https://github.com/login/oauth/access_token",
                            "client_id=" + encode(clientId.trim())
                                    + "&device_code=" + encode(deviceCode)
                                    + "&grant_type="
                                    + encode("urn:ietf:params:oauth:grant-type:device_code"));
                    String accessToken = tokenResponse.optString("access_token", "");
                    if (!accessToken.isEmpty()) {
                        callback.onAuthorized(accessToken);
                        return;
                    }
                    String error = tokenResponse.optString("error", "");
                    if ("authorization_pending".equals(error)) continue;
                    if ("slow_down".equals(error)) {
                        intervalSeconds += 5;
                        continue;
                    }
                    if ("expired_token".equals(error)) {
                        throw new IllegalStateException("Código expirou; tente conectar novamente");
                    }
                    if ("access_denied".equals(error)) {
                        throw new IllegalStateException("Autorização negada no GitHub");
                    }
                    throw new IllegalStateException("GitHub Device Flow: "
                            + tokenResponse.optString("error_description", error));
                }
                throw new IllegalStateException("Tempo de autorização do GitHub expirou");
            } catch (Throwable error) {
                callback.onError(error);
            }
        }, "github-device-flow").start();
    }

    private static JSONObject postForm(String endpoint, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("User-Agent", "Amaral-Driver-Lab/" + BuildConfig.VERSION_NAME);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(input);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("GitHub respondeu HTTP " + status + ": " + response);
        }
        return new JSONObject(response);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
}
