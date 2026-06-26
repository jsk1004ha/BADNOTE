package com.inkforge.notesstudio;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext;
import com.google.mlkit.vision.digitalink.recognition.WritingArea;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.gms.tasks.Tasks;
import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 4172;
    private static final int AUDIO_PERMISSION_REQUEST = 4173;
    private static final String APP_VERSION = "3.3.17";
    private static final String RELEASES_API_URL = "https://api.github.com/repos/jsk1004ha/BADNOTE/releases/latest";
    private static final String RELEASES_PAGE_URL = "https://github.com/jsk1004ha/BADNOTE/releases";

    private InkWebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private NativeInkBridge nativeInkBridge;
    private PermissionRequest pendingAudioPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xfff7f7f8);
            getWindow().setNavigationBarColor(0xfff7f7f8);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView = new InkWebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(webView);
        setContentView(root);

        nativeInkBridge = new NativeInkBridge(webView);
        configureWebView(webView);
        webView.addJavascriptInterface(nativeInkBridge, "InkForgeNative");
        webView.loadUrl("https://appassets.androidplatform.net/assets/public/index.html");
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        }

        WebView.setWebContentsDebuggingEnabled(false);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        view.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (nativeInkBridge != null) nativeInkBridge.warmUpCoreModels();
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception error) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*");
                }
                intent.putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE
                );
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileChooserCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsAudio = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                    }
                    if (!wantsAudio) {
                        request.deny();
                        return;
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingAudioPermission = request;
                        requestPermissions(
                                new String[]{Manifest.permission.RECORD_AUDIO},
                                AUDIO_PERMISSION_REQUEST
                        );
                    }
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST || pendingAudioPermission == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAudioPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingAudioPermission.deny();
        }
        pendingAudioPermission = null;
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "window.__inkforgeNativeBack&&window.__inkforgeNativeBack()",
                value -> {
                    if (!"true".equals(value)) MainActivity.super.onBackPressed();
                }
        );
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (webView != null && device != null &&
                (device.getSources() & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) {
            JSONObject detail = new JSONObject();
            try {
                detail.put("keyCode", event.getKeyCode());
                detail.put("action", event.getAction());
                detail.put("repeatCount", event.getRepeatCount());
                detail.put("eventTime", event.getEventTime());
                detail.put("device", device.getName());
            } catch (JSONException ignored) {
            }
            dispatchNativeEvent("inkforge:native-stylus-key", detail);
        }
        return super.dispatchKeyEvent(event);
    }

    void dispatchNativeEvent(String name, JSONObject detail) {
        if (webView == null) return;
        String script = "window.dispatchEvent(new CustomEvent(" +
                JSONObject.quote(name) + ", {detail:" + detail.toString() + "}));";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        if (nativeInkBridge != null) nativeInkBridge.close();
        if (webView != null) {
            webView.removeJavascriptInterface("InkForgeNative");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class InkWebView extends WebView {
        private long lastMoveDispatchNanos;
        private boolean stylusPrimaryButtonDown;
        private boolean stylusSecondaryButtonDown;

        InkWebView(Activity context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int index = Math.max(0, Math.min(event.getActionIndex(), event.getPointerCount() - 1));
            int toolType = event.getPointerCount() > 0
                    ? event.getToolType(index)
                    : MotionEvent.TOOL_TYPE_UNKNOWN;
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                        (event.getActionMasked() == MotionEvent.ACTION_DOWN ||
                                event.getActionMasked() == MotionEvent.ACTION_MOVE)) {
                    requestUnbufferedDispatch(event);
                }
                dispatchStylus(event, false);
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            dispatchStylus(event, true);
            return super.onHoverEvent(event);
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            if ((event.getSource() & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) {
                dispatchStylus(event, true);
            }
            return super.onGenericMotionEvent(event);
        }

        private void dispatchStylus(MotionEvent event, boolean hover) {
            if (event.getPointerCount() <= 0) return;
            long now = System.nanoTime();
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) &&
                    now - lastMoveDispatchNanos < 8_000_000L) return;
            lastMoveDispatchNanos = now;
            int index = Math.max(0, Math.min(event.getActionIndex(), event.getPointerCount() - 1));
            int buttonState = event.getButtonState();
            boolean primaryNow = (buttonState & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
            boolean secondaryNow = (buttonState & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0;
            if (action == MotionEvent.ACTION_BUTTON_RELEASE ||
                    action == MotionEvent.ACTION_CANCEL ||
                    action == MotionEvent.ACTION_UP) {
                stylusPrimaryButtonDown = primaryNow;
                stylusSecondaryButtonDown = secondaryNow;
            } else {
                if (primaryNow) stylusPrimaryButtonDown = true;
                if (secondaryNow) stylusSecondaryButtonDown = true;
            }
            boolean primaryButton = primaryNow || stylusPrimaryButtonDown;
            boolean secondaryButton = secondaryNow || stylusSecondaryButtonDown;
            int latchedButtonState = buttonState;
            if (primaryButton) latchedButtonState |= MotionEvent.BUTTON_STYLUS_PRIMARY;
            if (secondaryButton) latchedButtonState |= MotionEvent.BUTTON_STYLUS_SECONDARY;
            JSONObject detail = new JSONObject();
            try {
                detail.put("action", action);
                detail.put("hover", hover);
                detail.put("toolType", event.getToolType(index));
                detail.put("pointerId", event.getPointerId(index));
                detail.put("source", event.getSource());
                detail.put("stylus", event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS);
                detail.put("eraser", event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER);
                detail.put("x", event.getX(index));
                detail.put("y", event.getY(index));
                detail.put("pressure", event.getPressure(index));
                detail.put("tilt", event.getAxisValue(MotionEvent.AXIS_TILT, index));
                detail.put("orientation", event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index));
                detail.put("distance", event.getAxisValue(MotionEvent.AXIS_DISTANCE, index));
                detail.put("buttonState", latchedButtonState);
                detail.put("rawButtonState", buttonState);
                detail.put(
                        "primaryButton",
                        primaryButton
                );
                detail.put(
                        "secondaryButton",
                        secondaryButton
                );
                detail.put("barrelButton", primaryButton || secondaryButton);
                detail.put("eventTime", event.getEventTime());
                detail.put("historySize", event.getHistorySize());
                InputDevice device = event.getDevice();
                if (device != null) {
                    detail.put("device", device.getName());
                    detail.put("vendorId", device.getVendorId());
                    detail.put("productId", device.getProductId());
                }
            } catch (JSONException ignored) {
            }
            dispatchNativeEvent("inkforge:native-stylus", detail);
        }
    }

    private interface FailureCallback {
        void onFailure(Exception error);
    }

    public final class NativeInkBridge {
        private final WebView target;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final Map<String, DigitalInkRecognizer> recognizers = new ConcurrentHashMap<>();
        private final Map<String, DigitalInkRecognitionModel> models = new ConcurrentHashMap<>();
        private final RemoteModelManager modelManager = RemoteModelManager.getInstance();
        private final TextRecognizer koreanImageRecognizer = TextRecognition.getClient(
                new KoreanTextRecognizerOptions.Builder().build()
        );
        private final TextRecognizer latinImageRecognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
        );
        private static final String TESSERACT_LANGUAGES = "kor+eng";
        private final Object tesseractLock = new Object();
        private TessBaseAPI tesseractApi;
        private volatile boolean warmupStarted;
        private volatile JSONObject latestUpdate;
        private volatile File downloadedUpdateApk;

        NativeInkBridge(WebView target) {
            this.target = target;
        }

        @JavascriptInterface
        public String capabilities() {
            JSONObject result = new JSONObject();
            try {
                result.put("native", true);
                result.put("version", APP_VERSION);
                result.put("digitalInk", true);
                result.put("koreanImageOcr", true);
                result.put("koreanEnglishImageOcr", true);
                result.put("tesseractImageOcr", true);
                result.put("imageOcrEngine", "mlkit-korean+latin");
                result.put("tesseractFallbackDefault", false);
                result.put("stylusMotionEvent", true);
                result.put("appUpdate", true);
                result.put("releaseFeed", RELEASES_PAGE_URL);
                result.put("minSdk", 23);
                result.put("android", Build.VERSION.RELEASE);
                result.put("sdk", Build.VERSION.SDK_INT);
                result.put("manufacturer", Build.MANUFACTURER);
                result.put("model", Build.MODEL);
            } catch (JSONException ignored) {
            }
            return result.toString();
        }

        @JavascriptInterface
        public void checkForUpdate() {
            executor.execute(() -> {
                dispatchUpdateStatus(
                        "checking",
                        jsonObject("currentVersion", APP_VERSION)
                );
                try {
                    JSONObject update = loadLatestUpdate();
                    if (update == null) {
                        dispatchUpdateStatus(
                                "current",
                                jsonObject("currentVersion", APP_VERSION)
                        );
                        return;
                    }
                    latestUpdate = update;
                    downloadedUpdateApk = null;
                    dispatchUpdateStatus("available", update);
                } catch (Exception error) {
                    dispatchUpdateStatus(
                            "error",
                            jsonObject("message", errorMessage(error))
                    );
                }
            });
        }

        @JavascriptInterface
        public void downloadUpdate() {
            executor.execute(() -> {
                try {
                    JSONObject update = latestUpdate;
                    if (update == null) {
                        update = loadLatestUpdate();
                        if (update == null) {
                            dispatchUpdateStatus(
                                    "current",
                                    jsonObject("currentVersion", APP_VERSION)
                            );
                            return;
                        }
                        latestUpdate = update;
                    }
                    File output = downloadUpdateApk(update);
                    downloadedUpdateApk = output;
                    dispatchUpdateStatus(
                            "downloaded",
                            withUpdateFields(update, "fileSize", output.length())
                    );
                } catch (Exception error) {
                    dispatchUpdateStatus(
                            "error",
                            jsonObject("message", errorMessage(error))
                    );
                }
            });
        }

        @JavascriptInterface
        public void installDownloadedUpdate() {
            mainHandler.post(() -> {
                try {
                    installDownloadedApk();
                } catch (Exception error) {
                    dispatchUpdateStatus(
                            "error",
                            jsonObject("message", errorMessage(error))
                    );
                }
            });
        }

        @JavascriptInterface
        public void openUpdateInstallSettings() {
            mainHandler.post(this::openUnknownSourcesSettings);
        }

        @JavascriptInterface
        public void recognizeInk(String requestId, String json) {
            executor.execute(() -> {
                try {
                    JSONObject payload = new JSONObject(json);
                    String languageTag = payload.optString("languageTag", "ko");
                    Ink ink = inkFromJson(payload.optJSONArray("strokes"));
                    float width = (float) payload.optDouble("width", 1000.0);
                    float height = (float) payload.optDouble("height", 100.0);
                    String preContext = payload.optString("preContext", "");
                    int maxCandidates = Math.max(
                            1,
                            Math.min(20, payload.optInt("maxCandidates", 8))
                    );
                    ensureModel(
                            languageTag,
                            () -> recognizeWithModel(
                                    requestId,
                                    languageTag,
                                    ink,
                                    width,
                                    height,
                                    preContext,
                                    maxCandidates
                            ),
                            error -> reject(requestId, error)
                    );
                } catch (Exception error) {
                    reject(requestId, error);
                }
            });
        }

        @JavascriptInterface
        public void downloadInkModel(String requestId, String languageTag) {
            executor.execute(() -> ensureModel(
                    languageTag,
                    () -> resolve(
                            requestId,
                            jsonObject("downloaded", true, "languageTag", languageTag)
                    ),
                    error -> reject(requestId, error)
            ));
        }

        @JavascriptInterface
        public void recognizeKoreanImage(String requestId, String json) {
            recognizeImageText(requestId, json);
        }

        @JavascriptInterface
        public void recognizeImageText(String requestId, String json) {
            executor.execute(() -> {
                Bitmap bitmap = null;
                try {
                    JSONObject payload = new JSONObject(json);
                    bitmap = decodeImageBitmap(
                            payload.optString("dataUrl", ""),
                            payload.optInt("maxEdge", 1800)
                    );
                    InputImage image = InputImage.fromBitmap(bitmap, 0);
                    JSONObject output = recognizeImageTextPayload(
                            image,
                            bitmap,
                            payload.optString("mode", "balanced"),
                            payload.optBoolean("allowTesseract", false)
                    );
                    resolve(requestId, output);
                } catch (Exception error) {
                    reject(requestId, error);
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
            });
        }

        private JSONObject loadLatestUpdate() throws IOException, JSONException {
            JSONObject release = fetchJson(RELEASES_API_URL);
            String version = normalizeReleaseVersion(release.optString("tag_name", ""));
            if (version.isEmpty() || compareVersions(version, APP_VERSION) <= 0) {
                return null;
            }
            JSONObject asset = selectReleaseAsset(release, version);
            if (asset == null) {
                throw new IOException("현재 설치 유형에 맞는 APK 자산을 찾지 못했습니다.");
            }
            return jsonObject(
                    "currentVersion", APP_VERSION,
                    "version", version,
                    "tagName", release.optString("tag_name", "v" + version),
                    "releaseName", release.optString("name", "bad note " + version),
                    "body", release.optString("body", ""),
                    "htmlUrl", release.optString("html_url", RELEASES_PAGE_URL),
                    "assetName", asset.optString("name", ""),
                    "assetUrl", asset.optString("browser_download_url", ""),
                    "assetSize", asset.optLong("size", 0L),
                    "packageName", getPackageName(),
                    "variant", updateVariantLabel()
            );
        }

        private JSONObject fetchJson(String url) throws IOException, JSONException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(16000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "bad-note-android/" + APP_VERSION);
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readStreamText(stream);
            connection.disconnect();
            if (code < 200 || code >= 300) {
                throw new IOException("GitHub 릴리즈 확인 실패: HTTP " + code);
            }
            return new JSONObject(body);
        }

        @Nullable
        private JSONObject selectReleaseAsset(JSONObject release, String version) {
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) return null;
            String suffix = updateAssetSuffix();
            JSONObject fallback = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (!name.toLowerCase().endsWith(".apk")) continue;
                if (name.contains(version) && name.endsWith(suffix)) return asset;
                if (fallback == null && name.endsWith(suffix)) fallback = asset;
            }
            return fallback;
        }

        private File downloadUpdateApk(JSONObject update) throws IOException, JSONException {
            String url = update.optString("assetUrl", "");
            if (url.isEmpty()) throw new IOException("다운로드 URL이 비어 있습니다.");
            File directory = updateDownloadDirectory();
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("업데이트 다운로드 폴더를 만들지 못했습니다.");
            }
            cleanOldUpdateApks(directory);
            String assetName = sanitizeAssetName(update.optString("assetName", "bad-note-update.apk"));
            File output = new File(directory, assetName);

            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "bad-note-android/" + APP_VERSION);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readStreamText(connection.getErrorStream());
                connection.disconnect();
                throw new IOException("APK 다운로드 실패: HTTP " + code + (error.isEmpty() ? "" : " " + error));
            }
            long total = Math.max(connection.getContentLengthLong(), update.optLong("assetSize", 0L));
            long downloaded = 0L;
            long lastDispatchAt = 0L;
            byte[] buffer = new byte[128 * 1024];
            try (InputStream input = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(output)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastDispatchAt > 220L) {
                        lastDispatchAt = now;
                        dispatchDownloadProgress(update, downloaded, total);
                    }
                }
            } finally {
                connection.disconnect();
            }
            if (output.length() <= 0L) {
                throw new IOException("다운로드한 APK가 비어 있습니다.");
            }
            dispatchDownloadProgress(update, output.length(), Math.max(total, output.length()));
            return output;
        }

        private void installDownloadedApk() throws JSONException {
            File file = downloadedUpdateApk;
            if (file == null || !file.exists() || file.length() <= 0L) {
                dispatchUpdateStatus(
                        "error",
                        jsonObject("message", "먼저 업데이트 APK를 다운로드하세요.")
                );
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !getPackageManager().canRequestPackageInstalls()) {
                dispatchUpdateStatus(
                        "permission-required",
                        latestUpdate == null
                                ? jsonObject("message", "알 수 없는 앱 설치 권한이 필요합니다.")
                                : withUpdateFields(latestUpdate, "message", "알 수 없는 앱 설치 권한이 필요합니다.")
                );
                openUnknownSourcesSettings();
                return;
            }
            Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    file
            );
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            dispatchUpdateStatus(
                    "installing",
                    latestUpdate == null
                            ? jsonObject("fileSize", file.length())
                            : withUpdateFields(latestUpdate, "fileSize", file.length())
            );
            startActivity(intent);
        }

        private void openUnknownSourcesSettings() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }

        private File updateDownloadDirectory() {
            File external = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File base = external != null ? external : getCacheDir();
            return new File(base, "updates");
        }

        private void cleanOldUpdateApks(File directory) {
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".apk")) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }

        private void dispatchDownloadProgress(JSONObject update, long downloaded, long total) throws JSONException {
            int progress = total > 0L
                    ? Math.max(0, Math.min(100, Math.round(downloaded * 100f / total)))
                    : 0;
            dispatchUpdateStatus(
                    "downloading",
                    withUpdateFields(
                            update,
                            "bytesDownloaded", downloaded,
                            "totalBytes", total,
                            "progress", progress
                    )
            );
        }

        private JSONObject withUpdateFields(JSONObject update, Object... fields) throws JSONException {
            JSONObject output = new JSONObject(update == null ? "{}" : update.toString());
            for (int i = 0; i + 1 < fields.length; i += 2) {
                output.put(String.valueOf(fields[i]), fields[i + 1]);
            }
            return output;
        }

        private void dispatchUpdateStatus(String status, JSONObject payload) {
            JSONObject detail;
            try {
                detail = new JSONObject(payload == null ? "{}" : payload.toString());
                detail.put("status", status);
                detail.put("currentVersion", APP_VERSION);
                detail.put("packageName", getPackageName());
                detail.put("variant", updateVariantLabel());
            } catch (JSONException error) {
                detail = jsonObject(
                        "status", status,
                        "currentVersion", APP_VERSION,
                        "packageName", getPackageName(),
                        "variant", updateVariantLabel()
                );
            }
            dispatchNativeEvent("inkforge:native-update", detail);
        }

        private String updateAssetSuffix() {
            return "com.inkforge.note5".equals(getPackageName())
                    ? "SideBySide.apk"
                    : "Update.apk";
        }

        private String updateVariantLabel() {
            return "com.inkforge.note5".equals(getPackageName())
                    ? "병행 설치용"
                    : "업데이트용";
        }

        private String sanitizeAssetName(String name) {
            String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
            return cleaned.isEmpty() || !cleaned.endsWith(".apk")
                    ? "bad-note-update.apk"
                    : cleaned;
        }

        private String normalizeReleaseVersion(String tag) {
            String value = tag == null ? "" : tag.trim();
            if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
            int suffix = value.indexOf('-');
            if (suffix >= 0) value = value.substring(0, suffix);
            return value.replaceAll("[^0-9.]", "");
        }

        private int compareVersions(String left, String right) {
            String[] leftParts = normalizeReleaseVersion(left).split("\\.");
            String[] rightParts = normalizeReleaseVersion(right).split("\\.");
            int count = Math.max(leftParts.length, rightParts.length);
            for (int i = 0; i < count; i++) {
                int leftValue = i < leftParts.length && !leftParts[i].isEmpty()
                        ? Integer.parseInt(leftParts[i])
                        : 0;
                int rightValue = i < rightParts.length && !rightParts[i].isEmpty()
                        ? Integer.parseInt(rightParts[i])
                        : 0;
                if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
            }
            return 0;
        }

        private String readStreamText(@Nullable InputStream stream) throws IOException {
            if (stream == null) return "";
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                return output.toString("UTF-8");
            }
        }

        private JSONObject recognizeImageTextPayload(
                InputImage image,
                Bitmap bitmap,
                String mode,
                boolean allowTesseract
        ) throws JSONException {
            JSONArray warnings = new JSONArray();
            Text koreanText = null;
            Text latinText = null;
            try {
                koreanText = Tasks.await(
                        koreanImageRecognizer.process(image),
                        45,
                        TimeUnit.SECONDS
                );
            } catch (Exception error) {
                warnings.put(jsonObject("engine", "mlkit-korean", "error", errorMessage(error)));
            }
            try {
                latinText = Tasks.await(
                        latinImageRecognizer.process(image),
                        45,
                        TimeUnit.SECONDS
                );
            } catch (Exception error) {
                warnings.put(jsonObject("engine", "mlkit-latin", "error", errorMessage(error)));
            }

            String mlText = mergeImageOcrText(koreanText, latinText, "");
            String tesseractText = "";
            boolean qualityMode = "quality".equalsIgnoreCase(mode);
            if (allowTesseract && (qualityMode || ocrTextScore(mlText) < 90)) {
                try {
                    tesseractText = recognizeWithTesseract(bitmap);
                } catch (Exception error) {
                    warnings.put(jsonObject("engine", "tesseract-fast", "error", errorMessage(error)));
                }
            }

            JSONObject output = new JSONObject();
            output.put("text", mergeImageOcrText(koreanText, latinText, tesseractText));
            output.put("blocks", imageOcrBlocks(koreanText, latinText));
            output.put("engine", tesseractText.trim().isEmpty()
                    ? "mlkit-korean+latin"
                    : "mlkit-korean+latin+tesseract-fast");
            output.put("languages", "ko+en");
            output.put("width", bitmap.getWidth());
            output.put("height", bitmap.getHeight());
            output.put("warnings", warnings);
            return output;
        }

        private Bitmap decodeImageBitmap(String dataUrl, int requestedMaxEdge) {
            int comma = dataUrl.indexOf(',');
            String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int maxEdge = Math.max(900, Math.min(2600, requestedMaxEdge));
            int sample = 1;
            int sourceMax = Math.max(bounds.outWidth, bounds.outHeight);
            while (sourceMax / sample > maxEdge * 1.45f) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = sample;
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) throw new IllegalArgumentException("이미지 데이터를 읽지 못했습니다.");
            int decodedMax = Math.max(decoded.getWidth(), decoded.getHeight());
            if (decodedMax <= maxEdge) return decoded;
            float scale = maxEdge / (float) decodedMax;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)),
                    true
            );
            if (scaled != decoded) decoded.recycle();
            return scaled;
        }

        private String recognizeWithTesseract(Bitmap bitmap) throws IOException {
            synchronized (tesseractLock) {
                TessBaseAPI api = ensureTesseract();
                api.setImage(bitmap);
                String text = api.getUTF8Text();
                api.clear();
                return text == null ? "" : text;
            }
        }

        private TessBaseAPI ensureTesseract() throws IOException {
            if (tesseractApi != null) return tesseractApi;
            File dataDir = new File(getFilesDir(), "tesseract");
            File tessDir = new File(dataDir, "tessdata");
            if (!tessDir.exists() && !tessDir.mkdirs()) {
                throw new IOException("Tesseract 데이터 폴더를 만들지 못했습니다.");
            }
            copyAssetIfNeeded("tessdata/kor.traineddata", new File(tessDir, "kor.traineddata"));
            copyAssetIfNeeded("tessdata/eng.traineddata", new File(tessDir, "eng.traineddata"));
            TessBaseAPI api = new TessBaseAPI();
            if (!api.init(dataDir.getAbsolutePath(), TESSERACT_LANGUAGES)) {
                api.end();
                throw new IOException("Tesseract 한/영 모델을 초기화하지 못했습니다.");
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            tesseractApi = api;
            return api;
        }

        private void copyAssetIfNeeded(String assetName, File output) throws IOException {
            if (output.exists() && output.length() > 0) return;
            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("OCR 자산 폴더를 만들지 못했습니다.");
            }
            try (InputStream input = getAssets().open(assetName);
                 FileOutputStream stream = new FileOutputStream(output)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    stream.write(buffer, 0, read);
                }
            }
        }

        private JSONArray imageOcrBlocks(Text koreanText, Text latinText) throws JSONException {
            List<OcrBlock> blocks = new ArrayList<>();
            collectImageOcrBlocks(blocks, koreanText, "mlkit-korean");
            collectImageOcrBlocks(blocks, latinText, "mlkit-latin");
            blocks.sort((a, b) -> {
                int top = Integer.compare(a.top, b.top);
                return top != 0 ? top : Integer.compare(a.left, b.left);
            });
            JSONArray array = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (OcrBlock block : blocks) {
                String key = normalizeOcrKey(block.text);
                if (key.isEmpty() || !seen.add(key)) continue;
                JSONObject item = jsonObject(
                        "text", block.text,
                        "engine", block.engine
                );
                JSONObject bounds = jsonObject(
                        "left", block.left,
                        "top", block.top,
                        "right", block.right,
                        "bottom", block.bottom
                );
                item.put("bounds", bounds);
                array.put(item);
            }
            return array;
        }

        private String mergeImageOcrText(Text koreanText, Text latinText, String tesseractText) {
            List<OcrBlock> blocks = new ArrayList<>();
            collectImageOcrBlocks(blocks, koreanText, "mlkit-korean");
            collectImageOcrBlocks(blocks, latinText, "mlkit-latin");
            blocks.sort((a, b) -> {
                int top = Integer.compare(a.top, b.top);
                return top != 0 ? top : Integer.compare(a.left, b.left);
            });
            List<String> lines = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (OcrBlock block : blocks) addUniqueOcrLine(lines, seen, block.text);
            for (String line : tesseractText.split("\\R")) addUniqueOcrLine(lines, seen, line);
            return String.join("\n", lines).trim();
        }

        private void collectImageOcrBlocks(List<OcrBlock> output, @Nullable Text text, String engine) {
            if (text == null) return;
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String value = line.getText() == null ? "" : line.getText().trim();
                    if (value.isEmpty()) continue;
                    Rect rect = line.getBoundingBox() != null
                            ? line.getBoundingBox()
                            : block.getBoundingBox();
                    output.add(new OcrBlock(value, engine, rect));
                }
            }
        }

        private void addUniqueOcrLine(List<String> lines, Set<String> seen, String raw) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) return;
            String key = normalizeOcrKey(line);
            if (key.isEmpty()) return;
            for (String existing : seen) {
                if (key.length() > 5 && existing.length() > 5 &&
                        (key.contains(existing) || existing.contains(key))) {
                    return;
                }
            }
            if (seen.add(key)) lines.add(line);
        }

        private String normalizeOcrKey(String text) {
            return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                    .toLowerCase()
                    .replaceAll("[\\s\\p{Punct}]+", "");
        }

        private int ocrTextScore(String text) {
            int score = 0;
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (ch >= 0xAC00 && ch <= 0xD7A3) score += 3;
                else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) score += 2;
                else if (Character.isDigit(ch)) score += 2;
                else if (ch == '□' || ch == '�') score -= 8;
            }
            return score;
        }

        private String errorMessage(Exception error) {
            String message = error.getMessage();
            return message == null ? error.getClass().getSimpleName() : message;
        }

        private final class OcrBlock {
            final String text;
            final String engine;
            final int left;
            final int top;
            final int right;
            final int bottom;

            OcrBlock(String text, String engine, @Nullable Rect rect) {
                this.text = text;
                this.engine = engine;
                this.left = rect == null ? 0 : rect.left;
                this.top = rect == null ? 0 : rect.top;
                this.right = rect == null ? 0 : rect.right;
                this.bottom = rect == null ? 0 : rect.bottom;
            }
        }

        void warmUpCoreModels() {
            if (warmupStarted) return;
            warmupStarted = true;
            String[] languages = new String[]{"ko", "en-US", "zxx-Zsym-x-shapes"};
            for (String language : languages) {
                executor.execute(() -> ensureModel(
                        language,
                        () -> dispatchModelStatus(language, "ready", null),
                        error -> dispatchModelStatus(language, "error", error.getMessage())
                ));
            }
        }

        private Ink inkFromJson(@Nullable JSONArray strokeArray) throws JSONException {
            Ink.Builder inkBuilder = Ink.builder();
            if (strokeArray == null || strokeArray.length() == 0) {
                throw new IllegalArgumentException("인식할 획이 없습니다.");
            }
            long fallbackTime = System.currentTimeMillis();
            int pointCount = 0;
            for (int i = 0; i < strokeArray.length(); i++) {
                JSONArray points = strokeArray.optJSONArray(i);
                if (points == null || points.length() == 0) continue;
                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                for (int j = 0; j < points.length(); j++) {
                    JSONObject point = points.optJSONObject(j);
                    if (point == null) continue;
                    float x = (float) point.optDouble("x", 0);
                    float y = (float) point.optDouble("y", 0);
                    long t = point.has("t")
                            ? (long) point.optDouble("t", fallbackTime + j)
                            : fallbackTime + j;
                    strokeBuilder.addPoint(Ink.Point.create(x, y, t));
                    pointCount++;
                }
                inkBuilder.addStroke(strokeBuilder.build());
                fallbackTime += 16;
            }
            if (pointCount == 0) throw new IllegalArgumentException("인식할 점이 없습니다.");
            return inkBuilder.build();
        }

        private void ensureModel(
                String languageTag,
                Runnable success,
                FailureCallback failure
        ) {
            try {
                DigitalInkRecognitionModel model = models.get(languageTag);
                if (model == null) {
                    DigitalInkRecognitionModelIdentifier identifier =
                            DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag);
                    if (identifier == null) {
                        throw new IllegalArgumentException(
                                "지원되지 않는 필기 모델: " + languageTag
                        );
                    }
                    model = DigitalInkRecognitionModel.builder(identifier).build();
                    models.put(languageTag, model);
                }
                final DigitalInkRecognitionModel finalModel = model;
                modelManager.isModelDownloaded(model)
                        .addOnSuccessListener(downloaded -> {
                            if (Boolean.TRUE.equals(downloaded)) {
                                success.run();
                            } else {
                                dispatchModelStatus(languageTag, "downloading", null);
                                modelManager.download(
                                                finalModel,
                                                new DownloadConditions.Builder().build()
                                        )
                                        .addOnSuccessListener(unused -> {
                                            dispatchModelStatus(languageTag, "ready", null);
                                            success.run();
                                        })
                                        .addOnFailureListener(error -> failure.onFailure(error));
                            }
                        })
                        .addOnFailureListener(error -> failure.onFailure(error));
            } catch (Exception error) {
                failure.onFailure(error);
            }
        }

        private DigitalInkRecognizer recognizerFor(String languageTag) {
            DigitalInkRecognizer existing = recognizers.get(languageTag);
            if (existing != null) return existing;
            DigitalInkRecognitionModel model = models.get(languageTag);
            DigitalInkRecognizer created = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
            );
            recognizers.put(languageTag, created);
            return created;
        }

        private void recognizeWithModel(
                String requestId,
                String languageTag,
                Ink ink,
                float width,
                float height,
                String preContext,
                int maxCandidates
        ) {
            DigitalInkRecognizer recognizer = recognizerFor(languageTag);
            String contextText = preContext == null ? "" : preContext;
            if (contextText.length() > 20) {
                contextText = contextText.substring(contextText.length() - 20);
            }
            RecognitionContext context = RecognitionContext.builder()
                    .setWritingArea(new WritingArea(
                            Math.max(1, width),
                            Math.max(1, height)
                    ))
                    .setPreContext(contextText)
                    .build();
            recognizer.recognize(ink, context)
                    .addOnSuccessListener(result -> {
                        JSONObject output = new JSONObject();
                        JSONArray candidates = new JSONArray();
                        int count = Math.min(
                                maxCandidates,
                                result.getCandidates().size()
                        );
                        for (int i = 0; i < count; i++) {
                            JSONObject item = new JSONObject();
                            try {
                                item.put("text", result.getCandidates().get(i).getText());
                                item.put("rank", i);
                            } catch (JSONException ignored) {
                            }
                            candidates.put(item);
                        }
                        try {
                            output.put("languageTag", languageTag);
                            output.put("candidates", candidates);
                            output.put(
                                    "text",
                                    count > 0
                                            ? result.getCandidates().get(0).getText()
                                            : ""
                            );
                        } catch (JSONException ignored) {
                        }
                        resolve(requestId, output);
                    })
                    .addOnFailureListener(error -> reject(requestId, error));
        }

        private JSONObject jsonObject(Object... values) {
            JSONObject object = new JSONObject();
            for (int i = 0; i + 1 < values.length; i += 2) {
                try {
                    object.put(String.valueOf(values[i]), values[i + 1]);
                } catch (JSONException ignored) {
                }
            }
            return object;
        }

        private void dispatchModelStatus(
                String languageTag,
                String status,
                @Nullable String message
        ) {
            JSONObject detail = jsonObject(
                    "languageTag", languageTag,
                    "status", status
            );
            if (message != null) {
                try {
                    detail.put("message", message);
                } catch (JSONException ignored) {
                }
            }
            dispatchNativeEvent("inkforge:native-model-status", detail);
        }

        private void resolve(String requestId, JSONObject payload) {
            callback(requestId, jsonObject("ok", true, "data", payload));
        }

        private void reject(String requestId, Exception error) {
            String message = error.getMessage();
            callback(
                    requestId,
                    jsonObject(
                            "ok", false,
                            "error", message == null
                                    ? error.getClass().getSimpleName()
                                    : message
                    )
            );
        }

        private void callback(String requestId, JSONObject payload) {
            String script = "window.__inkforgeNativeCallbacks&&" +
                    "window.__inkforgeNativeCallbacks.resolve(" +
                    JSONObject.quote(requestId) + "," + payload.toString() + ");";
            mainHandler.post(() -> target.evaluateJavascript(script, null));
        }

        void close() {
            for (DigitalInkRecognizer recognizer : recognizers.values()) {
                recognizer.close();
            }
            koreanImageRecognizer.close();
            latinImageRecognizer.close();
            synchronized (tesseractLock) {
                if (tesseractApi != null) {
                    tesseractApi.end();
                    tesseractApi = null;
                }
            }
            executor.shutdownNow();
        }
    }
}
