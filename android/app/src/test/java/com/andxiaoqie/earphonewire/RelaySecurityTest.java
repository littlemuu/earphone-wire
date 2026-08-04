package com.andxiaoqie.earphonewire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class RelaySecurityTest {
    @Test public void mapsPlaybackStatesToTheServerEnum() {
        assertEquals("playing", PlaybackStateMapper.toUploadState(3));
        assertEquals("paused", PlaybackStateMapper.toUploadState(2));
        assertEquals("stopped", PlaybackStateMapper.toUploadState(1));
        assertEquals("buffering", PlaybackStateMapper.toUploadState(6));
        assertEquals("connecting", PlaybackStateMapper.toUploadState(8));
        assertEquals("error", PlaybackStateMapper.toUploadState(7));
        assertEquals("unknown", PlaybackStateMapper.toUploadState(0));
    }

    @Test public void acceptsOnlyHttpsOriginsWithoutPathsQueriesOrFragments() {
        assertEquals("https://earphone-wire-mcp.andxiaoqie.workers.dev",
                PairingOrigin.normalize("https://earphone-wire-mcp.andxiaoqie.workers.dev/"));
        for (String invalid : new String[]{"http://example.test", "https://example.test/path", "https://example.test?q=x", "https://example.test#x"}) {
            try {
                PairingOrigin.normalize(invalid);
                throw new AssertionError("Expected invalid origin: " + invalid);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }

    @Test public void serializesOnlyTheStrictServerFieldsWithinFourKiB() {
        NowPlayingPayload payload = NowPlayingPayload.create(
                "550e8400-e29b-41d4-a716-446655440000", " Title ", " Artist ",
                "playing", "2026-08-04T00:00:00.000Z");
        String json = payload.toJson();
        assertTrue(json.contains("\"eventId\""));
        assertTrue(json.contains("\"playerPackage\":\"com.tencent.qqmusic\""));
        assertFalse(json.contains("available"));
        assertFalse(json.contains("receivedAt"));
        assertFalse(json.contains("source"));
        assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096);
    }

    @Test public void rejectsInvalidPayloadValuesAndOversizedBodies() {
        try {
            NowPlayingPayload.create("not-a-uuid", "title", "", "playing", "2026-08-04T00:00:00.000Z");
            throw new AssertionError("Expected UUID rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            NowPlayingPayload.create("550e8400-e29b-41d4-a716-446655440000", "title", "", "not-a-state", "2026-08-04T00:00:00.000Z");
            throw new AssertionError("Expected state rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            NowPlayingPayload.create("550e8400-e29b-41d4-a716-446655440000", "x".repeat(301), "", "playing", "2026-08-04T00:00:00.000Z");
            throw new AssertionError("Expected length rejection");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void newObservationsGetNewIdsWhileRetryPayloadKeepsItsId() {
        NowPlayingPayload first = NowPlayingPayload.create("title", "artist", "playing");
        NowPlayingPayload retry = first;
        NowPlayingPayload newer = NowPlayingPayload.create("title", "artist", "paused");
        assertEquals(first.eventId, retry.eventId);
        assertNotEquals(first.eventId, newer.eventId);
    }

    @Test public void hasBoundedRetryAndResponsePolicies() {
        assertEquals(60_000L, HeartbeatPolicy.INTERVAL_MILLIS);
        assertEquals(2000L, RetryPolicy.delayMillis(1));
        assertEquals(32000L, RetryPolicy.delayMillis(5));
        assertEquals(-1L, RetryPolicy.delayMillis(6));
        assertEquals(UploadOutcome.SUCCESS, UploadOutcome.fromHttpStatus(204));
        assertEquals(UploadOutcome.REPAIR_REQUIRED, UploadOutcome.fromHttpStatus(401));
        assertEquals(UploadOutcome.SUPERSEDED, UploadOutcome.fromHttpStatus(409));
        assertEquals(UploadOutcome.RETRYABLE, UploadOutcome.fromHttpStatus(503));
    }

    @Test public void sourceAndApkConfigurationDoNotContainEmbeddedCredentials() throws Exception {
        File root = new File(System.getProperty("user.dir"));
        File source = new File(root, "src/main");
        if (!source.isDirectory()) source = new File(root, "app/src/main");
        final File scanRoot = source;
        assertTrue("Could not locate Android source", scanRoot.isDirectory());
        Files.walk(scanRoot.toPath()).filter(Files::isRegularFile).forEach(path -> {
            try {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertFalse("Embedded authorization header in " + path,
                        text.matches("(?s).*Bearer\\s+[A-Za-z0-9_-]{20,}.*"));
                assertFalse("Credential in BuildConfig/resources in " + path,
                        text.matches("(?s).*(ANDROID_UPLOAD_TOKEN|GITHUB_CLIENT_SECRET)\\s*[=:]\\s*[\\\"'][^\\\"']{8,}.*"));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }
}
