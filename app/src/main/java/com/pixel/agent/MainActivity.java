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

    private static final String[] PREF_IME_CANDIDATES = {
            "com.sohu.inputmethod.sogou.vivo/.SogouIME",
            "com.sohu.inputmethod.sogou/.SogouIME",
            "com.baidu.input_vivo/.ImeVivoService",
            "com.iflytek.inputmethod.vivo/.ImeService",
            "com.android.inputmethod.latin/.LatinIME",
    };

    private TextView statusText, logText;
    private EditText editMsg, editComment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        editMsg = findViewById(R.id.editMsg);
        editComment = findViewById(R.id.editComment);
        editMsg.setText(getString(R.string.default_dm_msg));
        editComment.setText(commentTemplate());

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
        findViewById(R.id.btnRestoreIme).setOnClickListener(v -> restoreSystemIme());
    }

    private String commentTemplate() {
        return getString(R.string.default_comment_1) + "|"
                + getString(R.string.default_comment_2) + "|"
                + getString(R.string.default_comment_3);
    }

    private String[] parseComments(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new String[]{
                    getString(R.string.default_comment_1),
                    getString(R.string.default_comment_2),
                    getString(R.string.default_comment_3),
            };
        }
        String[] parts = raw.split("\\|");
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) list.add(t);
        }
        if (list.isEmpty()) {
            list.add(getString(R.string.default_comment_1));
        }
        return list.toArray(new String[0]);
    }

    private void restoreSystemIme() {
        String cur = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String target = pickSystemIme(cur);
        if (target == null) {
            toast("未找到搜狗/系统输入法，请手动选择");
            statusText.setText("状态: 请到设置切换输入法");
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (Exception ignored) {}
            return;
        }
        Settings.Secure.putString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, target);
        String now = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (target.equals(now)) {
            String label = imeLabel(target);
            statusText.setText("状态: 键盘已恢复 " + label);
            toast("键盘已恢复: " + label);
        } else {
            toast("设置失败，请到「设置→系统→输入法」手动切换");
            statusText.setText("状态: 键盘恢复未生效");
        }
        logText.append("恢复输入法: " + (now != null ? now : "null") + "\n");
    }

    private String pickSystemIme(String current) {
        if (current != null && !current.contains("adbkeyboard")
                && !current.contains("ADB")) {
            for (String c : PREF_IME_CANDIDATES) {
                if (c.equals(current)) return c;
            }
        }
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        for (String c : PREF_IME_CANDIDATES) {
            if (enabled != null && enabled.contains(c)) return c;
        }
        if (enabled != null) {
            for (String c : PREF_IME_CANDIDATES) {
                String pkg = c.substring(0, c.indexOf('/'));
                if (enabled.contains(pkg)) return c;
            }
        }
        if (enabled != null) {
            for (String part : enabled.split(":")) {
                if (!part.contains("adbkeyboard") && part.contains("/")) return part;
            }
        }
        return null;
    }

    private String imeLabel(String id) {
        if (id == null) return "未知";
        if (id.contains("sogou.vivo")) return "搜狗输入法 vivo";
        if (id.contains("sogou")) return "搜狗输入法";
        if (id.contains("baidu")) return "百度输入法";
        if (id.contains("adbkeyboard")) return "ADB Keyboard";
        return id;
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
        String msg = editMsg.getText() == null ? "" : editMsg.getText().toString();
        String comments = editComment.getText() == null ? "" : editComment.getText().toString();
        FloatingWindowService.updatePrefsStatic(this, msg.trim(), TARGET, comments);
        FloatingWindowService.show(this);
    }

    private void savePrefs() {
        String msg = editMsg.getText() == null ? "" : editMsg.getText().toString();
        String comments = editComment.getText() == null ? "" : editComment.getText().toString();
        getSharedPreferences("pixel_agent", MODE_PRIVATE).edit()
                .putString("dm_msg", msg.trim())
                .putString("comment_msgs", comments.trim())
                .putInt("target", TARGET)
                .apply();
        FloatingWindowService.updatePrefsStatic(this, msg, TARGET, comments);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
