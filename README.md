# 1. Introduction
- To play DRM-protected content with ExoPlayer, your application must inject a DrmSessionManager into MediaCodecVideoTrackRenderer. A DrmSessionManager object is responsible for providing the MediaCrypto object needed to decrypt, as well as ensuring decryption keys are available for the DRM modules to be used.
- ExoPlayer provides a default instance of DrmSessionManager called StreamingDrmSessionManager - which uses MediaDrm. The StreamingDrmSessionManager class requires a MediaDrmCallback to be injected.
# 2. Require
- **Operating system:** ```Android 16+```
- **Install Exoplayer 2**:
    - Add a dependency in the build.gradle file of your app module. The following will add a dependency to the full library:
        ```
            implementation 'com.google.android.exoplayer:exoplayer:2.11.7'
            implementation 'com.google.android.exoplayer:exoplayer-core:2.11.7'
            implementation 'com.google.android.exoplayer:exoplayer-dash:2.11.7'
            implementation 'com.google.android.exoplayer:exoplayer-ui:2.11.7'
        ```
        - exoplayer-core: Core functionality (required).
        - exoplayer-dash: Support for DASH content.
        - exoplayer-hls: Support for HLS content.
        - exoplayer-smoothstreaming: Support for SmoothStreaming content.
        - exoplayer-ui: UI components and resources for use with ExoPlayer.


