package com.example.exoplayer;

import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.UUID;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManagerProvider;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.ErrorMessageProvider;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.Util;

import com.sigma.packer.SigmaMediaDrm;

public class PlayerActivity extends AppCompatActivity implements StyledPlayerView.ControllerVisibilityListener {
  private StyledPlayerView playerView;
  private ExoPlayer player;
  private DefaultTrackSelector trackSelector;

  String drmLicenseUrl;
  String videoPath;

  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    initStream();
    setContentView(R.layout.activity_player);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();
  }

  private void initStream() {
    videoPath = "https://sdrm-test.gviet.vn:9080/static/vod_staging/the_box/manifest.mpd";
    drmLicenseUrl = "https://license-staging.sigmadrm.com/license/verify/widevine";
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Util.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Util.SDK_INT <= 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Util.SDK_INT <= 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Util.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  private void releasePlayer() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  private void initializePlayer() {
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoPath));
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(getApplicationContext())
            .setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
              @Override
              public DrmSessionManager get(MediaItem mediaItem) {
                return createDrmSessionManager(mediaItem);
              }
            });
    MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);

    trackSelector = new DefaultTrackSelector(/* context= */ this);
    DefaultTrackSelector.Parameters trackSelectionParameters =
        new DefaultTrackSelector.ParametersBuilder(/* context= */ this)
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowVideoNonSeamlessAdaptiveness(true)
            .build();
    player = new ExoPlayer.Builder(getApplicationContext())
        .setTrackSelector(trackSelector)
        .build();
    playerView.setPlayer(player);
    player.addAnalyticsListener(new EventLogger(trackSelector));
    player.setTrackSelectionParameters(trackSelectionParameters);
    player.setMediaSource(mediaSource);
    player.setPlayWhenReady(true);
    player.prepare();
  }

  private DrmSessionManager createDrmSessionManager(MediaItem mediaItem) {
    DrmSessionManager drmSessionManager;
    if (Util.SDK_INT >= 18) {
      UUID drmSchemeUuid = Assertions.checkNotNull(Util.getDrmUuid("widevine"));
      MediaDrmCallback drmCallback = createMediaDrmCallback(drmLicenseUrl, null);
      drmSessionManager =
          new DefaultDrmSessionManager.Builder()
              .setMultiSession(true)
              .setUuidAndExoMediaDrmProvider(drmSchemeUuid, SigmaMediaDrm.DEFAULT_PROVIDER)
              .build(drmCallback);
    } else {
      drmSessionManager = DrmSessionManager.DRM_UNSUPPORTED;
    }
    return drmSessionManager;
  }

  private WidevineMediaDrmCallback createMediaDrmCallback(String licenseUrl, String[] keyRequestPropertiesArray) {
    HttpDataSource.Factory licenseDataSourceFactory =
        ((ExoplayerApplication) getApplication()).buildHttpDataSourceFactory();
    WidevineMediaDrmCallback drmCallback =
        new WidevineMediaDrmCallback(licenseUrl, licenseDataSourceFactory);
    if (keyRequestPropertiesArray != null) {
      for (int i = 0; i < keyRequestPropertiesArray.length - 1; i += 2) {
        drmCallback.setKeyRequestProperty(keyRequestPropertiesArray[i],
            keyRequestPropertiesArray[i + 1]);
      }
    }
    return drmCallback;
  }


  private class PlayerErrorMessageProvider implements ErrorMessageProvider<PlaybackException> {
    @Override
    public Pair<Integer, String> getErrorMessage(PlaybackException error) {
      String errorCode = error.errorCode + ":" + error.getErrorCodeName();
      Log.e("SigmaPlayer Error ", " ErrorCode " + errorCode);
      return Pair.create(0, errorCode);
    }
  }

  @Override
  public void onVisibilityChanged(int visibility) {

  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }


  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

}
