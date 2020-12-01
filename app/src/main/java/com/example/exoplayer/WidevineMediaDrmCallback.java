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
    customData.put("merchantId", "sctv");
    customData.put("appId", "RedTV");
    String customHeader = Base64.encodeToString(customData.toString().getBytes(), Base64.NO_WRAP);
    return customHeader;
  }
}
