package com.pixel.agent.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.pixel.agent.engine.CaptureService;
import com.pixel.agent.engine.TapService;
import com.pixel.agent.flow.FollowFlow;

/**
 * 悬浮窗控制面板。
 * 支持：展开/收起贴边气泡（滑动或按钮），避免挡住微信点击。
 */
public class FloatingWindowService extends Service {
    private static final String TAG = "PixelAgent.Float";
    private static final String CHANNEL_ID = "pixel_float";
    private static final String PREFS = "pixel_agent";
    private static final String KEY_MSG = "dm_msg";
    private static final String KEY_TARGET = "target";

    public static volatile FloatingWindowService instance;

    private WindowManager windowManager;
    private FrameLayout panelRoot;
    private LinearLayout panel;
    private TextView bubble;
    private TextView statusView;
    private Button startBtn;
    private Button minBtn;
    private FollowFlow flow;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable statusTick;

    private boolean collapsed = false;
    private boolean animating = false;
    private int dockSide = Gravity.START; // START=左, END=右
    private int expandedY = dp(4);

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
        if (panelRoot != null) {
            try {
                windowManager.removeView(panelRoot);
            } catch (Throwable ignored) {}
            panelRoot = null;
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

        panelRoot = new FrameLayout(this);
        panel = buildPanel();
        bubble = buildBubble();

        panelRoot.addView(panel);
        panelRoot.addView(bubble, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        bubble.setVisibility(View.GONE);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = dp(6);
        wlp.y = expandedY;
        windowManager.addView(panelRoot, wlp);

        installDragToCollapse(panel, wlp);
        installBubbleTap(bubble, wlp);
        Log.i(TAG, "float panel added");
    }

    private LinearLayout buildPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC1B1B1B"));
        bg.setCornerRadius(dp(12));
        root.setBackground(bg);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PixelAgent");
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
        startBtn.setBackgroundColor(Color.parseColor("#1565C0"));
        startBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lpStart = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lpStart.rightMargin = dp(6);
        startBtn.setLayoutParams(lpStart);
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

        minBtn = new Button(this);
        minBtn.setText("收起");
        minBtn.setBackgroundColor(Color.parseColor("#455A64"));
        minBtn.setTextColor(Color.WHITE);
        minBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        minBtn.setOnClickListener(v -> collapseToEdge());
        root.addView(minBtn);

        return root;
    }

    private TextView buildBubble() {
        TextView b = new TextView(this);
        b.setText("P");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#1565C0"));
        b.setBackground(bg);
        int sz = dp(48);
        b.setLayoutParams(new FrameLayout.LayoutParams(sz, sz));
        return b;
    }

