/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.drm;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.ExoMediaDrm.KeyRequest;
import com.google.android.exoplayer2.drm.ExoMediaDrm.ProvisionRequest;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.StatsDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A {@link MediaDrmCallback} that makes requests using {@link DataSource} instances.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class HttpMediaDrmCallback implements MediaDrmCallback {

  private static final int MAX_MANUAL_REDIRECTS = 5;

  private final DataSource.Factory dataSourceFactory;
  @Nullable private final String defaultLicenseUrl;
  private final boolean forceDefaultLicenseUrl;
  private final Map<String, String> keyRequestProperties;

  /**
   * Constructs an instance.
   *
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL. May be {@code null} if it's known that all key requests will specify
   *     their own URLs.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     usually be an HTTP-based {@link DataSource}.
   */
  public HttpMediaDrmCallback(
      @Nullable String defaultLicenseUrl, DataSource.Factory dataSourceFactory) {
    this(defaultLicenseUrl, /* forceDefaultLicenseUrl= */ false, dataSourceFactory);
  }

  /**
   * Constructs an instance.
   *
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is set to
   *     true. May be {@code null} if {@code forceDefaultLicenseUrl} is {@code false} and if it's
   *     known that all key requests will specify their own URLs.
   * @param forceDefaultLicenseUrl Whether to force use of {@code defaultLicenseUrl} for key
   *     requests that include their own license URL.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     * usually be an HTTP-based {@link DataSource}.
   */
  public HttpMediaDrmCallback(
      @Nullable String defaultLicenseUrl,
      boolean forceDefaultLicenseUrl,
      DataSource.Factory dataSourceFactory) {
    Assertions.checkArgument(!(forceDefaultLicenseUrl && TextUtils.isEmpty(defaultLicenseUrl)));
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

  /** Clears all headers for key requests made by the callback. */
  public void clearAllKeyRequestProperties() {
    synchronized (keyRequestProperties) {
      keyRequestProperties.clear();
    }
  }

  @Override
  public byte[] executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException {
    String url = request.getDefaultUrl();
    byte[] httpBody = request.getData();

    if (C.WIDEVINE_UUID.equals(uuid)) {
      if (TextUtils.isEmpty(url)) {
        url = "https://www.gstatic.com/widevine/cert/provision";
      } else {
        url += (url.contains("?") ? "&" : "?") + "signed_request=" + Util.fromUtf8Bytes(httpBody);
        httpBody = null;
      }
    }

    sendBroadcastLog("[PROV] Requesting Provisioning... to: " + url);

    Map<String, String> requestProperties = new HashMap<>();
    if (httpBody != null) {
      requestProperties.put("Content-Type", "application/octet-stream");
    }

    return executePost(dataSourceFactory, url, httpBody, requestProperties);
  }

  @Override
  public byte[] executeKeyRequest(UUID uuid, KeyRequest request) throws MediaDrmCallbackException {
    String url = request.getLicenseServerUrl();
    if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
      url = defaultLicenseUrl;
    }
    if (TextUtils.isEmpty(url)) {
      throw new MediaDrmCallbackException(
          new DataSpec.Builder().setUri(Uri.EMPTY).build(),
          Uri.EMPTY,
          /* responseHeaders= */ ImmutableMap.of(),
          /* bytesLoaded= */ 0,
          /* cause= */ new IllegalStateException("No license URL"));
    }
    Map<String, String> requestProperties = new HashMap<>();
    String contentType = C.PLAYREADY_UUID.equals(uuid) ? "text/xml" : (C.CLEARKEY_UUID.equals(uuid) ? "application/json" : "application/octet-stream");
    requestProperties.put("Content-Type", contentType);
    if (C.PLAYREADY_UUID.equals(uuid)) {
      requestProperties.put("SOAPAction", "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense");
    }
    synchronized (keyRequestProperties) {
      requestProperties.putAll(keyRequestProperties);
    }
    
    sendBroadcastLog("[NET] Requesting License...");

    byte[] response;
    try {
        try {
            response = executePost(dataSourceFactory, url, request.getData(), requestProperties);
            sendBroadcastLog("[NET] Response 200 OK");
        } catch (Exception e) {
            sendBroadcastLog("[NET] Connection failed. Injecting Invalid Response to force DRM error...");
            return "{\"status\":\"expired\",\"license\":\"invalid\"}".getBytes(UTF_8);
        }
    } catch (Exception e) {
        String errorMsg;
        if (e instanceof InvalidResponseCodeException) {
            InvalidResponseCodeException httpError = (InvalidResponseCodeException) e;
            errorMsg = "HTTP " + httpError.responseCode;
            if (httpError.responseBody != null && httpError.responseBody.length > 0) {
                try {
                    String body = new String(httpError.responseBody, UTF_8).trim();
                    if (body.length() < 100) errorMsg += ": " + body;
                } catch (Exception ignored) {}
            }
        } else {
            errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("No address associated with hostname")) {
                errorMsg = "No Network Connection";
            }
        }
        android.util.Log.e("DRM_DEBUG", "[ERR] Request Failed: " + errorMsg);
        sendBroadcastLog("[ERR] Request Failed: " + errorMsg);
        throw e;
    }

    if (response != null) {
        String responseString = new String(response, UTF_8).trim();
        if (responseString.startsWith("{")) {
            try {
                JSONObject jsonObject = new JSONObject(responseString);
                if (jsonObject.has("license")) {
                    String licenseBase64 = jsonObject.getString("license");
                    byte[] decodedLicense = Base64.decode(licenseBase64, Base64.DEFAULT);
                    sendBroadcastLog("[NET] Response 200 OK (JSON)");
                    return decodedLicense;
                } else {
                    String msg = jsonObject.optString("message", "Unknown Error");
                    sendBroadcastLog("[ERR] License missing: " + msg);
                }
            } catch (JSONException e) {
                sendBroadcastLog("[ERR] JSON Parsing Error");
            }
        } else {
            sendBroadcastLog("[NET] Response 200 OK");
        }
    }

    return response;
  }

  private static byte[] executePost(
      DataSource.Factory dataSourceFactory,
      String url,
      @Nullable byte[] httpBody,
      Map<String, String> requestProperties)
      throws MediaDrmCallbackException {
    StatsDataSource dataSource = new StatsDataSource(dataSourceFactory.createDataSource());
    int manualRedirectCount = 0;
    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(url)
            .setHttpRequestHeaders(requestProperties)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(httpBody)
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build();
    DataSpec originalDataSpec = dataSpec;
    try {
      while (true) {
        DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
        try {
          return Util.toByteArray(inputStream);
        } catch (InvalidResponseCodeException e) {
          @Nullable String redirectUrl = getRedirectUrl(e, manualRedirectCount);
          if (redirectUrl == null) {
            throw e;
          }
          manualRedirectCount++;
          dataSpec = dataSpec.buildUpon().setUri(redirectUrl).build();
        } finally {
          Util.closeQuietly(inputStream);
        }
      }
    } catch (Exception e) {
      throw new MediaDrmCallbackException(
          originalDataSpec,
          Assertions.checkNotNull(dataSource.getLastOpenedUri()),
          dataSource.getResponseHeaders(),
          dataSource.getBytesRead(),
          /* cause= */ e);
    }
  }

  @Nullable
  private static String getRedirectUrl(
      InvalidResponseCodeException exception, int manualRedirectCount) {
    boolean manuallyRedirect =
        (exception.responseCode == 307 || exception.responseCode == 308)
            && manualRedirectCount < MAX_MANUAL_REDIRECTS;
    if (!manuallyRedirect) {
      return null;
    }
    Map<String, List<String>> headerFields = exception.headerFields;
    if (headerFields != null) {
      @Nullable List<String> locationHeaders = headerFields.get("Location");
      if (locationHeaders != null && !locationHeaders.isEmpty()) {
        return locationHeaders.get(0);
      }
    }
    return null;
  }

  private void sendBroadcastLog(String msg) {
      android.util.Log.d("DRM_DEBUG", msg);
      try {
          @SuppressLint("PrivateApi")
          Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
          Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
          android.content.Context context = (android.content.Context) activityThreadClass.getMethod("getApplication").invoke(activityThread);
          
          if (context != null) {
              android.content.Intent intent = new android.content.Intent("SIGMA_DRM_LOG");
              intent.setPackage(context.getPackageName());
              intent.putExtra("message", msg);
              context.sendBroadcast(intent);
          }
      } catch (Exception ignored) {}
  }
}
