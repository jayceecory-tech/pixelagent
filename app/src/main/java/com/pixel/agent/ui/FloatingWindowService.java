package com.pixel.agent.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.pixel.agent.R;
import com.pixel.agent.engine.CaptureService;
import com.pixel.agent.engine.TapService;
import com.pixel.agent.flow.FollowFlow;

/**
 * 悬浮窗控制面板：常驻在微信上层，点击「开始」跑互关流程。
 * 目的：MainActivity 切后台后 vivo 容易冻结/断开无障碍；
 * 悬浮窗保持进程可见，流程在微信前台时仍可点开始/停止。
 */
public class FloatingWindowService extends Service {
    private static final String TAG = "PixelAgent.Float";
    private static final String CHANNEL_ID = "pixel_float";
    private static final String PREFS = "pixel_agent";
    private static final String KEY_MSG = "dm_msg";
    private static final String KEY_TARGET = "target";

    public static volatile FloatingWindowService instance;

    private WindowManager windowManager;
    private View floatView;
    private TextView statusView;
    private Button startBtn;
    private FollowFlow flow;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable statusTick;

    public static void show(Context ctx) {
        Intent i = new Intent(ctx, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void hide(Context ctx) {
        ctx.stopService(new Intent(ctx, FloatingWindowService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(2001, buildNotification("PixelAgent 悬浮控制"));
        addOverlay();
        statusTick = new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(statusTick);
        Log.i(TAG, "float window created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_FLOW".equals(intent.getAction())) {
            stopFlow();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(statusTick);
        stopFlow();
        if (floatView != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Throwable ignored) {}
            floatView = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void addOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#CC1B1B1B"));
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PixelAgent 悬浮控制");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("待启动");
        statusView.setTextColor(Color.parseColor("#B0BEC5"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setPadding(0, dp(4), 0, dp(6));
        root.addView(statusView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        startBtn = new Button(this);
        startBtn.setText("开始");
        // 不能用微信关注绿 #07C160，否则像素检测会当成名片关注钮
        startBtn.setBackgroundColor(Color.parseColor("#1565C0"));
        startBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.rightMargin = dp(6);
        startBtn.setLayoutParams(lp);
        startBtn.setOnClickListener(v -> toggleFlow());
        row.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setBackgroundColor(Color.parseColor("#C62828"));
        stopBtn.setTextColor(Color.WHITE);
        stopBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        stopBtn.setOnClickListener(v -> {
            stopFlow();
            toast("已请求停止");
        });
        row.addView(stopBtn);

        root.addView(row);

        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        // 放在左上角状态栏下方，避免盖住微信列表/底部按钮
        // 注意: dp 在 560dpi 下约 ×3.5，y 用很小的 dp 值
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = dp(6);
        wlp.y = dp(4); // ≈14px @560dpi，紧贴状态栏下沿

        windowManager.addView(root, wlp);
        floatView = root;
    }

    private void toggleFlow() {
        if (flow != null && flow.isRunning()) {
            stopFlow();
            toast("已停止");
            return;
        }
        if (CaptureService.instance == null) {
            toast("请先在主界面启动截屏服务");
            statusView.setText("缺少截屏服务");
            return;
        }
        if (TapService.instance == null) {
            toast("请先开启无障碍 PixelAgent");
            statusView.setText("缺少无障碍服务");
            return;
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String msg = sp.getString(KEY_MSG, null);
        if (msg == null || msg.trim().isEmpty()) {
            msg = getString(R.string.default_dm_msg);
        }
        int target = sp.getInt(KEY_TARGET, 5);
        startFlow(msg.trim(), target);
        toast("已从悬浮窗启动");
    }

    public void startFlow(String msg, int target) {
        stopFlow();
        String[] msgs = {msg};
        flow = new FollowFlow(getApplicationContext(), msgs, target, new FollowFlow.Listener() {
            @Override
            public void onLog(final String line) {
                ui.post(() -> {
                    if (statusView != null) statusView.setText(line);
                    Log.i(TAG, line);
                });
            }

            @Override
            public void onProgress(final int done, final int t, final int followed, final int dm) {
                ui.post(() -> {
                    if (statusView != null) {
                        statusView.setText(String.format("进度 %d/%d 关注%d 私信%d", done, t, followed, dm));
                    }
                    if (startBtn != null) startBtn.setText("运行中");
                });
            }

            @Override
            public void onFinish(final String summary) {
                ui.post(() -> {
                    if (statusView != null) statusView.setText(summary);
                    if (startBtn != null) startBtn.setText("开始");
                });
            }
        });
        new Thread(flow, "followFlowFloat").start();
        if (statusView != null) statusView.setText("已启动，正在打开微信…");
        if (startBtn != null) startBtn.setText("运行中");
    }

    public void stopFlow() {
        if (flow != null) {
            flow.stop();
            flow = null;
        }
        if (startBtn != null) startBtn.setText("开始");
    }

    private void refreshStatus() {
        if (statusView == null) return;
        String cap = CaptureService.instance != null ? "截屏✓" : "截屏✗";
        String tap = TapService.instance != null ? "点击✓" : "点击✗";
        CharSequence cur = statusView.getText();
        if (cur != null && (cur.toString().contains("进度") || cur.toString().contains("运行")
                || cur.toString().contains("启动") || cur.toString().contains("完成")
                || cur.toString().contains("点头像") || cur.toString().contains("关注")
                || cur.toString().contains("搜索") || cur.toString().contains("文章")
                || cur.toString().contains("私信") || cur.toString().contains("导航")
                || cur.toString().contains("提交") || cur.toString().contains("状态"))) {
            // 保留流程日志，只在启动前刷新服务状态
            if (cur.toString().equals("待启动") || cur.toString().startsWith("缺少")
                    || cur.toString().contains("服务")) {
                statusView.setText(cap + " " + tap);
            }
            return;
        }
        statusView.setText(cap + " " + tap);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "悬浮控制", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification(String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PixelAgent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true);
        return b.build();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    /** 主界面写入话术/目标后刷新 */
    public void updatePrefs(String msg, int target) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_MSG, msg)
                .putInt(KEY_TARGET, target)
                .apply();
    }

    public static void updatePrefsStatic(Context ctx, String msg, int target) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MSG, msg)
                .putInt(KEY_TARGET, target)
                .apply();
        FloatingWindowService s = instance;
        if (s != null) s.updatePrefs(msg, target);
    }
}