# 3. Integrate MediaDrmCallback
**Step 1: Implements MediaDrmCallback**
- Create WidevineMediaDrmCallback implements MediaDrmCallback
**Example:**
    ```
  package com.example.exoplayer;

  import android.annotation.TargetApi;
  import android.net.Uri;
  import android.text.TextUtils;
  import android.util.Base64;
  import android.util.Log;
  
  import com.google.android.exoplayer2.C;
  import com.google.android.exoplayer2.drm.ExoMediaDrm.KeyRequest;
  import com.google.android.exoplayer2.drm.ExoMediaDrm.ProvisionRequest;
  import com.google.android.exoplayer2.drm.MediaDrmCallback;
  import com.google.android.exoplayer2.upstream.DataSourceInputStream;
  import com.google.android.exoplayer2.upstream.DataSpec;
  import com.google.android.exoplayer2.upstream.HttpDataSource;
  import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
  import com.google.android.exoplayer2.util.Assertions;
  import com.google.android.exoplayer2.util.Util;
  
  import org.json.JSONException;
import org.json.JSONObject;
  
  import java.io.IOException;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.UUID;

  /**
   * A {@link MediaDrmCallback} that makes requests using {@link HttpDataSource} instances.
   */
  @TargetApi(18)
public final class WidevineMediaDrmCallback implements MediaDrmCallback {
  
    private final HttpDataSource.Factory dataSourceFactory;
    private final String defaultLicenseUrl;
    private final boolean forceDefaultLicenseUrl;
    private final Map<String, String> keyRequestProperties;
  
    /**
     * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL.
     * @param dataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
     */
    public WidevineMediaDrmCallback(String defaultLicenseUrl, HttpDataSource.Factory dataSourceFactory) {
      this(defaultLicenseUrl, false, dataSourceFactory);
    }
  
    /**
     * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
     *     their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is
     *     set to true.
     * @param forceDefaultLicenseUrl Whether to use {@code defaultLicenseUrl} for key requests that
     *     include their own license URL.
     * @param dataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
     */
    public WidevineMediaDrmCallback(String defaultLicenseUrl, boolean forceDefaultLicenseUrl,
                                  HttpDataSource.Factory dataSourceFactory) {
      this.dataSourceFactory = dataSourceFactory;
      this.defaultLicenseUrl = defaultLicenseUrl;
      this.forceDefaultLicenseUrl = forceDefaultLicenseUrl;
      this.keyRequestProperties = new HashMap<>();
    }
  
    /**
     * Sets a header for key requests made by the callback.
     *
     * @param name The name of the header field.
     * @param value The value of the field.
     */
    public void setKeyRequestProperty(String name, String value) {
    Assertions.checkNotNull(name);
      Assertions.checkNotNull(value);
      synchronized (keyRequestProperties) {
        keyRequestProperties.put(name, value);
      }
    }
  
    /**
     * Clears a header for key requests made by the callback.
     *
     * @param name The name of the header field.
     */
  public void clearKeyRequestProperty(String name) {
      Assertions.checkNotNull(name);
      synchronized (keyRequestProperties) {
        keyRequestProperties.remove(name);
      }
    }
  
    /**
     * Clears all headers for key requests made by the callback.
   */
    public void clearAllKeyRequestProperties() {
      synchronized (keyRequestProperties) {
        keyRequestProperties.clear();
      }
    }
  
  @Override
    public byte[] executeProvisionRequest(UUID uuid, ProvisionRequest request) throws IOException {
      String url =
          request.getDefaultUrl() + "&signedRequest=" + Util.fromUtf8Bytes(request.getData());
      return executePost(dataSourceFactory, url, Util.EMPTY_BYTE_ARRAY, null);
    }
  
    @Override
    public byte[] executeKeyRequest(UUID uuid, KeyRequest request) throws Exception {
      String url = request.getLicenseServerUrl();
      if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
        url = defaultLicenseUrl;
      }
      Map<String, String> requestProperties = new HashMap<>();
      // Add standard request properties for supported schemes.
      String contentType = "application/octet-stream";
      requestProperties.put("Content-Type", contentType);
    JSONObject customData = new JSONObject();
      requestProperties.put("custom-data", getCustomData());
  
      // Add additional request properties.
      synchronized (keyRequestProperties) {
        requestProperties.putAll(keyRequestProperties);
      }
      byte[] bytes = executePost(dataSourceFactory, url, request.getData(), requestProperties);
      try {
      JSONObject jsonObject = new JSONObject(new String(bytes));
        return Base64.decode(jsonObject.getString("license"), Base64.DEFAULT);
      } catch (JSONException e) {
        Log.e("DRM Callback", "Error while parsing DRMtoday response: " + new String(bytes), e);
        throw new RuntimeException("Error while parsing response", e);
      }
    }
  
    private static byte[] executePost(HttpDataSource.Factory dataSourceFactory, String url,
        byte[] data, Map<String, String> requestProperties) throws IOException {
      HttpDataSource dataSource = dataSourceFactory.createDataSource();
      if (requestProperties != null) {
        for (Map.Entry<String, String> requestProperty : requestProperties.entrySet()) {
          dataSource.setRequestProperty(requestProperty.getKey(), requestProperty.getValue());
        }
      }
  
      while (true) {
        DataSpec dataSpec =
            new DataSpec(
                Uri.parse(url),
                data,
                /* absoluteStreamPosition= */ 0,
                /* position= */ 0,
                /* length= */ C.LENGTH_UNSET,
                /* key= */ null,
                DataSpec.FLAG_ALLOW_GZIP);
        DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
        try {
          return Util.toByteArray(inputStream);
        } catch (InvalidResponseCodeException e) {
          throw e;
        } finally {
          Util.closeQuietly(inputStream);
        }
      }
    }
    private String getCustomData() throws Exception {
      JSONObject customData = new JSONObject();
      customData.put("userId", "1-6849382");
      customData.put("sessionId", "exoplayer_sessionId_123456");
      customData.put("merchantId", "d5321abd-6676-4bc1-a39e-6bb763029e54");
      customData.put("appId", "3930f331-e337-42b7-9619-00a0c12c16cb");
      String customHeader = Base64.encodeToString(customData.toString().getBytes(), Base64.NO_WRAP);
      return customHeader;
    }
  }
  
    ```
  |Props|Description|Type|Example|
  |---|---|---|---|
  |defaultLicenseUrl|Url of the license server if protected.|String|"https://proxy.uat.widevine.com/proxy?video_id=GTS_HW_SECURE_DECODE&provider=widevine_test"|
  |dataSourceFactory|A factory from which to obtain {@link HttpDataSource} instances.|HttpDataSource|HttpDataSource.Factory licenseDataSourceFactory = getApplication().buildHttpDataSourceFactory();|
  |setKeyRequestProperty(String name, String value)|Sets a header for key requests made by the callback.|Function| name: The name of the header field, value: The value of the field.|
  |clearKeyRequestProperty(String name)|Clears a header for key requests made by the callback.|Function|name: The name of the header field.|
  |clearAllKeyRequestProperties|Clears all headers for key requests made by the callback.|Function||

