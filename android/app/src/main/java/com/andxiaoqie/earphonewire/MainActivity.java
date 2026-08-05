package com.andxiaoqie.earphonewire;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String QQ_MUSIC_PACKAGE = "com.tencent.qqmusic";
    private static final int COLOR_READY = Color.rgb(31, 122, 78);
    private static final int COLOR_WAITING = Color.rgb(174, 92, 31);

    private ComponentName listenerComponent;
    private TextView accessStatus;
    private TextView resultTitle;
    private TextView resultArtist;
    private TextView resultDetails;
    private Button openAccessButton;
    private Button refreshButton;
    private EditText workerOriginInput;
    private EditText uploadTokenInput;
    private TextView pairingStatus;
    private TextView uploadStatus;
    private PairingStore pairingStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listenerComponent = new ComponentName(this, PlaybackNotificationListenerService.class);
        accessStatus = findViewById(R.id.access_status);
        resultTitle = findViewById(R.id.result_title);
        resultArtist = findViewById(R.id.result_artist);
        resultDetails = findViewById(R.id.result_details);
        openAccessButton = findViewById(R.id.open_access_button);
        refreshButton = findViewById(R.id.refresh_button);
        workerOriginInput = findViewById(R.id.worker_origin_input);
        uploadTokenInput = findViewById(R.id.upload_token_input);
        pairingStatus = findViewById(R.id.pairing_status);
        uploadStatus = findViewById(R.id.upload_status);
        pairingStore = new PairingStore(this);

        workerOriginInput.setText(pairingStore.savedOrigin());
        updatePairingStatus();
        updateUploadStatus();

        openAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openNotificationAccessSettings();
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshPlayback();
            }
        });
        findViewById(R.id.save_pairing_button).setOnClickListener(view -> savePairing());
        findViewById(R.id.test_connection_button).setOnClickListener(view -> testConnection());
        findViewById(R.id.clear_pairing_button).setOnClickListener(view -> clearPairing());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessState();
        updatePairingStatus();
        updateUploadStatus();
        if (isNotificationListenerEnabled()) {
            refreshPlayback();
        }
    }

    private void savePairing() {
        try {
            String token = uploadTokenInput.getText().toString();
            if (TextUtils.isEmpty(token)) {
                if (pairingStore.requiresRePairing()) {
                    throw new IllegalArgumentException(getString(R.string.new_token_required));
                }
                PairingStore.Pairing existing = pairingStore.load();
                if (existing == null) throw new IllegalArgumentException(getString(R.string.token_required));
                token = existing.token;
            }
            pairingStore.save(workerOriginInput.getText().toString(), token);
            uploadTokenInput.setText(""); // Never redisplay a saved token.
            updatePairingStatus();
            PairingEvents.notifyChanged();
        } catch (Exception error) {
            pairingStatus.setText(error.getMessage() == null ? getString(R.string.pairing_invalid) : error.getMessage());
        }
    }

    private void testConnection() {
        final String origin;
        try {
            origin = PairingOrigin.normalize(workerOriginInput.getText().toString());
        } catch (IllegalArgumentException error) {
            pairingStatus.setText(error.getMessage());
            return;
        }
        pairingStatus.setText(R.string.testing_connection);
        new Thread(() -> {
            boolean connected = new NowPlayingUploader().testConnection(origin);
            runOnUiThread(() -> pairingStatus.setText(
                    connected ? R.string.connection_ok : R.string.connection_failed));
        }, "earphone-wire-connection-test").start();
    }

    private void clearPairing() {
        pairingStore.clear();
        uploadTokenInput.setText("");
        updatePairingStatus();
        updateUploadStatus();
        PairingEvents.notifyChanged();
    }

    private void updatePairingStatus() {
        if (pairingStore.requiresRePairing()) {
            pairingStatus.setText(R.string.repair_required);
        } else {
            pairingStatus.setText(pairingStore.isPaired() ? R.string.paired_token_hidden : R.string.not_paired);
        }
    }

    private void updateUploadStatus() {
        String status = pairingStore.lastUploadStatus();
        long time = pairingStore.lastUploadTime();
        if (status == null || time == 0L) {
            uploadStatus.setText(R.string.upload_not_sent);
            return;
        }
        String renderedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(time));
        uploadStatus.setText(getString(R.string.upload_status_format, status, renderedTime));
    }

    private void updateAccessState() {
        boolean enabled = isNotificationListenerEnabled();
        accessStatus.setText(enabled ? R.string.access_enabled : R.string.access_disabled);
        accessStatus.setTextColor(enabled ? COLOR_READY : COLOR_WAITING);
        refreshButton.setEnabled(enabled);
        openAccessButton.setText(enabled ? R.string.review_access : R.string.grant_access);

        if (!enabled) {
            resultTitle.setText(R.string.waiting_for_access_title);
            resultArtist.setText(R.string.waiting_for_access_body);
            resultDetails.setText(R.string.waiting_for_access_detail);
        }
    }

    private boolean isNotificationListenerEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                return manager.isNotificationListenerAccessGranted(listenerComponent);
            }
        }

        String enabledListeners = Settings.Secure.getString(
                getContentResolver(),
                "enabled_notification_listeners"
        );
        if (TextUtils.isEmpty(enabledListeners)) {
            return false;
        }

        String[] flattenedComponents = enabledListeners.split(":");
        for (String flattenedComponent : flattenedComponents) {
            ComponentName component = ComponentName.unflattenFromString(flattenedComponent);
            if (listenerComponent.equals(component)) {
                return true;
            }
        }
        return false;
    }

    private void openNotificationAccessSettings() {
        Intent listIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);

        // EMUI/HarmonyOS may silently consume the public detail intent.  On Huawei
        // and Honor devices, opening the public list directly is more reliable.
        if (isHuaweiOrHonorDevice()) {
            if (startSettingsActivity(listIntent)) {
                return;
            }
            startSettingsActivity(settingsIntent);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent detailIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            detailIntent.putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    listenerComponent
            );
            if (startSettingsActivity(detailIntent)) {
                return;
            }
        }

        if (startSettingsActivity(listIntent)) {
            return;
        }

        startSettingsActivity(settingsIntent);
    }

    private boolean isHuaweiOrHonorDevice() {
        return isHuaweiOrHonor(Build.MANUFACTURER) || isHuaweiOrHonor(Build.BRAND);
    }

    private boolean isHuaweiOrHonor(String value) {
        return "HUAWEI".equalsIgnoreCase(value) || "HONOR".equalsIgnoreCase(value);
    }

    private boolean startSettingsActivity(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void refreshPlayback() {
        if (!isNotificationListenerEnabled()) {
            updateAccessState();
            return;
        }

        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            if (manager == null) {
                showFailure(
                        getString(R.string.media_service_unavailable),
                        getString(R.string.media_service_unavailable_detail)
                );
                return;
            }

            List<MediaController> controllers = manager.getActiveSessions(listenerComponent);
            MediaController qqMusic = findQqMusicController(controllers);
            if (qqMusic == null) {
                showNoQqMusicSession(controllers);
                return;
            }

            showQqMusicSession(qqMusic);
        } catch (SecurityException error) {
            showFailure(
                    getString(R.string.access_not_ready),
                    getString(R.string.access_not_ready_detail)
            );
        } catch (RuntimeException error) {
            showFailure(
                    getString(R.string.read_failed),
                    error.getClass().getSimpleName() + ": " + safeMessage(error)
            );
        }
    }

    private MediaController findQqMusicController(List<MediaController> controllers) {
        for (MediaController controller : controllers) {
            if (QQ_MUSIC_PACKAGE.equals(controller.getPackageName())) {
                return controller;
            }
        }
        return null;
    }

    private void showNoQqMusicSession(List<MediaController> controllers) {
        resultTitle.setText(R.string.qq_music_not_found);
        resultArtist.setText(R.string.start_qq_music_hint);

        if (controllers.isEmpty()) {
            resultDetails.setText(R.string.no_active_sessions);
            return;
        }

        List<String> packages = new ArrayList<>();
        for (MediaController controller : controllers) {
            String packageName = controller.getPackageName();
            if (!packages.contains(packageName)) {
                packages.add(packageName);
            }
        }
        resultDetails.setText(getString(
                R.string.other_sessions_found,
                TextUtils.join("\n", packages)
        ));
    }

    private void showQqMusicSession(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        QqMusicMetadataMapper.Result normalized = QqMusicMetadataMapper.map(metadata);

        resultTitle.setText(orFallback(normalized.title, getString(R.string.unknown_title)));
        resultArtist.setText(orFallback(normalized.artist, getString(R.string.unknown_artist)));

        String observedAt = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
        ).format(new Date());
        resultDetails.setText(getString(
                R.string.playback_details,
                playbackStateLabel(controller.getPlaybackState()),
                controller.getPackageName(),
                observedAt
        ));
    }

    private CharSequence orFallback(CharSequence value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private String playbackStateLabel(PlaybackState playbackState) {
        if (playbackState == null) {
            return getString(R.string.state_unknown);
        }

        switch (playbackState.getState()) {
            case PlaybackState.STATE_PLAYING:
                return getString(R.string.state_playing);
            case PlaybackState.STATE_PAUSED:
                return getString(R.string.state_paused);
            case PlaybackState.STATE_STOPPED:
                return getString(R.string.state_stopped);
            case PlaybackState.STATE_BUFFERING:
                return getString(R.string.state_buffering);
            case PlaybackState.STATE_CONNECTING:
                return getString(R.string.state_connecting);
            case PlaybackState.STATE_ERROR:
                return getString(R.string.state_error);
            case PlaybackState.STATE_NONE:
                return getString(R.string.state_none);
            default:
                return getString(R.string.state_other, playbackState.getState());
        }
    }

    private void showFailure(String title, String detail) {
        resultTitle.setText(title);
        resultArtist.setText(R.string.try_again_hint);
        resultDetails.setText(detail);
    }

    private String safeMessage(RuntimeException error) {
        return error.getMessage() == null ? getString(R.string.no_error_message) : error.getMessage();
    }
}
