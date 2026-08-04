package com.andxiaoqie.earphonewire;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS-only uploader. It never follows redirects or writes request data to logs. */
public final class NowPlayingUploader {
    public UploadOutcome upload(PairingStore.Pairing pairing, NowPlayingPayload payload) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(pairing.origin + "/api/v1/now-playing");
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + pairing.token);
            byte[] bytes = payload.toJson().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            return UploadOutcome.fromHttpStatus(connection.getResponseCode());
        } catch (Exception error) {
            return UploadOutcome.RETRYABLE;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public boolean testConnection(String origin) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + "/health").openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            return connection.getResponseCode() == 200;
        } catch (Exception error) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