**Step 2: Create PlayerActivity using WidevineMediaDrmCallback**
- In PlayerActivity create DrmSessionManager**
    - **1. createMediaDrmCallback**:
        - **Example:**
            ```
                private WidevineMediaDrmCallback createMediaDrmCallback(
                      String licenseUrl, String[] keyRequestPropertiesArray) {
                    HttpDataSource.Factory licenseDataSourceFactory =
                        ((DemoApplication) getApplication()).buildHttpDataSourceFactory();
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
            ```
    - **2. createDrmSessionManager**:
        - **Example:**
            ```
                private MediaSource createLeafMediaSource() {
                    int errorStringId = R.string.error_drm_unknown;
                    DrmSessionManager<ExoMediaCrypto> drmSessionManager = null;

                    MediaDrmCallback mediaDrmCallback =
                            createMediaDrmCallback(drmLicenseUrl, null);
                    drmSessionManager =
                            new DefaultDrmSessionManager.Builder()
                                    .setUuidAndExoMediaDrmProvider(drmScheme, FrameworkMediaDrm.DEFAULT_PROVIDER)
                                    .setMultiSession(false)
                                    .build(mediaDrmCallback);
            ```


                    if (drmSessionManager == null) {
                        showToast(errorStringId);
                        finish();
                        return null;
                    }
    
                    return createLeafMediaSource(uri, "", drmSessionManager);
                }
            ```
- **Step 3: Integrate drmSessionManager to MediaSource**
    - **Example:**
        ```
            private MediaSource createLeafMediaSource(
              Uri uri, String extension, DrmSessionManager<?> drmSessionManager) {
                @ContentType int type = Util.inferContentType(uri, extension);
                switch (type) {
                  case C.TYPE_DASH:
                    return new DashMediaSource.Factory(dataSourceFactory)
                        .setDrmSessionManager(drmSessionManager)
                        .createMediaSource(uri);
                  case C.TYPE_SS:
                    return new SsMediaSource.Factory(dataSourceFactory)
                        .setDrmSessionManager(drmSessionManager)
                        .createMediaSource(uri);
                  case C.TYPE_HLS:
                    return new HlsMediaSource.Factory(dataSourceFactory)
                        .setDrmSessionManager(drmSessionManager)
                        .createMediaSource(uri);
                  case C.TYPE_OTHER:
                    return new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .setDrmSessionManager(drmSessionManager)
                        .createMediaSource(uri);
                  default:
                    throw new IllegalStateException("Unsupported type: " + type);
                }
            }
        ```
- **Step 4. Init Player with MediaSource**
    - **Example:**
        ```
        private void initializePlayer() {
            if (player == null) {
                mediaSource = createLeafMediaSource();

                if (mediaSource == null) {
                    return;
                }
                DefaultTrackSelector trackSelector = new DefaultTrackSelector(getApplicationContext(), new AdaptiveTrackSelection.Factory());

                RenderersFactory renderersFactory =
                        ((ExoplayerApplication) getApplication()).buildRenderersFactory(false);
                player =
                        new SimpleExoPlayer.Builder(/* context= */ this, renderersFactory)
                                .setTrackSelector(trackSelector)
                                .build();
                player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
                player.setPlayWhenReady(true);
                playerView.setPlayer(player);
                playerView.setPlaybackPreparer(this);
            }

            boolean haveStartPosition = startWindow != C.INDEX_UNSET;
            if (haveStartPosition) {
                player.seekTo(startWindow, startPosition);
            }
            player.prepare(mediaSource, !haveStartPosition, false);
        }
        ```
# 4. Thông tin tích hợp
# 5. Demo: **```[Demo source code](https://link/)```**