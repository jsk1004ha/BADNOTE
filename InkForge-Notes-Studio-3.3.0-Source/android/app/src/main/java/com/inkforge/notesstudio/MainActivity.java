package com.inkforge.notesstudio;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 4172;
    private static final int AUDIO_PERMISSION_REQUEST = 4173;

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
                detail.put("buttonState", event.getButtonState());
                detail.put(
                        "primaryButton",
                        (event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                );
                detail.put(
                        "secondaryButton",
                        (event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                );
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
        private volatile boolean warmupStarted;

        NativeInkBridge(WebView target) {
            this.target = target;
        }

        @JavascriptInterface
        public String capabilities() {
            JSONObject result = new JSONObject();
            try {
                result.put("native", true);
                result.put("version", "3.3.2");
                result.put("digitalInk", true);
                result.put("koreanImageOcr", true);
                result.put("stylusMotionEvent", true);
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
            executor.execute(() -> {
                try {
                    JSONObject payload = new JSONObject(json);
                    String dataUrl = payload.optString("dataUrl", "");
                    int comma = dataUrl.indexOf(',');
                    String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                    byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) {
                        throw new IllegalArgumentException("이미지 데이터를 읽지 못했습니다.");
                    }
                    InputImage image = InputImage.fromBitmap(bitmap, 0);
                    koreanImageRecognizer.process(image)
                            .addOnSuccessListener(text -> {
                                JSONObject output = new JSONObject();
                                try {
                                    output.put("text", text.getText());
                                    JSONArray blocks = new JSONArray();
                                    for (Text.TextBlock block : text.getTextBlocks()) {
                                        JSONObject item = new JSONObject();
                                        item.put("text", block.getText());
                                        if (block.getBoundingBox() != null) {
                                            JSONObject bounds = new JSONObject();
                                            bounds.put("left", block.getBoundingBox().left);
                                            bounds.put("top", block.getBoundingBox().top);
                                            bounds.put("right", block.getBoundingBox().right);
                                            bounds.put("bottom", block.getBoundingBox().bottom);
                                            item.put("bounds", bounds);
                                        }
                                        blocks.put(item);
                                    }
                                    output.put("blocks", blocks);
                                } catch (JSONException ignored) {
                                }
                                bitmap.recycle();
                                resolve(requestId, output);
                            })
                            .addOnFailureListener(error -> {
                                bitmap.recycle();
                                reject(requestId, error);
                            });
                } catch (Exception error) {
                    reject(requestId, error);
                }
            });
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
            RecognitionContext context = RecognitionContext.builder()
                    .setWritingArea(new WritingArea(
                            Math.max(1, width),
                            Math.max(1, height)
                    ))
                    .setPreContext(preContext == null ? "" : preContext)
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
            executor.shutdownNow();
        }
    }
}
