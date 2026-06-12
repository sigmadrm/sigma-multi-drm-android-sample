package com.google.android.exoplayer2.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.util.Util;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SigmaDemoActivity extends AppCompatActivity {

    private PlayerView playerView;
    private SimpleExoPlayer player;
    private TextView textLogs, textTime;
    private EditText editManifestUri, editBaseUrl, editMerchantId, editAppId, editUserId, editSessionId;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isNetworkLost = false;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            runOnUiThread(() -> {
                if (isNetworkLost) {
                    log(">>> NETWORK: Back online. Checking recovery...");
                    isNetworkLost = false;
                    if (player != null) {
                        ExoPlaybackException error = player.getPlayerError();
                        if (error != null && error.type == ExoPlaybackException.TYPE_SOURCE) {
                            log(">>> NETWORK: Recovering from network error...");
                            player.prepare();
                            player.play();
                        } else if (error == null) {
                            player.play();
                        } else {
                            log(">>> NETWORK: Cannot auto-resume. Fatal Error detected.");
                        }
                    }
                }
            });
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(() -> {
                log(">>> NETWORK: Connection lost.");
                isNetworkLost = true;
            });
        }
    };

    private final BroadcastReceiver drmLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            if (msg != null) log(msg);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sigma_demo);

        registerReceiver(drmLogReceiver, new IntentFilter("SIGMA_DRM_LOG"));

        playerView = findViewById(R.id.player_view);
        textLogs = findViewById(R.id.text_logs);
        textLogs.setMovementMethod(new ScrollingMovementMethod());
        textTime = findViewById(R.id.text_time);
        
        editManifestUri = findViewById(R.id.edit_manifest_uri);
        editBaseUrl = findViewById(R.id.edit_base_url);
        editMerchantId = findViewById(R.id.edit_merchant_id);
        editAppId = findViewById(R.id.edit_app_id);
        editUserId = findViewById(R.id.edit_user_id);
        editSessionId = findViewById(R.id.edit_session_id);

        findViewById(R.id.btn_start).setOnClickListener(v -> startPlayback());
        findViewById(R.id.btn_play).setOnClickListener(v -> { if (player != null) player.play(); });
        findViewById(R.id.btn_pause).setOnClickListener(v -> { if (player != null) player.pause(); });
        findViewById(R.id.btn_reset).setOnClickListener(v -> resetApp());
        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> { textLogs.setText(""); log("Logs cleared."); });
        
        findViewById(R.id.btn_seek_back).setOnClickListener(v -> {
            if (player != null) player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
        });
        findViewById(R.id.btn_seek_forward).setOnClickListener(v -> {
            if (player != null) player.seekTo(player.getCurrentPosition() + 10000);
        });

        initializePlayer();
        registerNetworkCallback();
        log("App Ready (ExoPlayer 2.x). Input data and press START.");
    }

    private void initializePlayer() {
        player = new SimpleExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                log(isPlaying ? ">>> EVENT: Play" : ">>> EVENT: Pause");
                if (isPlaying) updateProgress();
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { 
                    log("Status: Playing (Ready)"); 
                    updateProgress(); 
                } else if (state == Player.STATE_BUFFERING) {
                    log("Status: Buffering...");
                } else if (state == Player.STATE_ENDED) {
                    log(">>> EVENT: Video Ended. Stopping session.");
                    player.stop();
                    player.clearMediaItems();
                }
            }
            @Override
            public void onPlayerError(ExoPlaybackException error) {
                log("[PLAYER] Fatal Error: " + error.getMessage());
            }
        });
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, networkCallback);
        }
    }

    private void resetApp() {
        editManifestUri.setText("https://sdrm-test.gviet.vn:9080/drm/static/vod_staging/the_box/manifest.mpd");
        editBaseUrl.setText("https://license-staging.sigmadrm.com/license/verify/widevine");
        editMerchantId.setText("sctv"); editAppId.setText("RedTV");
        editUserId.setText("U_Pnh_And"); editSessionId.setText("S_Pnh_And");
        log("UI Reseted.");
    }

    private void startPlayback() {
        if (player == null) initializePlayer();

        String manifestUri = editManifestUri.getText().toString().trim();
        String baseUrl = editBaseUrl.getText().toString().trim();
        String merchantId = editMerchantId.getText().toString().trim();
        String appId = editAppId.getText().toString().trim();
        String userId = editUserId.getText().toString().trim();
        String sessionId = editSessionId.getText().toString().trim();

        String finalBaseUrl = baseUrl;
        if (!finalBaseUrl.contains("/license/verify/widevine")) {
            finalBaseUrl += finalBaseUrl.endsWith("/") ? "license/verify/widevine" : "/license/verify/widevine";
        }
        String connector = finalBaseUrl.contains("?") ? "&" : "?";
        String licenseUrl = String.format(Locale.US, "%s%smerchantId=%s&appId=%s&userId=%s&sessionId=%s",
                finalBaseUrl, connector, android.net.Uri.encode(merchantId), android.net.Uri.encode(appId),
                android.net.Uri.encode(userId), android.net.Uri.encode(sessionId));

        log(">>> STARTING: Requesting Manifest...");
        
        // Cấu hình Retry Policy 3-5-10s
        DefaultLoadErrorHandlingPolicy retryPolicy = new DefaultLoadErrorHandlingPolicy(3) {
            @Override
            public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
                int errorCount = loadErrorInfo.errorCount;
                if (errorCount <= 3) {
                    long delayMs = (errorCount == 1) ? 3000 : (errorCount == 2 ? 5000 : 10000);
                    log(">>> SYSTEM: Will retry in " + (delayMs / 1000) + " seconds... (Retry " + errorCount + "/3)");
                    return delayMs;
                }
                return C.TIME_UNSET;
            }
        };

        // Cấu hình DRM cho ExoPlayer 2.x
        HttpMediaDrmCallback drmCallback = new HttpMediaDrmCallback(licenseUrl, new DefaultHttpDataSource.Factory());
        DefaultDrmSessionManager drmManager = new DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setLoadErrorHandlingPolicy(retryPolicy)
                .build(drmCallback);

        // Tạo MediaSource với DRM và Retry Policy
        MediaSource mediaSource = new DefaultMediaSourceFactory(new DefaultDataSourceFactory(this))
                .setDrmSessionManager(drmManager)
                .setLoadErrorHandlingPolicy(retryPolicy)
                .createMediaSource(MediaItem.fromUri(manifestUri));

        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }

    private void updateProgress() {
        if (player != null && player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady()) {
            textTime.setText(formatTime(player.getCurrentPosition()) + " / " + formatTime(player.getDuration()));
            handler.postDelayed(this::updateProgress, 1000);
        }
    }

    private String formatTime(long timeMs) {
        if (timeMs < 0) return "00:00";
        long s = timeMs / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", (s / 60) % 60, s % 60);
    }

    private void log(String message) {
        String time = dateFormat.format(new Date());
        runOnUiThread(() -> {
            String currentLogs = textLogs.getText().toString();
            textLogs.setText("[" + time + "] " + message + "\n" + currentLogs);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(drmLogReceiver);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        if (player != null) player.release();
    }
}