    /** 拖到左/右边沿 → 贴边收起 */
    private void installDragToCollapse(View target, WindowManager.LayoutParams wlp) {
        final float[] startX = {0};
        final float[] startY = {0};
        final int[] origX = {0};
        final int[] origY = {0};
        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = event.getRawX();
                    startY[0] = event.getRawY();
                    origX[0] = wlp.x;
                    origY[0] = wlp.y;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - startX[0];
                    float dy = event.getRawY() - startY[0];
                    wlp.x = origX[0] + (int) dx;
                    wlp.y = origY[0] + (int) dy;
                    try {
                        windowManager.updateViewLayout(panelRoot, wlp);
                    } catch (Throwable ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float totalX = event.getRawX() - startX[0];
                    float totalY = event.getRawY() - startY[0];
                    // 小位移视为点击，交给子按钮；大幅位移判定为拖到边
                    if (Math.abs(totalX) > dp(40) || Math.abs(totalY) > dp(40)) {
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        if (wlp.x < screenW / 3f) {
                            dockSide = Gravity.START;
                            collapseToEdge();
                        } else if (wlp.x > screenW * 2f / 3f) {
                            dockSide = Gravity.END;
                            collapseToEdge();
                        } else {
                            // 落回左上
                            wlp.x = dp(6);
                            wlp.y = expandedY;
                            try {
                                windowManager.updateViewLayout(panelRoot, wlp);
                            } catch (Throwable ignored) {}
                        }
                    }
                    return false;
            }
            return false;
        });
    }

    private void installBubbleTap(TextView b, WindowManager.LayoutParams wlp) {
        b.setOnClickListener(v -> expandFromEdge(wlp));
    }

    private void collapseToEdge() {
        if (panelRoot == null || animating || collapsed) return;
        final WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) panelRoot.getLayoutParams();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        final int fromX = wlp.x;
        final int fromY = wlp.y;
        final int toX = dockSide == Gravity.START ? -dp(10) : screenW - dp(40);
        final int toY = dp(120);

        animating = true;
        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(280);
        an.setInterpolator(new AccelerateInterpolator());
        an.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            wlp.x = (int) (fromX + (toX - fromX) * f);
            wlp.y = (int) (fromY + (toY - fromY) * f);
            panel.setAlpha(1f - f * 0.85f);
            panel.setScaleX(1f - f * 0.35f);
            panel.setScaleY(1f - f * 0.35f);
            try {
                windowManager.updateViewLayout(panelRoot, wlp);
            } catch (Throwable ignored) {}
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panel.setVisibility(View.GONE);
                bubble.setVisibility(View.VISIBLE);
                // 气泡贴边
                wlp.gravity = Gravity.TOP | (dockSide == Gravity.START ? Gravity.START : Gravity.END);
                wlp.x = dockSide == Gravity.START ? dp(4) : dp(4);
                wlp.y = toY;
                try {
                    windowManager.updateViewLayout(panelRoot, wlp);
                } catch (Throwable ignored) {}
                panel.setAlpha(1f);
                panel.setScaleX(1f);
                panel.setScaleY(1f);
                collapsed = true;
                animating = false;
                Log.i(TAG, "collapsed to edge side=" + dockSide);
            }
        });
        an.start();
    }

    private void expandFromEdge(WindowManager.LayoutParams wlp) {
        if (panelRoot == null || animating || !collapsed) return;
        animating = true;
        final int fromX = wlp.x;
        final int fromY = wlp.y;
        final int toX = dp(6);
        final int toY = expandedY;

        bubble.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.7f);
        panel.setScaleY(0.7f);

        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(280);
        an.setInterpolator(new DecelerateInterpolator());
        an.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            wlp.gravity = Gravity.TOP | Gravity.START;
            wlp.x = (int) (fromX + (toX - fromX) * f);
            wlp.y = (int) (fromY + (toY - fromY) * f);
            panel.setAlpha(f);
            panel.setScaleX(0.7f + 0.3f * f);
            panel.setScaleY(0.7f + 0.3f * f);
            try {
                windowManager.updateViewLayout(panelRoot, wlp);
            } catch (Throwable ignored) {}
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panel.setAlpha(1f);
                panel.setScaleX(1f);
                panel.setScaleY(1f);
                collapsed = false;
                animating = false;
                Log.i(TAG, "expanded from edge");
            }
        });
        an.start();
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
            msg = getString(com.pixel.agent.R.string.default_dm_msg);
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
        String wx = TapService.isWechatForeground() ? "微信前台✓" : "微信前台✗";
        CharSequence cur = statusView.getText();
        if (cur != null) {
            String s = cur.toString();
            if (s.contains("进度") || s.contains("运行") || s.contains("启动")
                    || s.contains("完成") || s.contains("点头像") || s.contains("关注")
                    || s.contains("搜索") || s.contains("文章") || s.contains("私信")
                    || s.contains("导航") || s.contains("提交") || s.contains("状态")
                    || s.contains("误入") || s.contains("恢复")) {
                return;
            }
        }
        statusView.setText(cap + " " + tap + " " + wx);
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
