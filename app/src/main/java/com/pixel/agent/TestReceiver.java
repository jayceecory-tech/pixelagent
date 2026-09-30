package com.pixel.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.pixel.agent.detector.PixelDetector;
import com.pixel.agent.engine.CaptureService;

/** 测试入口: adb shell am broadcast -a com.pixel.agent.TEST */
public class TestReceiver extends BroadcastReceiver {
    private static final String TAG = "PixelAgent.Test";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "test broadcast received");
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            // 截屏×5 测延迟
            for (int i = 0; i < 5; i++) {
                long ts = System.currentTimeMillis();
                int[][] f = CaptureService.instance != null
                        ? CaptureService.instance.capture() : null;
                Log.i(TAG, "capture#" + i + " " + (System.currentTimeMillis() - ts) + "ms "
                        + (f != null ? f[1][0] + "x" + f[1][1] : "null"));
                if (f == null) return;
            }
            // 检测
            int[][] f = CaptureService.instance.capture();
            if (f == null) { Log.e(TAG, "final capture null"); return; }
            PixelDetector d = new PixelDetector(f[0], f[1][0], f[1][1]);
            long td = System.currentTimeMillis();
            Log.i(TAG, "pageKind=" + d.pageKind());
            PixelDetector.Btn gb = d.findGreenButton();
            Log.i(TAG, "greenBtn=" + (gb == null ? "none" : gb.x + "," + gb.y));
            java.util.List<int[]> avs = d.findAvatars();
            StringBuilder sb = new StringBuilder();
            for (int[] av : avs) sb.append("(").append(av[0]).append(",").append(av[1]).append(") ");
            Log.i(TAG, "avatars=" + avs.size() + " " + sb);
            Log.i(TAG, "inputMode=" + d.dmInputMode());
            Log.i(TAG, "detect time=" + (System.currentTimeMillis() - td) + "ms");
            Log.i(TAG, "TOTAL=" + (System.currentTimeMillis() - t0) + "ms");
        }, "pixelTest").start();
    }
}
