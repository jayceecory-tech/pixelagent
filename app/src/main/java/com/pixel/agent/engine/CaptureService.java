package com.pixel.agent.engine;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 截屏引擎: MediaProjection + ImageReader。
 * MediaProjection token 只能消费一次 —— onStartCommand 必须防重入。
 */
public class CaptureService extends Service {
    private static final String TAG = "PixelAgent.Capture";
    private static final String CHANNEL_ID = "pixel_capture";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private volatile boolean ready = false;
    private volatile boolean creating = false;
    private int width, height, dpi;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("ForegroundServiceType")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra("result")) {
            Log.e(TAG, "no result code/intent");
            if (!ready) stopSelf();
            return START_NOT_STICKY;
        }
        // 已就绪时忽略重复启动（MainActivity 曾重复 startForegroundService）
        if (ready && projection != null) {
            Log.i(TAG, "already ready, ignore duplicate start");
            return START_NOT_STICKY;
        }
        if (creating) {
            Log.i(TAG, "capture setup in progress");
            return START_NOT_STICKY;
        }
        creating = true;
        try {
            int resultCode = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("result");
            if (data == null) {
                Log.e(TAG, "null projection data");
                stopSelf();
                return START_NOT_STICKY;
            }

            Notification noti = buildNotification("截屏服务运行中");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1001, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1001, noti);
            }

            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            width = dm.widthPixels;
            height = dm.heightPixels;
            dpi = dm.densityDpi;
            Log.i(TAG, "screen " + width + "x" + height + " dpi=" + dpi);

            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) {
                Log.e(TAG, "getMediaProjection returned null");
                creating = false;
                stopSelf();
                return START_NOT_STICKY;
            }

            // Android 14+: createVirtualDisplay 前必须注册 Callback，否则直接崩
            captureThread = new HandlerThread("capture");
            captureThread.start();
            final Handler handler = new Handler(captureThread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user");
                    ready = false;
                    instance = null;
                    try {
                        if (virtualDisplay != null) {
                            virtualDisplay.release();
                            virtualDisplay = null;
                        }
                    } catch (Throwable ignored) {}
                }
            }, handler);

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

            try {
                virtualDisplay = projection.createVirtualDisplay(
                        "pixelagent", width, height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.getSurface(), null, handler);
            } catch (Throwable t) {
                Log.e(TAG, "createVirtualDisplay failed", t);
                creating = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            ready = true;
            instance = this;
            Log.i(TAG, "capture ready " + width + "x" + height);
            new Thread(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                try {
                    long t0 = System.currentTimeMillis();
                    int[][] f = capture();
                    Log.i(TAG, "SELFTEST capture " + (System.currentTimeMillis() - t0) + "ms "
                            + (f != null ? f[1][0] + "x" + f[1][1] : "null"));
                    if (f != null) {
                        com.pixel.agent.detector.PixelDetector d =
                                new com.pixel.agent.detector.PixelDetector(f[0], f[1][0], f[1][1]);
                        Log.i(TAG, "SELFTEST pageKind=" + d.pageKind()
                                + " avatars=" + d.findAvatars().size()
                                + " green=" + (d.findGreenButton() != null));
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "selftest fail", t);
                }
            }, "selftest").start();
            return START_NOT_STICKY;
        } finally {
            creating = false;
        }
    }

    /**
     * 抓取一帧。返回 [pixels, width, height]，失败返回 null。
     * RGBA_8888 little-endian int = R | G<<8 | B<<16 | A<<24
     */
    public synchronized int[][] capture() {
        if (!ready || imageReader == null || virtualDisplay == null) return null;
        try {
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                Thread.sleep(80);
                image = imageReader.acquireLatestImage();
                if (image == null) {
                    virtualDisplay.resize(width, height, dpi);
                    Thread.sleep(120);
                    image = imageReader.acquireLatestImage();
                    if (image == null) return null;
                }
            }
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = Math.max(1, planes[0].getPixelStride());
            int rowStride = Math.max(width * pixelStride, planes[0].getRowStride());

            int[] out = new int[width * height];
            // 按行读取，正确处理 rowStride > width*pixelStride 的 padding
            int bytesPerPixel = Math.min(4, pixelStride);
            byte[] row = new byte[width * bytesPerPixel];
            for (int y = 0; y < height; y++) {
                int pos = y * rowStride;
                if (pos >= buffer.capacity()) break;
                buffer.position(pos);
                int toRead = Math.min(row.length, buffer.remaining());
                if (toRead < bytesPerPixel) break;
                buffer.get(row, 0, toRead);
                int base = y * width;
                for (int x = 0; x < width; x++) {
                    int i = x * bytesPerPixel;
                    if (i + 3 >= toRead) break;
                    out[base + x] = (row[i] & 0xFF)
                            | ((row[i + 1] & 0xFF) << 8)
                            | ((row[i + 2] & 0xFF) << 16)
                            | ((row[i + 3] & 0xFF) << 24);
                }
            }
            image.close();
            return new int[][]{out, {width, height}};
        } catch (Throwable t) {
            Log.e(TAG, "capture fail", t);
            return null;
        }
    }

    public int getScreenWidth() { return width; }
    public int getScreenHeight() { return height; }

    public static volatile CaptureService instance;

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "截屏", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("PixelAgent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override
    public void onDestroy() {
        ready = false;
        instance = null;
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {}
        try {
            if (projection != null) projection.stop();
        } catch (Throwable ignored) {}
        if (captureThread != null) captureThread.quitSafely();
        virtualDisplay = null;
        projection = null;
        imageReader = null;
        super.onDestroy();
    }
}
