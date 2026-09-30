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
    private static final String KEY_COMMENTS = "comment_msgs";
    private static final long AUTO_HIDE_MS = 12_000L;

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
    private Runnable autoHideTick;

    private boolean collapsed = false;
    private boolean animating = false;
    private int dockSide = Gravity.START;
    private int expandedY = 4;
    private long lastInteractMs;

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
        expandedY = dp(6);
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
        autoHideTick = new Runnable() {
            @Override
            public void run() {
                if (!collapsed && !animating
                        && (System.currentTimeMillis() - lastInteractMs) > AUTO_HIDE_MS) {
                    // 流程运行中不自动收起，避免误操作
                    if (flow == null || !flow.isRunning()) {
                        collapseToEdge();
                    }
                }
                ui.postDelayed(this, 3000);
            }
        };
        ui.post(statusTick);
        ui.post(autoHideTick);
        lastInteractMs = System.currentTimeMillis();
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
        ui.removeCallbacks(autoHideTick);
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

    private void touch() {
        lastInteractMs = System.currentTimeMillis();
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
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#F21565C0"));
        bg.setCornerRadius(dp(14));
        root.setBackground(bg);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        root.setMinimumWidth(dp(280));

        TextView title = new TextView(this);
        title.setText("PixelAgent  控制台");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("待启动 · 点「开始」跑互关");
        statusView.setTextColor(Color.parseColor("#B3E5FC"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        startBtn = new Button(this);
        startBtn.setText("开始");
        startBtn.setBackgroundColor(Color.parseColor("#26C6DA"));
        startBtn.setTextColor(Color.parseColor("#00334D"));
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lpStart = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lpStart.rightMargin = dp(6);
        startBtn.setLayoutParams(lpStart);
        startBtn.setOnClickListener(v -> {
            touch();
            toggleFlow();
        });
        row.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setBackgroundColor(Color.parseColor("#EF5350"));
        stopBtn.setTextColor(Color.WHITE);
        stopBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1f));
        stopBtn.setOnClickListener(v -> {
            touch();
            stopFlow();
            toast("已请求停止");
        });
        row.addView(stopBtn);

        root.addView(row);

        minBtn = new Button(this);
        minBtn.setText("收起到侧边  ·  拖到左右边缘也可");
        minBtn.setBackgroundColor(Color.parseColor("#37474F"));
        minBtn.setTextColor(Color.WHITE);
        minBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        minBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        minBtn.setOnClickListener(v -> {
            touch();
            collapseToEdge();
        });
        root.addView(minBtn);

        TextView tip = new TextView(this);
        tip.setText("支持评论区自动留言：互关，一起努力～");
        tip.setTextColor(Color.parseColor("#90CAF9"));
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tip.setPadding(0, dp(6), 0, 0);
        root.addView(tip);

        return root;
    }

    private TextView buildBubble() {
        TextView b = new TextView(this);
        b.setText("P");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#1565C0"));
        bg.setStroke(dp(2), Color.parseColor("#42A5F5"));
        b.setBackground(bg);
        int sz = dp(52);
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
            touch();
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
                    if (Math.abs(totalX) > dp(40) || Math.abs(totalY) > dp(40)) {
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        if (wlp.x < screenW / 3f) {
                            dockSide = Gravity.START;
                            collapseToEdge();
                        } else if (wlp.x > screenW * 2f / 3f) {
                            dockSide = Gravity.END;
                            collapseToEdge();
                        } else {
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
        b.setOnClickListener(v -> {
            touch();
            expandFromEdge(wlp);
        });
        // 长按也可展开
        b.setOnLongClickListener(v -> {
            touch();
            expandFromEdge(wlp);
            return true;
        });
    }

    private void collapseToEdge() {
        if (panelRoot == null || animating || collapsed) return;
        touch();
        final WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) panelRoot.getLayoutParams();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        final int fromX = wlp.x;
        final int fromY = wlp.y;
        final int toX = dockSide == Gravity.START ? -dp(8) : screenW - dp(44);
        final int toY = dp(160);

        animating = true;
        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(320);
        an.setInterpolator(new AccelerateInterpolator());
        an.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            wlp.x = (int) (fromX + (toX - fromX) * f);
            wlp.y = (int) (fromY + (toY - fromY) * f);
            panel.setAlpha(1f - f * 0.9f);
            panel.setScaleX(1f - f * 0.4f);
            panel.setScaleY(1f - f * 0.4f);
            try {
                windowManager.updateViewLayout(panelRoot, wlp);
            } catch (Throwable ignored) {}
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panel.setVisibility(View.GONE);
                bubble.setVisibility(View.VISIBLE);
                wlp.gravity = Gravity.TOP | (dockSide == Gravity.START ? Gravity.START : Gravity.END);
                wlp.x = dp(6);
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
        panel.setScaleX(0.65f);
        panel.setScaleY(0.65f);

        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(320);
        an.setInterpolator(new DecelerateInterpolator());
        an.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            wlp.gravity = Gravity.TOP | Gravity.START;
            wlp.x = (int) (fromX + (toX - fromX) * f);
            wlp.y = (int) (fromY + (toY - fromY) * f);
            panel.setAlpha(f);
            panel.setScaleX(0.65f + 0.35f * f);
            panel.setScaleY(0.65f + 0.35f * f);
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
                touch();
                Log.i(TAG, "expanded from edge");
            }
        });
        an.start();
    }

    private void toggleFlow() {
        touch();
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
        String commentsRaw = sp.getString(KEY_COMMENTS, null);
        if (commentsRaw == null || commentsRaw.trim().isEmpty()) {
            commentsRaw = getString(com.pixel.agent.R.string.default_comment_1) + "|"
                    + getString(com.pixel.agent.R.string.default_comment_2) + "|"
                    + getString(com.pixel.agent.R.string.default_comment_3);
        }
        String[] comments = commentsRaw.split("\\|");
        int target = sp.getInt(KEY_TARGET, 5);
        startFlow(msg.trim(), target, comments);
        toast("已从悬浮窗启动（含评论留言）");
    }

    public void startFlow(String msg, int target, String[] comments) {
        stopFlow();
        String[] msgs = {msg};
        if (comments == null || comments.length == 0) {
            comments = new String[]{
                    getString(com.pixel.agent.R.string.default_comment_1),
                    getString(com.pixel.agent.R.string.default_comment_2),
                    getString(com.pixel.agent.R.string.default_comment_3),
            };
        }
        flow = new FollowFlow(getApplicationContext(), msgs, target, comments,
                new FollowFlow.Listener() {
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
                        statusView.setText(String.format(
                                "进度 %d/%d 关注%d 私信%d", done, t, followed, dm));
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
        if (statusView != null) statusView.setText("已启动（留言+关注+私信）…");
        if (startBtn != null) startBtn.setText("运行中");
        touch();
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

    public void updatePrefs(String msg, int target, String comments) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_MSG, msg)
                .putInt(KEY_TARGET, target)
                .putString(KEY_COMMENTS, comments)
                .apply();
    }

    public static void updatePrefsStatic(Context ctx, String msg, int target, String comments) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MSG, msg)
                .putInt(KEY_TARGET, target)
                .putString(KEY_COMMENTS, comments)
                .apply();
        FloatingWindowService s = instance;
        if (s != null) s.updatePrefs(msg, target, comments);
    }
}
