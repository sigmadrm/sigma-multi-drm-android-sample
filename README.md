# ExoPlayer version 2.19.1

## 1. Introduction

::: tip Integration information:

- _License URL:_

  - _Staging:_ https://license-staging.sigmadrm.com/license/verify/widevine
  - _Production:_ https://license.sigmadrm.com/license/verify/widevine

- _Merchant Information:_

![get_customer_info](/dashboard_get_merchant_app_integrate.png)

:::

## 2. Require

::: tip Prerequisite

- _Operating system:_ Android 21+

- Add a repositories in the **[root_directory]/build.gradle** file

  ```java
    repositories{
        google()
        mavenCentral()
        maven
            {
                url "https://maven.sigmadrm.com"
            }
        }
    }
  ```

- Add a dependency in the **app/build.gradle**. The following will add a dependency to the full library:

```java
  implementation 'com.google.android.exoplayer:exoplayer:2.19.1'
  // FIXME: If you don't use feature license encrypt, please comment line below
  // implementation 'com.sigma.packer:2.19.x:1.0.3'
```

- Params that using in this document

| Props       | Type   | Description                                       |
| ----------- | ------ | ------------------------------------------------- |
| MEDIA_URL   | String | The URL to the manifest file                      |
| LICENSE_URL | String | Url of the license server                         |
| MERCHANT_ID | String | Identify of merchant                              |
| APP_ID      | String | Identify of application                           |
| USER_ID     | String | User Identify that provided by merchant system    |
| SESSION_ID  | String | Session Identify that provided by merchant system |

:::

**_Note_**: _"If you use both **Sigma MultiDRM** and **Sigma DRM**, please add dependency as **Sigma DRM**"_

## 3. Integrate MediaDrmCallback

```java
// WidevineMediaDrmCallback.java

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import androidx.annotation.NonNull;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.ExoMediaDrm;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.drm.MediaDrmCallbackException;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;

// FIXME: If you user license encrypt feature then please uncomment 2 lines below
// import com.sigma.packer.RequestInfo;
// import com.sigma.packer.SigmaDrmPacker;

/**
 * A {@link MediaDrmCallback} that makes requests using {@link HttpDataSource} instances.
 */
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

  @NonNull
  @Override
  public byte[] executeProvisionRequest(@NonNull UUID uuid, ExoMediaDrm.ProvisionRequest request) throws MediaDrmCallbackException {
    String url =
        request.getDefaultUrl() + "&signedRequest=" + Util.fromUtf8Bytes(request.getData());
    return executePost(dataSourceFactory, url, Util.EMPTY_BYTE_ARRAY, null);
  }

  @NonNull
  @Override
  public byte[] executeKeyRequest(@NonNull UUID uuid, @NonNull ExoMediaDrm.KeyRequest request) throws MediaDrmCallbackException {
    try {
      String url = request.getLicenseServerUrl();
      if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
        url = defaultLicenseUrl;
      }
      Map<String, String> requestProperties = new HashMap<>();
      // Add standard request properties for supported schemes.
      String contentType = "application/octet-stream";
      requestProperties.put("Content-Type", contentType);
      JSONObject customData = new JSONObject();
      requestProperties.put("custom-data", getCustomData(request));

      // Add additional request properties.
      synchronized (keyRequestProperties) {
        requestProperties.putAll(keyRequestProperties);
      }
      String base64Encoded = Base64.encodeToString(request.getData(), Base64.NO_WRAP);
      Log.e("Data base64Encoded", base64Encoded);
      byte[] bytes = executePost(dataSourceFactory, url, request.getData(), requestProperties);
      JSONObject jsonObject = new JSONObject(new String(bytes));
      String licenseEncrypted = jsonObject.getString("license");
//      String licenseInBase64 = SigmaDrmPacker.extractLicense(licenseEncrypted);
      return Base64.decode(licenseEncrypted, Base64.DEFAULT);
    } catch (Exception e) {
      throw new RuntimeException("Error while parsing response", e);
    }
  }

  private static byte[] executePost(HttpDataSource.Factory dataSourceFactory, String url,
                                    byte[] data, Map<String, String> requestProperties) throws MediaDrmCallbackException {
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
      } catch (Exception e) {
        throw new MediaDrmCallbackException(
            dataSpec,
            Uri.parse(url),
            dataSource.getResponseHeaders(),
            inputStream.bytesRead(),
            e);
      } finally {
        Util.closeQuietly(inputStream);
      }
    }
  }

  private String getCustomData(ExoMediaDrm.KeyRequest keyRequest) throws Exception {
    JSONObject customData = new JSONObject();

    customData.put("userId", USER_ID);
    customData.put("sessionId", SESSION_ID);
    customData.put("merchantId", MERCHANT_ID);
    customData.put("appId", APP_ID);

    // FIXME: If you user license encrypt feature then please uncomment 3 lines below
    // RequestInfo requestInfo = SigmaDrmPacker.requestInfo(keyRequest.getData());
    // customData.put("reqId", requestInfo.requestId);
    // customData.put("deviceInfo", requestInfo.deviceInfo);

    String customHeader = Base64.encodeToString(customData.toString().getBytes(), Base64.NO_WRAP);
    Log.e("Custom Data: ", customHeader);
    return customHeader;
  }
}

```

### 4. Initialize Player

```java
// PlayerActivity.java

  private void initializePlayer() {
  MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MEDIA_URL));
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
    MediaDrmCallback drmCallback = createMediaDrmCallback(LICENSE_URL, null);
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

```

## 5. Sample Code

[Sample source code](https://github.com/sigmadrm/sigma-multi-drm-android-sample/tree/2.19.1)
