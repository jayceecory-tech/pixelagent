package com.pixel.agent.flow;

import com.pixel.agent.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pixel.agent.detector.PixelDetector;
import com.pixel.agent.engine.CaptureService;
import com.pixel.agent.engine.TapService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * 全自动互关流程（1260x2800 实测坐标）:
 *   微信首页 → 搜索"公众号互关" → 文章 tab → 最新 → 打开文章
 *   → 进评论区 → 点头像 → 名片关注 → 私信求回关 → 返回
 *   评论耗尽后返回列表打开下一篇。
 */
public class FollowFlow implements Runnable {
    private static final String TAG = "PixelAgent.Flow";
    public static final String ACTION_ADB_INPUT_B64 = "ADB_INPUT_B64";
    public static final String ACTION_ADB_CLEAR_TEXT = "ADB_CLEAR_TEXT";

    private static final String[] SEARCH_KEYWORDS = {
            "公众号互关",
            "互关帖",
            "冲100粉互助"
    };

    private final Context context;
    private final String[] msgs;
    private final String[] commentMsgs;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private final Random rand = new Random();

    private final List<Integer> processed = new ArrayList<>();
    private int msgIdx = 0;
    private int commentIdx = 0;
    private int done = 0, successFollow = 0, successDm = 0, successComment = 0;
    private int searchIdx = 0;
    private int articleTaps = 0;
    private int emptyCommentSwipes = 0;
    private final int target;

    public interface Listener {
        void onLog(String line);
        void onProgress(int done, int target, int followed, int dm);
        void onFinish(String summary);
    }

    public FollowFlow(Context ctx, String[] msgs, int target, Listener l) {
        this(ctx, msgs, target, null, l);
    }

