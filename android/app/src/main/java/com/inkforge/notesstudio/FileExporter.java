package com.inkforge.notesstudio;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Streams WebView exports to a private temporary file, then a user-chosen document. */
final class FileExporter {
    static final int REQUEST_CODE = 4174;
    interface Callback {
        void complete(String requestId, boolean saved, boolean cancelled, String error);
    }

    private final Activity activity;
    private final Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private File staging;
    private FileOutputStream stream;
    private String token;
    private String filename;
    private String mime;
    private String pendingRequest;
    private boolean copying;

    FileExporter(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    synchronized String begin(String name, String type) {
        if (token != null) return "";
        try {
            staging = File.createTempFile("badnote-export-", ".tmp", activity.getCacheDir());
            stream = new FileOutputStream(staging);
            token = UUID.randomUUID().toString();
            filename = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            mime = name.endsWith(".ifnote") ? "application/octet-stream" : type;
            return token;
        } catch (Exception error) {
            cleanup();
            return "";
        }
    }

    synchronized boolean append(String id, String base64) {
        if (token == null || !token.equals(id) || stream == null || base64.length() > 400000) return false;
        try {
            stream.write(Base64.decode(base64, Base64.NO_WRAP));
            return true;
        } catch (Exception error) {
            cleanup();
            return false;
        }
    }

    synchronized void finish(String requestId, String id) {
        if (token == null || !token.equals(id) || stream == null) {
            callback.complete(requestId, false, false, "내보낼 임시 파일이 없습니다.");
            return;
        }
        try {
            stream.close();
            stream = null;
            pendingRequest = requestId;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, filename);
            activity.startActivityForResult(intent, REQUEST_CODE);
        } catch (Exception error) {
            cleanup();
            callback.complete(requestId, false, false, error.getMessage());
        }
    }

    synchronized void onResult(int resultCode, Intent data) {
        if (pendingRequest == null || copying) return;
        String requestId = pendingRequest;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null) {
            cleanup();
            callback.complete(requestId, false, true, null);
            return;
        }
        copying = true;
        File source = staging;
        executor.execute(() -> {
            String failure = null;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("저장 위치를 열 수 없습니다.");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            } catch (Exception error) {
                failure = error.getMessage();
                if (failure == null) failure = "파일을 저장하지 못했습니다.";
                try { DocumentsContract.deleteDocument(activity.getContentResolver(), uri); } catch (Exception ignored) { }
            }
            synchronized (FileExporter.this) { cleanup(); }
            callback.complete(requestId, failure == null, false, failure);
        });
    }

    synchronized void cancel(String id) {
        if (token != null && token.equals(id) && pendingRequest == null) cleanup();
    }

    private void cleanup() {
        try { if (stream != null) stream.close(); } catch (Exception ignored) { }
        if (staging != null) staging.delete();
        stream = null;
        staging = null;
        token = null;
        pendingRequest = null;
        copying = false;
    }

    synchronized void close() {
        if (!copying) cleanup();
        executor.shutdown();
    }
}
