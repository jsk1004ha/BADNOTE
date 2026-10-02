package com.inkforge.notesstudio;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.WebView;

import org.json.JSONArray;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs only in the separate .debug app; never touches the user's release notes. */
public final class StylusSmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private WebView web;
    private long downTime;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Field field = MainActivity.class.getDeclaredField("webView");
            field.setAccessible(true);
            web = (WebView) field.get(activity);
            await("window.__inkforge?.ready && window.__inkforgeNativeBridge?.ready");
            SystemClock.sleep(800);
            js("document.querySelector('#nativeUpdateSheet [data-update-action=close]')?.click();" +
                    "window.__smokeTrail=[];window.addEventListener('inkforge:native-stylus',e=>{const d=e.detail;" +
                    "__smokeTrail.push({d,session:__inkforge.state.drawSession?.kind,view:__inkforge.state.view," +
                    "modal:!document.querySelector('#modalBackdrop').hidden,hit:document.elementFromPoint(d.x,d.y)?.outerHTML?.slice(0,150)});});");
            js("document.querySelectorAll('[data-action=close-modal]').forEach(b=>b.click());" +
                    "(()=>{const a=__inkforge,d=a.createDocument('Export smoke test','grid');" +
                    "a.state.settings.language='ko';a.state.settings.sPenGestures=true;a.state.settings.stylusOnly=true;" +
                    "a.state.settings.drawHold=false;a.state.settings.scribbleErase=false;" +
                    "d.pages[0].objects=[500,650].map((y,i)=>({id:'target'+i,type:'stroke',brush:'fountain',width:4,color:'#111827'," +
                    "points:[{x:450,y,p:.5},{x:550,y,p:.5}]}));a.state.documents.push(d);a.openDocument(d.id);a.setTool('pen');a.setZoom(1);})()");
            await("document.querySelector('.page-canvas')!==null");
            motion(MotionEvent.ACTION_HOVER_MOVE, 500, 500, 32);
            await("__inkforge.state.tool==='eraser'");
            check("__inkforge.currentPage().objects.length===2", "hover must not erase");
            motion(MotionEvent.ACTION_DOWN, 500, 500, 32);
            await("!__inkforge.currentPage().objects.some(o=>o.id==='target0')");
            motion(MotionEvent.ACTION_MOVE, 500, 650, 32);
            await("!__inkforge.currentPage().objects.some(o=>o.id==='target1')");
            motion(MotionEvent.ACTION_MOVE, 600, 700, 0);
            await("__inkforge.state.tool==='pen'&&__inkforge.state.drawSession?.kind==='stroke'");
            motion(MotionEvent.ACTION_MOVE, 750, 740, 0);
            motion(MotionEvent.ACTION_UP, 750, 740, 0);
            await("__inkforge.currentPage().objects.length===1&&!__inkforge.state.drawSession");
            check("__inkforge.state.activePointers.size===0", "native pointers must be released");
            result.putString("native_stylus", "PASS: hover, erase down/move, release, resumed ink, cleanup");
            // Leave an isolated fixture for the real system save-picker smoke test.
            js("(()=>{const a=__inkforge,d=a.currentDocument();d.pages.push(a.blankPage('blank'));" +
                    "d.pages[1].objects=[{id:'text',type:'text',x:100,y:200,w:800,h:100,fontSize:36,text:'PDF export test',color:'#234567'}];" +
                    "a.storage.putDocument(d).then(()=>window.__smokeSaved=true);a.renderEditorPages();})()");
            await("window.__smokeSaved===true");
            result.putString("passed", "true");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("passed", "false");
            result.putString("error", error.toString());
            try { result.putString("diagnostics", js("JSON.stringify(window.__smokeTrail)")); } catch (Exception ignored) { }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String js(String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String[] result = new String[1];
        runOnMainSync(() -> web.evaluateJavascript(script, value -> { result[0] = value; latch.countDown(); }));
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("JavaScript timeout");
        return result[0];
    }

    private void await(String expression) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            if ("true".equals(js(expression))) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Condition failed: " + expression);
    }

    private void check(String expression, String message) throws Exception {
        if (!"true".equals(js(expression))) throw new AssertionError(message);
    }

    private void motion(int action, float pageX, float pageY, int buttons) throws Exception {
        JSONArray position = new JSONArray(js("(()=>{const r=document.querySelector('.page-canvas').getBoundingClientRect();" +
                "return [r.left+r.width*" + pageX + "/1000,r.top+r.height*" + pageY + "/1414]})()"));
        float x = (float) position.getDouble(0), y = (float) position.getDouble(1);
        runOnMainSync(() -> {
            int[] location = new int[2]; web.getLocationOnScreen(location);
            float density = activity.getResources().getDisplayMetrics().density;
            MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
            prop.id = 0; prop.toolType = MotionEvent.TOOL_TYPE_STYLUS;
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = x * density + location[0]; coords.y = y * density + location[1];
            coords.pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_HOVER_MOVE ? 0 : .55f;
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                    new MotionEvent.PointerProperties[]{prop}, new MotionEvent.PointerCoords[]{coords},
                    0, buttons, 1, 1, 0, 0, InputDevice.SOURCE_STYLUS, 0);
            if (action == MotionEvent.ACTION_HOVER_MOVE) activity.dispatchGenericMotionEvent(event);
            else activity.dispatchTouchEvent(event);
            event.recycle();
        });
        SystemClock.sleep(40);
    }
}
