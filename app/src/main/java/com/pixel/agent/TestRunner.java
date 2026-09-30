package com.pixel.agent;

import android.content.Context;
import android.util.Log;

import com.pixel.agent.detector.PixelDetector;
import com.pixel.agent.engine.CaptureService;

/** 前台测试: 由MainActivity按钮触发 */
public class TestRunner implements Runnable {
    private static final String TAG = "PixelAgent.Test";

    @Override
    public void run() {
        Log.i(TAG, "test start");
        if (CaptureService.instance == null) {
            Log.e(TAG, "capture service not ready");
            return;
        }
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            long ts = System.currentTimeMillis();
            int[][] f = CaptureService.instance.capture();
            Log.i(TAG, "capture#" + i + " " + (System.currentTimeMillis() - ts) + "ms "
                    + (f != null ? f[1][0] + "x" + f[1][1] : "null"));
            if (f == null) return;
        }
        int[][] f = CaptureService.instance.capture();
        if (f == null) {
            Log.e(TAG, "final null");
            return;
        }
        PixelDetector d = new PixelDetector(f[0], f[1][0], f[1][1]);
        long td = System.currentTimeMillis();
        Log.i(TAG, "pageKind=" + d.pageKind());
        PixelDetector.Btn gb = d.findGreenButton();
        Log.i(TAG, "greenBtn=" + (gb == null ? "none" : gb.x + "," + gb.y));
        PixelDetector.Btn dm = d.findDmButton();
        Log.i(TAG, "dmBtn=" + (dm == null ? "none" : dm.x + "," + dm.y));
        java.util.List<int[]> avs = d.findAvatars();
        StringBuilder sb = new StringBuilder();
        for (int[] av : avs) sb.append("(").append(av[0]).append(",").append(av[1]).append(") ");
        Log.i(TAG, "avatars=" + avs.size() + " " + sb);
        Log.i(TAG, "inputMode=" + d.dmInputMode());
        Log.i(TAG, "detect=" + (System.currentTimeMillis() - td) + "ms");
        Log.i(TAG, "TOTAL=" + (System.currentTimeMillis() - t0) + "ms");
    }
}
