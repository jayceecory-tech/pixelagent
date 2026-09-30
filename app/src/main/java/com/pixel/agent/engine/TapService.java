package com.pixel.agent.engine;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 输入引擎: 无障碍 dispatchGesture 实现点击/滑动。
 * 同时跟踪前台包名，供流程判断是否被误触到其它应用。
 */
public class TapService extends AccessibilityService {
    private static final String TAG = "PixelAgent.Tap";
    public static volatile TapService instance;
    /** 当前台包名（无障碍窗口事件） */
    public static volatile String foregroundPackage;

    public static final String PKG_WECHAT = "com.tencent.mm";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "tap service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCs = event.getPackageName();
            String pkg = pkgCs != null ? pkgCs.toString() : null;
            if (pkg != null && pkg.length() > 0) {
                foregroundPackage = pkg;
            }
            Log.d(TAG, "window: " + pkg + " " + event.getClassName());
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isWechatForeground() {
        return PKG_WECHAT.equals(foregroundPackage);
    }

    public boolean tap(int x, int y) {
        return dispatch(x, y, x, y, 60);
    }

    public boolean longPress(int x, int y, long holdMs) {
        return dispatch(x, y, x, y, (int) Math.max(200, holdMs));
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        return dispatch(x1, y1, x2, y2, duration);
    }

    private boolean dispatch(int x1, int y1, int x2, int y2, int duration) {
        if (Build.VERSION.SDK_INT < 24) return false;
        if (instance == null) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(p, 0, duration));
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                done.complete(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                done.complete(false);
            }
        }, null);
        if (!ok) return false;
        try {
            return done.get(duration + 1500L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean back() {
        if (instance == null) return false;
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
