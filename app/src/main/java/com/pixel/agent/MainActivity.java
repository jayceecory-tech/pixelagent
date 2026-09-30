package com.pixel.agent;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.pixel.agent.engine.CaptureService;
import com.pixel.agent.engine.TapService;
import com.pixel.agent.ui.FloatingWindowService;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAPTURE = 100;
    private static final int REQ_OVERLAY = 101;
    private static final int TARGET = 5;

    private TextView statusText, logText;
    private EditText editMsg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        editMsg = findViewById(R.id.editMsg);
        editMsg.setText(getString(R.string.default_dm_msg));

        findViewById(R.id.btnCapture).setOnClickListener(v -> startCapture());
        findViewById(R.id.btnTest).setOnClickListener(v ->
                new Thread(new TestRunner(), "pixelTest").start());
        findViewById(R.id.btnAccessibility).setOnClickListener(v -> openAccessibility());
        findViewById(R.id.btnStart).setOnClickListener(v -> showFloatAndHint());
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            FloatingWindowService.hide(this);
            statusText.setText("状态: 悬浮窗已关闭");
        });
        findViewById(R.id.btnFloat).setOnClickListener(v -> ensureOverlayThenFloat());
    }

    private void startCapture() {
        if (CaptureService.instance != null) {
            statusText.setText("状态: 截屏服务已就绪 ✓");
            toast("截屏服务已在运行");
            savePrefs();
            return;
        }
        android.media.projection.MediaProjectionManager mpm =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, CaptureService.class);
            svc.putExtra("code", resultCode);
            svc.putExtra("result", data);
            if (Build.VERSION.SDK_INT >= 29) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            statusText.postDelayed(() -> {
                if (CaptureService.instance != null) {
                    statusText.setText("状态: 截屏服务已就绪 ✓");
                } else {
                    statusText.setText("状态: 截屏服务启动中...稍候再试");
                }
            }, 800);
            toast("截屏服务已启动");
            savePrefs();
        } else if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                showFloat();
            } else {
                toast("仍无悬浮窗权限");
            }
        } else if (resultCode == RESULT_CANCELED) {
            statusText.setText("状态: 截屏授权被拒绝");
        }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("只开启 PixelAgent，不要开 TalkBack");
        } catch (Exception e) {
            toast("无法打开无障碍设置");
        }
    }

    private void showFloatAndHint() {
        savePrefs();
        ensureOverlayThenFloat();
        statusText.setText("状态: 请点悬浮窗「开始」");
        toast("已弹出悬浮窗，微信前台点开始");
        // 尝试打开微信，方便用户看到悬浮窗叠在微信上
        Intent it = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (it != null) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        }
    }

    private void ensureOverlayThenFloat() {
        savePrefs();
        if (!Settings.canDrawOverlays(this)) {
            toast("请允许悬浮窗权限");
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        showFloat();
        statusText.setText("状态: 悬浮窗已显示");
    }

    private void showFloat() {
        FloatingWindowService.updatePrefsStatic(this,
                editMsg.getText() == null ? "" : editMsg.getText().toString(),
                TARGET);
        FloatingWindowService.show(this);
    }

    private void savePrefs() {
        String msg = editMsg.getText() == null ? "" : editMsg.getText().toString();
        getSharedPreferences("pixel_agent", MODE_PRIVATE).edit()
                .putString("dm_msg", msg.trim())
                .putInt("target", TARGET)
                .apply();
        FloatingWindowService.updatePrefsStatic(this, msg, TARGET);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