    public FollowFlow(Context ctx, String[] msgs, int target, String[] comments, Listener l) {
        this.context = ctx;
        this.msgs = msgs;
        this.target = target;
        if (comments != null && comments.length > 0) {
            this.commentMsgs = comments;
        } else {
            this.commentMsgs = new String[]{
                    ctx.getString(R.string.default_comment_1),
                    ctx.getString(R.string.default_comment_2),
                    ctx.getString(R.string.default_comment_3),
            };
        }
        this.listener = l;
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    // ---------- 基础 ----------
    private PixelDetector shot() {
        CaptureService cap = CaptureService.instance;
        if (cap == null) return null;
        int[][] f = cap.capture();
        if (f == null) return null;
        return new PixelDetector(f[0], f[1][0], f[1][1]);
    }

    private PixelDetector.Page page() {
        PixelDetector d = shot();
        return d == null ? PixelDetector.Page.UNKNOWN : d.pageKind();
    }

    private boolean tap(PixelDetector.Btn b) {
        if (b == null) return false;
        if (!ensureWechatForeground(true)) return false;
        TapService tap = TapService.instance;
        if (tap == null) return false;
        return tap.tap(b.x, b.y);
    }

    private void tapXY(int x, int y) {
        if (!ensureWechatForeground(true)) return;
        TapService tap = TapService.instance;
        if (tap != null) tap.tap(x, y);
    }

    private void back() {
        TapService tap = TapService.instance;
        if (tap != null) tap.back();
        sleep(1200);
    }

    /** 当前前台是否微信 */
    private boolean onWechat() {
        String fg = currentForegroundPackage();
        return TapService.PKG_WECHAT.equals(fg);
    }

    /**
     * 前台包名：优先无障碍事件；失败时用 ActivityManager（不依赖无障碍存活）。
     */
    private String currentForegroundPackage() {
        String a11y = TapService.foregroundPackage;
        if (TapService.PKG_WECHAT.equals(a11y)) return a11y;
        String am = amForegroundPackage();
        // 两边都不像微信时，以 AM 为准（更接近真实前台）
        if (am != null) return am;
        return a11y;
    }

    private String amForegroundPackage() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                    am.getRunningAppProcesses();
            if (list == null) return null;
            for (android.app.ActivityManager.RunningAppProcessInfo p : list) {
                if (p == null || p.processName == null) continue;
                if (p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        || p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    return p.processName;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 确保微信在前台。误触广告/分享/其它应用时强制拉回。
     * @return true=可以安全点击
     */
    private boolean ensureWechatForeground(boolean logIfRecovered) {
        if (onWechat()) return true;
        String pkg = currentForegroundPackage();
        log("前台不是微信: " + pkg + ", 拉回微信");
        openWechat();
        sleep(2200);
        for (int i = 0; i < 3 && !onWechat(); i++) {
            if (TapService.instance != null) {
                back();
            }
            openWechat();
            sleep(1500);
        }
        if (onWechat()) {
            if (logIfRecovered) log("已回到微信前台: " + TapService.foregroundPackage);
            return true;
        }
        // 无障碍断开时仍可能已打开微信，但事件没更新
        String now = currentForegroundPackage();
        if (TapService.PKG_WECHAT.equals(now)) {
            if (logIfRecovered) log("已回到微信前台(AM): " + now);
            return true;
        }
        log("仍无法确认微信前台, 暂停点击 (tap="
                + (TapService.instance != null) + ")");
        return false;
    }

    /** 页面疑似广告/分享/非微信业务页: 立即 BACK，避免乱点其它应用 */
    private boolean looksLikeAdOrShare(PixelDetector.Page p) {
        if (p == PixelDetector.Page.AD) return true;
        if (p == PixelDetector.Page.HOME) return true;
        if (p == PixelDetector.Page.UNKNOWN) return true;
        if (p == PixelDetector.Page.SEARCH_INPUT || p == PixelDetector.Page.SEARCH_RESULTS) return false;
        return false;
    }

    /** 周期检查前台，约每 8 秒做一次完整恢复 */
    private int lastWechatCheckRound = 0;

    private void periodicWechatGuard(int round) {
        if (round - lastWechatCheckRound < 8) return;
        lastWechatCheckRound = round;
        if (TapService.instance == null) return;
        if (onWechat()) {
            log("前台检查: 微信 ✓");
            return;
        }
        log("前台检查: 不是微信 (" + TapService.foregroundPackage + ")");
        ensureWechatForeground(true);
    }

    private void backToComments(int maxBacks) {
        for (int i = 0; i < maxBacks; i++) {
            PixelDetector d = shot();
            if (d != null && d.pageKind() == PixelDetector.Page.COMMENTS) return;
            TapService tap = TapService.instance;
            if (tap == null) return;
            tap.back();
            sleep(1100);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String s) {
        Log.i(TAG, s);
        ui.post(() -> {
            if (listener != null) listener.onLog(s);
        });
    }

    private void progress() {
        final int d = done, t = target, f = successFollow, m = successDm;
        ui.post(() -> {
            if (listener != null) listener.onProgress(d, t, f, m);
        });
    }

    public int getSuccessComment() {
        return successComment;
    }

    private int screenW() {
        CaptureService cap = CaptureService.instance;
        if (cap != null && cap.getScreenWidth() > 0) return cap.getScreenWidth();
        return 1260;
    }

    private int screenH() {
        CaptureService cap = CaptureService.instance;
        if (cap != null && cap.getScreenHeight() > 0) return cap.getScreenHeight();
        return 2800;
    }

    private boolean ready() {
        return CaptureService.instance != null && TapService.instance != null;
    }

    // ---------- 中文输入 ----------
    private void clearText() {
        // ADB_CLEAR_TEXT 在无输入焦点时会让 AdbIME 崩溃, 必须先聚焦输入框
        try {
            context.sendBroadcast(new Intent(ACTION_ADB_CLEAR_TEXT));
            sleep(300);
        } catch (Throwable ignored) {}
    }

    private void typeIntoSearchBox(String kw) {
        // 先点搜索框聚焦, 再注入, 不要盲目 CLEAR
        tapXY(300, 230);
        sleep(600);
        try {
            String b64 = Base64.getEncoder()
                    .encodeToString(kw.getBytes(StandardCharsets.UTF_8));
            Intent i = new Intent(ACTION_ADB_INPUT_B64);
            i.putExtra("msg", b64);
            context.sendBroadcast(i);
            sleep(1200);
        } catch (Throwable t) {
            Log.e(TAG, "typeIntoSearchBox", t);
        }
    }

    private boolean typeViaAdbKeyboard(String msg) {
        try {
            String b64 = Base64.getEncoder()
                    .encodeToString(msg.getBytes(StandardCharsets.UTF_8));
            Intent i = new Intent(ACTION_ADB_INPUT_B64);
            i.putExtra("msg", b64);
            context.sendBroadcast(i);
            sleep(1300);
            PixelDetector d = shot();
            return d != null && d.dmInputHasText();
        } catch (Throwable t) {
            Log.e(TAG, "typeViaAdbKeyboard", t);
            return false;
        }
    }

    private boolean typeText(String msg) {
        // 仅在已聚焦私信输入框时注入; 不盲发 CLEAR
        if (typeViaAdbKeyboard(msg)) {
            log("输入方式: ADBKeyboard");
            return true;
        }
        return false;
    }

    // ---------- 导航: 搜索互关帖 ----------
    private void openWechat() {
        Intent it = context.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (it != null) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(it);
            log("打开微信");
        }
        sleep(2500);
        log("tap=" + (TapService.instance != null)
                + " capture=" + (CaptureService.instance != null)
                + " fg=" + TapService.foregroundPackage
                + " page=" + page());
    }

    private void ensureWechatSearchPage() {
        openWechat();
        for (int i = 0; i < 10; i++) {
            if (TapService.instance == null || CaptureService.instance == null) {
                log("服务断开 tap=" + (TapService.instance != null)
                        + " cap=" + (CaptureService.instance != null) + ", 停止");
                running = false;
                return;
            }
            PixelDetector.Page p = page();
            log("导航检查#" + i + ": " + p
                    + " tap=" + (TapService.instance != null));
            if (p == PixelDetector.Page.SEARCH_INPUT || p == PixelDetector.Page.SEARCH_RESULTS) {
                return;
            }
            // 停在名片/私信/评论等页: 先返回，再进搜索
            if (p == PixelDetector.Page.CARD_UNFOLLOWED
                    || p == PixelDetector.Page.CARD_FOLLOWED
                    || p == PixelDetector.Page.DM
                    || p == PixelDetector.Page.COMMENTS
                    || p == PixelDetector.Page.ARTICLE) {
                log("当前页非搜索, BACK恢复: " + p);
                back();
                sleep(1400);
                continue;
            }
            // 强制回到微信首页后再点搜索
            openWechat();
            int w = screenW();
            tapXY((int) (w * 0.833), 207);
            sleep(2200);
            PixelDetector.Page p2 = page();
            log("点搜索后 page=" + p2);
            if (p2 == PixelDetector.Page.SEARCH_INPUT || p2 == PixelDetector.Page.SEARCH_RESULTS) {
                return;
            }
        }
        log("导航结束 page=" + page() + "（可能仍不在搜索页）");
    }

    private boolean typeSearchKeyword() {
        String kw = SEARCH_KEYWORDS[searchIdx % SEARCH_KEYWORDS.length];
        log("搜索关键词: " + kw);
        typeIntoSearchBox(kw);
        // 提交: 建议第一项 + 绿色搜索钮
        int[][] submit = {
                {500, 730}, {500, 800},
                {1124, 230}, {1080, 230}
        };
        for (int[] s : submit) {
            tapXY(s[0], s[1]);
            sleep(1800);
            PixelDetector.Page p = page();
            log("提交(" + s[0] + "," + s[1] + ")->" + p
                    + " tap=" + (TapService.instance != null));
            if (TapService.instance == null) return false;
            if (p == PixelDetector.Page.SEARCH_RESULTS) return true;
        }
        return page() == PixelDetector.Page.SEARCH_RESULTS;
    }

    private boolean looksLikeTabbedResults() {
        PixelDetector d = shot();
        if (d == null) return false;
        // 手动再查 tab 行 + 列表
        return d.pageKind() == PixelDetector.Page.SEARCH_RESULTS
                || d.pageKind() == PixelDetector.Page.SEARCH_INPUT;
    }

    private void openArticleTabAndLatest() {
        // 文章 tab 实测约 x345 y400
        tapXY(345, 400);
        sleep(1800);
        log("已点文章 tab, page=" + page());
        // 最新排序
        tapXY(643, 575);
        sleep(1600);
        log("已点最新, page=" + page());
    }

    private void openNextArticle() {
        int w = screenW(), h = screenH();
        // 实测文章列表标题约在 y1250 / 1710 / 2100 / 2570
        int[] candidates = {1250, 1710, 2100, 2570, 900, 756};
        int tapY;
        if (articleTaps < candidates.length) {
            tapY = candidates[articleTaps];
        } else {
            TapService tap = TapService.instance;
            if (tap != null) {
                tap.swipe(w / 2, (int) (h * 0.75), w / 2, (int) (h * 0.35), 400);
                sleep(1600);
            }
            tapY = 1250;
        }
        articleTaps++;
        log("打开文章 #" + articleTaps + " y=" + tapY);
        tapXY(w / 2, tapY);
        sleep(3200);
        PixelDetector.Page p = page();
        // 仍未离开列表: 再试其它标题行
        if (p == PixelDetector.Page.SEARCH_RESULTS) {
            for (int y : new int[]{1250, 1710, 2100, 2570}) {
                if (y == tapY) continue;
                log("列表未变, 试 y=" + y);
                tapXY(w / 2, y);
                sleep(2800);
                p = page();
                if (p != PixelDetector.Page.SEARCH_RESULTS) break;
            }
        }
        // 误入名片/私信/联系人: 返回
        if (p == PixelDetector.Page.CARD_UNFOLLOWED
                || p == PixelDetector.Page.CARD_FOLLOWED
                || p == PixelDetector.Page.DM
                || p == PixelDetector.Page.HOME) {
            log("误入 " + p + ", BACK");
            back();
            sleep(1500);
            p = page();
        }
        log("文章打开后 page=" + p);
    }

    private void tryEnterCommentsFromArticle() {
        log("文章页, 尝试进评论区 page=" + page());
        int w = screenW(), h = screenH();
        TapService tap = TapService.instance;
        // 先下滑找留言区/头像，再试底部图标
        for (int s = 0; s < 4; s++) {
            if (tap == null) break;
            tap.swipe(w / 2, (int) (h * 0.72), w / 2, (int) (h * 0.28), 400);
            sleep(1800);
            PixelDetector d = shot();
            if (d != null && !d.findAvatars().isEmpty()) {
                log("下滑后发现头像, 进入评论逻辑");
                return;
            }
            PixelDetector.Page p = page();
            log("下滑#" + (s + 1) + " page=" + p);
            if (p == PixelDetector.Page.COMMENTS) return;
        }
        // 底部栏候选（避开悬浮窗，优先中下部）
        int[][] candidates = {
                {630, 2680}, {480, 2680}, {780, 2680},
                {630, 2720}, {350, 2680}, {900, 2680}
        };
        for (int[] c : candidates) {
            tapXY(c[0], c[1]);
            sleep(2000);
            PixelDetector d = shot();
            if (d != null && !d.findAvatars().isEmpty()) {
                log("底部入口发现头像 (" + c[0] + "," + c[1] + ")");
                return;
            }
            PixelDetector.Page p = page();
            log("评论入口尝试 (" + c[0] + "," + c[1] + ") -> " + p);
            if (p == PixelDetector.Page.COMMENTS) return;
            if (p == PixelDetector.Page.CARD_UNFOLLOWED
                    || p == PixelDetector.Page.CARD_FOLLOWED
                    || p == PixelDetector.Page.DM) {
                back();
                sleep(1000);
            }
        }
    }

    private void leaveCommentsToNextArticle() {
        log("评论区无更多头像, 返回列表找下一篇");
        // back 到文章, 再 back 到列表
        back();
        sleep(1200);
        back();
        sleep(1500);
        openNextArticle();
        if (page() == PixelDetector.Page.ARTICLE || page() == PixelDetector.Page.UNKNOWN) {
            tryEnterCommentsFromArticle();
        }
    }

    // ---------- 主循环 ----------
    @Override
    public void run() {
        running = true;
        log("全自动流程启动: 目标 " + target + " 人");
        openWechat();

        PixelDetector.Page p0 = page();
        log("启动时页面: " + p0);
        // 已在文章/评论/名片/私信: 直接进入处理，不要强行回搜索
        if (p0 == PixelDetector.Page.COMMENTS
                || p0 == PixelDetector.Page.CARD_UNFOLLOWED
                || p0 == PixelDetector.Page.CARD_FOLLOWED
                || p0 == PixelDetector.Page.DM
                || p0 == PixelDetector.Page.ARTICLE) {
            log("当前已在业务页, 跳过搜索导航");
        } else if (p0 == PixelDetector.Page.SEARCH_RESULTS
                || p0 == PixelDetector.Page.SEARCH_INPUT) {
            log("已在搜索页, 直接开文章");
            openArticleTabAndLatest();
            openNextArticle();
        } else {
            ensureWechatSearchPage();
            if (!typeSearchKeyword()) {
                log("搜索失败, 仍尝试继续");
            }
            openArticleTabAndLatest();
            openNextArticle();
        }

        int idleRounds = 0;
        int round = 0;
        while (running && done < target) {
            if (!ready()) {
                log("截屏/无障碍服务断开, 停止");
                break;
            }
            round++;
            periodicWechatGuard(round);

            // 前台不是微信时禁止点击，只恢复
            if (!onWechat()) {
                ensureWechatForeground(true);
                sleep(800);
                continue;
            }

            PixelDetector d = shot();
            if (d == null) {
                sleep(500);
                continue;
            }
            PixelDetector.Page p = d.pageKind();
            log("state=" + p + " done=" + done + "/" + target
                    + " follow=" + successFollow + " dm=" + successDm
                    + " fg=" + TapService.foregroundPackage);

            // 广告/分享/未知: 只 BACK，不乱点
            if (p == PixelDetector.Page.AD || p == PixelDetector.Page.UNKNOWN) {
                log("疑似广告/分享/未知页, BACK恢复");
                back();
                sleep(1000);
                ensureWechatForeground(true);
                continue;
            }

            switch (p) {
                case SEARCH_INPUT:
                case SEARCH_RESULTS:
                    idleRounds = 0;
                    openArticleTabAndLatest();
                    openNextArticle();
                    break;

                case ARTICLE:
                    idleRounds = 0;
                    tryEnterCommentsFromArticle();
                    break;

                case COMMENTS:
                    idleRounds = 0;
                    // 先尝试自动留言（互关/一起努力），再点头像关注
                    if (tryPostComment(d)) {
                        emptyCommentSwipes = 0;
                    } else if (!processComments(d)) {
                        emptyCommentSwipes++;
                        if (emptyCommentSwipes >= 3) {
                            emptyCommentSwipes = 0;
                            leaveCommentsToNextArticle();
                        } else {
                            swipeSoft();
                        }
                    } else {
                        emptyCommentSwipes = 0;
                    }
                    break;

                case CARD_UNFOLLOWED:
                    idleRounds = 0;
                    processCard(d);
                    break;

                case CARD_FOLLOWED:
                    idleRounds = 0;
                    processFollowedCard(d);
                    break;

                case DM:
                    idleRounds = 0;
                    processDm(d);
                    break;

                case HOME:
                    idleRounds = 0;
                    log("回到微信首页, 重新搜索");
                    ensureWechatSearchPage();
                    typeSearchKeyword();
                    openArticleTabAndLatest();
                    openNextArticle();
                    break;

                default:
                    idleRounds++;
                    log("未知页 #" + idleRounds + " page=" + p);
                    if (idleRounds > 4) {
                        log("连续异常, 强制回微信并重新导航");
                        ensureWechatForeground(true);
                        ensureWechatSearchPage();
                        typeSearchKeyword();
                        openArticleTabAndLatest();
                        openNextArticle();
                        idleRounds = 0;
                    } else {
                        back();
                    }
                    break;
            }
            sleep(300);
        }

        String summary = String.format(
                "完成: 处理%d人 关注%d 私信%d 留言%d",
                done, successFollow, successDm, successComment);
        log(summary);
        ui.post(() -> {
            if (listener != null) listener.onFinish(summary);
        });
    }

    /**
     * 评论区自动留言：点写留言/底部输入 → 注入互关文案 → 发送。
     * 文案从 commentMsgs 轮换。失败不阻断关注流程。
     */
    private boolean tryPostComment(PixelDetector d) {
        if (commentMsgs == null || commentMsgs.length == 0) return false;
        if (!ensureWechatForeground(true)) return false;

        String text = commentMsgs[commentIdx % commentMsgs.length];
        int w = screenW(), h = screenH();

        // 候选「写留言」入口：评论区底部输入条
        int[][] entries = {
                {630, 2680}, {630, 2720}, {480, 2700},
                {780, 2700}, {630, 2620}
        };
        boolean opened = false;
        for (int[] e : entries) {
            tapXY(e[0], e[1]);
            sleep(1800);
            PixelDetector d2 = shot();
            if (d2 == null) continue;
            PixelDetector.InputMode mode = d2.dmInputMode();
            if (mode == PixelDetector.InputMode.TEXT || d2.dmInputHasText()) {
                opened = true;
                log("评论输入已打开 (" + e[0] + "," + e[1] + ")");
                break;
            }
            PixelDetector.Page p = d2.pageKind();
            if (p == PixelDetector.Page.CARD_UNFOLLOWED
                    || p == PixelDetector.Page.CARD_FOLLOWED
                    || p == PixelDetector.Page.DM) {
                back();
                sleep(1000);
            }
        }
        if (!opened) {
            log("未打开评论输入, 尝试点头像关注");
            return false;
        }

        // 若在语音模式，先切文字
        PixelDetector d3 = shot();
        if (d3 != null && d3.dmInputMode() == PixelDetector.InputMode.VOICE) {
            PixelDetector.Btn icon = d3.findBottomLeftIcon();
            if (icon != null) {
                tap(icon);
                sleep(1500);
                d3 = shot();
            }
        }

        clearText();
        boolean typed = typeViaAdbKeyboard(text);
        if (!typed) {
            log("留言注入失败");
            return false;
        }
        log("已输入留言: " + text);

        PixelDetector d4 = shot();
        if (d4 == null) return false;
        PixelDetector.Btn send = d4.findSendButton();
        if (send == null) {
            // 评论区发送按钮可能与私信不同：试底部绿色区域
            tapXY((int) (w * 0.78), (int) (h * 0.96));
        } else {
            tap(send);
        }
        sleep(2000);
        successComment++;
        log("评论留言成功 #" + successComment + " (" + text + ")");
        progress();
        // 返回评论列表，准备继续点头像
        back();
        sleep(1200);
        return true;
    }

    private boolean processComments(PixelDetector d) {
        List<int[]> avatars = d.findAvatars();
        if (avatars.isEmpty()) {
            log("本屏无头像");
            return false;
        }
        log("发现候选头像 " + avatars.size() + " 个");
        for (int[] av : avatars) {
            int y = av[1];
            boolean seen = false;
            for (int p : processed) {
                if (Math.abs(y - p) < 70) {
                    seen = true;
                    break;
                }
            }
            if (seen) continue;
            processed.add(y);
            log(String.format("点头像 (%d,%d) (%d/%d)",
                    av[0], y, done + 1, target));
            tapXY(av[0], y);
            sleep(3500);
            PixelDetector.Page after = page();
            log("点头像后 page=" + after);
            if (after == PixelDetector.Page.CARD_UNFOLLOWED
                    || after == PixelDetector.Page.CARD_FOLLOWED
                    || after == PixelDetector.Page.DM) {
                return true;
            }
            // 误入聊天/小程序/广告: 立即返回
            if (after == PixelDetector.Page.UNKNOWN || after == PixelDetector.Page.HOME
                    || after == PixelDetector.Page.SEARCH_INPUT
                    || after == PixelDetector.Page.SEARCH_RESULTS) {
                log("误入 " + after + ", BACK");
                back();
                sleep(1500);
                continue;
            }
            // 仍在文章/评论: 可能点偏，试下一个
            log("名片未打开, 试下一个头像");
        }
        return false;
    }

    private void processCard(PixelDetector d) {
        PixelDetector.Btn gb = d.findGreenButton();
        if (gb == null) {
            log("名片无绿钮, 返回");
            back();
            return;
        }
        log("点关注 (" + gb.x + "," + gb.y + ")");
        tap(gb);
        sleep(2200);

        PixelDetector d2 = shot();
        if (d2 == null) {
            log("关注后截图失败");
            done++;
            progress();
            back();
            return;
        }

        PixelDetector.Btn gb2 = d2.findGreenButton();
        if (gb2 != null && Math.abs(gb2.x - gb.x) < 100 && Math.abs(gb2.y - gb.y) < 100) {
            log("关注未生效, 重试");
            tap(gb);
            sleep(2200);
            d2 = shot();
            if (d2 == null) return;
            gb2 = d2.findGreenButton();
            if (gb2 != null && Math.abs(gb2.x - gb.x) < 100 && Math.abs(gb2.y - gb.y) < 100) {
                log("关注失败, 记录并返回");
                done++;
                progress();
                back();
                return;
            }
        }
        successFollow++;
        log("关注成功 #" + successFollow);
        progress();

        PixelDetector.Btn dm = d2.findDmButton(gb.x, gb.y);
        if (dm == null) dm = d2.findDmButton();
        if (dm == null) {
            log("未找到私信按钮, 返回");
            done++;
            progress();
            back();
            return;
        }
        log("点私信 (" + dm.x + "," + dm.y + ")");
        tap(dm);
        sleep(2800);
        PixelDetector d3 = shot();
        if (d3 == null || d3.pageKind() != PixelDetector.Page.DM) {
            log("私信窗口未确认, 再点一次私信");
            tap(dm);
            sleep(2800);
            d3 = shot();
            if (d3 != null && d3.pageKind() != PixelDetector.Page.DM) {
                log("私信打开失败, 退回评论区");
                done++;
                progress();
                backToComments(3);
            }
        }
    }

    private void processFollowedCard(PixelDetector d) {
        PixelDetector.Btn dm = d.findDmButton();
        if (dm == null) {
            log("已关注名片找不到私信钮, 返回");
            back();
            return;
        }
        log("已关注名片点私信 (" + dm.x + "," + dm.y + ")");
        tap(dm);
        sleep(2800);
    }

    private void processDm(PixelDetector d) {
        PixelDetector.InputMode mode = d.dmInputMode();
        for (int i = 0; i < 3 && mode != PixelDetector.InputMode.TEXT; i++) {
            PixelDetector.Btn icon = d.findBottomLeftIcon();
            if (icon == null) break;
            log("切换输入模式 " + mode);
            tap(icon);
            sleep(1600);
            PixelDetector d2 = shot();
            if (d2 == null) return;
            d = d2;
            mode = d2.dmInputMode();
        }
        if (mode != PixelDetector.InputMode.TEXT) {
            log("输入模式异常: " + mode + ", dm=0");
            done++;
            progress();
            backToComments(3);
            throttleAfterPerson();
            return;
        }

        String msg = msgs[msgIdx % msgs.length];
        boolean typed = false;
        for (int i = 0; i < 2 && !typed; i++) {
            typed = typeText(msg);
            if (!typed) log("输入失败重试 " + (i + 1));
        }
        if (!typed) {
            log("输入失败, dm=0");
            done++;
            progress();
            backToComments(3);
            throttleAfterPerson();
            return;
        }
        msgIdx++;

        PixelDetector d3 = shot();
        if (d3 == null) {
            done++;
            progress();
            backToComments(3);
            return;
        }
        PixelDetector.Btn send = d3.findSendButton();
        if (send == null) {
            log("无发送按钮, dm=0");
            done++;
            progress();
            backToComments(3);
            throttleAfterPerson();
            return;
        }
        log("点发送 (" + send.x + "," + send.y + ")");
        tap(send);
        sleep(2200);
        PixelDetector d4 = shot();
        boolean sent = d4 != null && d4.sendButtonGone();
        if (sent) {
            successDm++;
            log("私信发送成功 #" + successDm);
        } else {
            log("发送未验证, dm=0");
        }
        done++;
        progress();
        backToComments(3);
        throttleAfterPerson();
    }

    private void throttleAfterPerson() {
        int pause = 4000 + rand.nextInt(5000);
        log("节流等待 " + (pause / 1000) + "s");
        sleep(pause);
        if (done % 25 == 0 && done > 0) {
            log("每25人休息120s");
            sleep(120000);
        }
    }

    private void swipeSoft() {
        TapService tap = TapService.instance;
        if (tap == null) return;
        int w = screenW(), h = screenH();
        tap.swipe(w / 2, (int) (h * 0.64), w / 2, (int) (h * 0.32), 450);
        sleep(2000);
    }
}
