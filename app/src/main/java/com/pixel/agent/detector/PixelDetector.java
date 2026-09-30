package com.pixel.agent.detector;

/**
 * 像素检测模块 —— 从 Python 版 run.py/drive.py 移植并对齐实测阈值。
 * 输入: RGBA int[] + 宽高。
 * 坐标: 内部用真实像素；接口同时暴露归一化换算。
 *
 * Android ImageReader RGBA_8888 little-endian:
 *   int = R | (G<<8) | (B<<16) | (A<<24)
 */
public final class PixelDetector {
    private final int[] px;
    private final int w, h;

    public PixelDetector(int[] pixels, int width, int height) {
        this.px = pixels;
        this.w = width;
        this.h = height;
    }

    public int width() { return w; }
    public int height() { return h; }

    private int r(int i) { return px[i] & 0xFF; }
    private int g(int i) { return (px[i] >> 8) & 0xFF; }
    private int b(int i) { return (px[i] >> 16) & 0xFF; }

    private boolean isGreen(int i) {
        int r = r(i), g = g(i), b = b(i);
        // 微信 #07C160 ≈ (7,193,96)，兼容轻微色偏
        return g > 150 && r < 140 && b < 140 && (g - r) > 50 && (g - b) > 20;
    }

    private boolean isGray(int i) {
        int r = r(i), g = g(i), b = b(i);
        return Math.abs(r - g) < 12 && Math.abs(g - b) < 12 && r > 200 && r < 250;
    }

    private boolean isWhite(int i) {
        return r(i) > 250 && g(i) > 250 && b(i) > 250;
    }

    /** 微信评论区常见浅底 #F7F7F7 / #F2F2F2 */
    private boolean isLightBg(int i) {
        return r(i) >= 235 && g(i) >= 235 && b(i) >= 235;
    }

    private boolean isNonWhite(int i) {
        int r = r(i), g = g(i), b = b(i);
        return Math.abs(r - 255) > 18 || Math.abs(g - 255) > 18 || Math.abs(b - 255) > 18;
    }

    private boolean isColored(int i) {
        int r = r(i), g = g(i), b = b(i);
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return (mx - mn) > 30;
    }

    // ---------- 页面状态机 ----------
    public enum Page {
        COMMENTS,
        CARD_UNFOLLOWED,
        CARD_FOLLOWED,
        DM,
        ARTICLE,
        SEARCH_INPUT,
        SEARCH_RESULTS,
        HOME,
        AD,
        UNKNOWN
    }

    private int countGray(int y1, int y2) {
        int c = 0;
        for (int y = y1; y < y2 && y < h; y++)
            for (int x = 0; x < w; x++)
                if (isGray(y * w + x)) c++;
        return c;
    }

    private int countWhite(int y1, int y2) {
        int c = 0;
        for (int y = y1; y < y2 && y < h; y++)
            for (int x = 0; x < w; x++)
                if (isWhite(y * w + x) || isLightBg(y * w + x)) c++;
        return c;
    }

    private int rightColorful(int y1, int y2) {
        int x1 = (int) (w * 0.75), x2 = (int) (w * 0.98);
        int c = 0;
        for (int y = y1; y < y2 && y < h; y++)
            for (int x = x1; x < x2; x++)
                if (isColored(y * w + x)) c++;
        return c;
    }

    public Page pageKind() {
        int yMid1 = (int) (h * 0.43), yMid2 = (int) (h * 0.53);
        int area = (yMid2 - yMid1) * w;

        // 私信窗: 中部大面积灰 + 右侧无彩色封面 + 底部输入栏特征
        if (countGray(yMid1, yMid2) > area * 0.75
                && rightColorful((int) (h * 0.18), (int) (h * 0.53)) < 5000
                && dmInputMode() != InputMode.NONE) {
            return Page.DM;
        }

        // 公众号名片未关注: 大绿钮（排除底部栏小绿图标）
        if (findGreenButton() != null) return Page.CARD_UNFOLLOWED;

        // 已关注名片: 双灰钮带
        int[][] runs = grayButtonRuns();
        if (runs.length >= 2) {
            int widthsOk = 0;
            for (int[] run : runs) if (run[1] - run[0] > (int) (w * 0.18)) widthsOk++;
            if (widthsOk >= 2) return Page.CARD_FOLLOWED;
        }

        java.util.List<int[]> avatars = findAvatars();

        // 评论区: 左侧头像列 + 中部浅底 + 非搜索条
        boolean searchBar = looksLikeSearchBar();
        if (!avatars.isEmpty() && !searchBar
                && countWhite(yMid1, yMid2) > area * 0.35) {
            return Page.COMMENTS;
        }
        if (!avatars.isEmpty() && !searchBar && leftBandHasContent()
                && !hasTabRow()) {
            return Page.COMMENTS;
        }
        if (countWhite(yMid1, yMid2) > area * 0.50
                && leftBandHasContent() && !searchBar) {
            return Page.COMMENTS;
        }

        // 搜索结果页: 顶部搜索条 + tab 行 + 下方列表
        if (looksLikeSearchBar() && hasTabRow() && hasListContentBelow()) {
            return Page.SEARCH_RESULTS;
        }
        // 搜索输入页: 顶部搜索条 + 下方建议/键盘（无明显 tab）
        if (looksLikeSearchBar() && !hasBottomActionBar()) {
            return Page.SEARCH_INPUT;
        }

        // 评论区: 左侧头像已处理过上面；再看浅底+左带
        if (!avatars.isEmpty()) return Page.COMMENTS;
        if (countWhite(yMid1, yMid2) > area * 0.50 && leftBandHasContent()) {
            return Page.COMMENTS;
        }

        // 文章页: 中部有正文 + 底部操作栏
        if (hasBottomActionBar() && hasBodyText()) {
            return Page.ARTICLE;
        }

        // 微信首页: 顶部深色状态栏 + 中部列表（无搜索框大浅带）
        if (topDark() && countWhite(yMid1, yMid2) < area * 0.45) {
            return Page.HOME;
        }

        return Page.UNKNOWN;
    }

    /** 搜索结果列表区: 中部有较多深色文字行 */
    private boolean hasListContentBelow() {
        int y1 = (int) (h * 0.22), y2 = (int) (h * 0.75);
        int dark = 0, total = 0;
        for (int y = y1; y < y2; y += 4)
            for (int x = 0; x < w; x += 4) {
                total++;
                if (r(y * w + x) < 120) dark++;
            }
        return total > 0 && dark > total * 0.02;
    }

    /** 顶部搜索条: 要求 y约6%-10% 大面积浅灰/白，且横向跨度宽（输入框，不是普通顶栏） */
    private boolean looksLikeSearchBar() {
        int y1 = (int) (h * 0.060), y2 = (int) (h * 0.100);
        int light = 0, total = 0;
        int x1 = (int) (w * 0.04), x2 = (int) (w * 0.96);
        for (int y = y1; y < y2 && y < h; y++) {
            for (int x = x1; x < x2; x += 2) {
                total++;
                int i = y * w + x;
                int r = r(i), g = g(i), b = b(i);
                if (r >= 228 && g >= 228 && b >= 228 && Math.abs(r - g) < 15 && Math.abs(g - b) < 15) {
                    light++;
                }
            }
        }
        // 输入框几乎全是浅色；首页顶栏下方列表区不是
        return total > 0 && light > total * 0.70;
    }

    /** tab 行: y约13.5%-15.5% 有中等密度内容（搜索结果页） */
    private boolean hasTabRow() {
        int y1 = (int) (h * 0.135), y2 = (int) (h * 0.160);
        int dark = 0, total = 0;
        for (int y = y1; y < y2 && y < h; y++)
            for (int x = 0; x < w; x += 2) {
                total++;
                if (r(y * w + x) < 160) dark++;
            }
        // tab 文字稀疏，密度不宜过高
        return total > 0 && dark > total * 0.015 && dark < total * 0.20;
    }

    /** 左侧头像带是否有内容 */
    private boolean leftBandHasContent() {
        int x1 = (int) (w * 0.044), x2 = (int) (w * 0.131);
        int y1 = (int) (h * 0.20), y2 = (int) (h * 0.85);
        int c = 0;
        for (int y = y1; y < y2 && y < h; y += 2)
            for (int x = x1; x < x2; x += 2)
                if (isNonWhite(y * w + x)) c++;
        return c > (y2 - y1) * (x2 - x1) / 8;
    }

    /** 文章底部操作栏: y>93% 有非纯白内容 */
    private boolean hasBottomActionBar() {
        int y1 = (int) (h * 0.93), y2 = h;
        int c = 0, total = 0;
        for (int y = y1; y < y2; y++)
            for (int x = 0; x < w; x += 3) {
                total++;
                if (!isWhite(y * w + x) && !isLightBg(y * w + x)) c++;
            }
        return total > 0 && c > total * 0.03;
    }

    /** 正文区: 中部有大量非白/非浅灰文字像素 */
    private boolean hasBodyText() {
        int y1 = (int) (h * 0.20), y2 = (int) (h * 0.80);
        int dark = 0, total = 0;
        for (int y = y1; y < y2; y += 3)
            for (int x = 0; x < w; x += 4) {
                total++;
                int i = y * w + x;
                if (r(i) < 120 && g(i) < 120 && b(i) < 120) dark++;
            }
        return total > 0 && dark > total * 0.015;
    }

    /** 顶部是否像深色工具栏 (y 0-8%) */
    private boolean topDark() {
        int y2 = (int) (h * 0.08);
        int c = 0;
        for (int y = 0; y < y2; y++)
            for (int x = 0; x < w; x += 4) {
                int i = y * w + x;
                if (r(i) < 80 && g(i) < 80 && b(i) < 80) c++;
            }
        return c > (y2 * w / 4) * 0.55;
    }

    // ---------- 绿色关注按钮 ----------
    public static class Btn { public int x, y; }

    /**
     * 找名片页大块绿色关注按钮。
     * 排除右上角悬浮窗等 overlay：中心 x 不得贴右边缘，且宽度/面积要像名片大按钮。
     */
    public Btn findGreenButton() {
        int ylo = (int) (h * 0.16), yhi = (int) (h * 0.70);
        // 悬浮窗/顶栏绿钮常在 x>75% 屏宽，排除
        int xMax = (int) (w * 0.88);
        int[] rowGreen = new int[h];
        for (int y = ylo; y < yhi && y < h; y++) {
            int c = 0;
            for (int x = 0; x < xMax; x++)
                if (isGreen(y * w + x)) c++;
            rowGreen[y] = c;
        }
        int start = -1;
        for (int y = ylo; y <= yhi && y < h + 1; y++) {
            boolean on = y < yhi && y < h && rowGreen[y] > 150;
            if (on && start < 0) start = y;
            else if (!on && start >= 0) {
                if (y - start >= 80) {
                    Btn btn = greenSegCenter(start, y, xMax);
                    if (btn != null) return btn;
                }
                start = -1;
            }
        }
        return null;
    }

    private Btn greenSegCenter(int y1, int y2, int xMax) {
        int x1 = -1, x2 = -1, count = 0;
        for (int y = y1; y < y2 && y < h; y++) {
            for (int x = 0; x < xMax && x < w; x++) {
                if (isGreen(y * w + x)) {
                    if (x1 < 0 || x < x1) x1 = x;
                    if (x > x2) x2 = x;
                    count++;
                }
            }
        }
        if (count < 15000 || x2 - x1 < 200) return null;
        int cx = (x1 + x2) / 2;
        // 中心不能贴最右（悬浮窗/顶栏）
        if (cx > w * 0.80) return null;
        Btn b = new Btn();
        b.x = cx;
        b.y = (y1 + y2) / 2;
        return b;
    }

    // ---------- 灰色按钮带 ----------
    private int[][] grayButtonRuns() {
        return grayRunsInBand((int) (h * 0.39), (int) (h * 0.64), (int) (w * 0.03), (int) (w * 0.96));
    }

    private int[][] grayRunsInBand(int y1, int y2, int xlo, int xhi) {
        y2 = Math.min(y2, h);
        xhi = Math.min(xhi, w);
        if (xlo >= xhi || y1 >= y2) return new int[0][];
        int[] cols = new int[xhi - xlo];
        for (int y = y1; y < y2; y++)
            for (int x = xlo; x < xhi; x++)
                if (isGray(y * w + x)) cols[x - xlo]++;
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        int start = -1;
        for (int i = 0; i <= cols.length; i++) {
            boolean on = i < cols.length && cols[i] > 35;
            if (on && start < 0) start = i;
            else if (!on && start >= 0) {
                if (i - start > 200 && i - start < 700) runs.add(new int[]{xlo + start, xlo + i});
                start = -1;
            }
        }
        return runs.toArray(new int[0][]);
    }

    /** 已关注名片上找私信按钮（右侧灰钮）。参考坐标可选。 */
    public Btn findDmButton() {
        // 全带扫描: 取最右侧宽灰钮
        int[][] runs = grayButtonRuns();
        if (runs.length == 0) return grayCenter(runs.length == 0 ? -1 : -1);
        int[] last = runs[runs.length - 1];
        if (runs.length == 1 && last[1] - last[0] > (int) (w * 0.44)) {
            int cx = last[0] + (int) ((last[1] - last[0]) * 0.72);
            return grayCenterAt(cx, (int) (h * 0.39), (int) (h * 0.64));
        }
        int cx = (last[0] + last[1]) / 2;
        return grayCenterAt(cx, (int) (h * 0.39), (int) (h * 0.64));
    }

    public Btn findDmButton(int gx, int gy) {
        if (gx <= 0 || gy <= 0 || gx >= w || gy >= h) {
            return findDmButton();
        }
        int ylo = Math.max(0, gy - (int) (h * 0.025));
        int yhi = Math.min(h, gy + (int) (h * 0.025));
        int xlo = Math.max(0, gx - (int) (w * 0.05));
        int xhi = Math.min(w, gx + (int) (w * 0.75));
        int[][] runs = grayRunsInBand(ylo, yhi, xlo, xhi);
        if (runs.length == 0) {
            // 参考带扫不到时退回全带
            Btn full = findDmButton();
            if (full != null && Math.abs(full.y - gy) < h * 0.08) return full;
            return full;
        }
        int cx;
        if (runs.length == 1 && runs[0][1] - runs[0][0] > (int) (w * 0.44)) {
            cx = runs[0][0] + (int) ((runs[0][1] - runs[0][0]) * 0.72);
        } else {
            cx = (runs[runs.length - 1][0] + runs[runs.length - 1][1]) / 2;
        }
        return grayCenterAt(cx, ylo, yhi);
    }

    private Btn grayCenter(int unused) { return null; }

    private Btn grayCenterAt(int cx, int ylo, int yhi) {
        long sy = 0;
        int n = 0;
        for (int y = ylo; y < yhi && y < h; y++) {
            for (int dx = -20; dx <= 20; dx++) {
                int x = cx + dx;
                if (x >= 0 && x < w && isGray(y * w + x)) {
                    sy += y;
                    n++;
                }
            }
        }
        if (n == 0) return null;
        Btn b = new Btn();
        b.x = cx;
        b.y = (int) (sy / n);
        return b;
    }

    // ---------- 评论头像检测 ----------
    /** 检测评论区左侧头像列（彩色圆，高约 2.8%-4.2%，行宽≤8.6%屏宽）。 */
    public java.util.List<int[]> findAvatars() {
        int x1 = (int) (w * 0.044), x2 = (int) (w * 0.131);
        int bandW = x2 - x1;
        int yStart = (int) (h * 0.14), yEnd = (int) (h * 0.93);
        int[] coloredRows = new int[h];
        for (int y = yStart; y < yEnd && y < h; y++) {
            int c = 0;
            for (int x = x1; x < x2; x++)
                if (isColored(y * w + x)) c++;
            coloredRows[y] = c;
        }
        double[] smooth = new double[h];
        for (int y = yStart; y < yEnd; y++) {
            double s = 0;
            for (int k = -2; k <= 2; k++) {
                int yy = Math.min(Math.max(y + k, yStart), yEnd - 1);
                s += coloredRows[yy];
            }
            smooth[y] = s / 5.0;
        }
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int start = -1;
        double thresh = 6.0; // 彩色像素 >6
        for (int y = yStart; y <= yEnd; y++) {
            boolean on = y < yEnd && smooth[y] > thresh;
            if (on && start < 0) start = y;
            else if (!on && start >= 0) {
                emitAvatar(out, start, y, x1, x2);
                start = -1;
            }
        }
        if (start >= 0) emitAvatar(out, start, yEnd, x1, x2);
        return out;
    }

    private void emitAvatar(java.util.List<int[]> out, int y1, int y2, int x1, int x2) {
        int hgt = y2 - y1;
        double hMin = h * 0.026, hMax = h * 0.042;
        if (hgt < hMin || hgt > hMax) return;
        int mid = (y1 + y2) / 2;
        int rowCount = 0;
        for (int y = Math.max(y1, mid - 5); y <= Math.min(y2 - 1, mid + 5); y++) {
            int c = 0;
            for (int x = x1; x < x2; x++)
                if (isColored(y * w + x)) c++;
            rowCount = Math.max(rowCount, c);
        }
        // 行宽: 评论头像约 70-108px；过宽多为广告/封面
        if (rowCount > (int) (w * 0.086)) return;
        // 右侧应有评论文字（深色像素），排除贴边广告图
        int textX1 = x2 + (int) (w * 0.02);
        int textX2 = Math.min(w, x2 + (int) (w * 0.55));
        int textDark = 0;
        for (int y = y1; y < y2; y++)
            for (int x = textX1; x < textX2; x++) {
                int i = y * w + x;
                if (r(i) < 120 && g(i) < 120 && b(i) < 120) textDark++;
            }
        if (textDark < 80) return;
        long sx = 0;
        int n = 0;
        for (int y = y1; y < y2; y++)
            for (int x = x1; x < x2; x++)
                if (isColored(y * w + x)) {
                    sx += x;
                    n++;
                }
        if (n < 25) return;
        out.add(new int[]{(int) (sx / n), (y1 + y2) / 2});
    }

    // ---------- 私信输入行模式 ----------
    public enum InputMode { TEXT, VOICE, NONE }

    public InputMode dmInputMode() {
        int y1 = (int) (h * 0.928), y2 = Math.min(h, (int) (h * 0.996));
        int x1 = (int) (w * 0.12), x2 = (int) (w * 0.87);
        int total = 0, mid = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int i = y * w + x;
                int r = r(i), g = g(i), b = b(i);
                boolean dark = r < 160 && g < 160 && b < 160
                        && Math.abs(r - g) < 30 && Math.abs(g - b) < 30;
                if (dark) {
                    total++;
                    if (x - x1 > (x2 - x1) * 0.29 && x - x1 < (x2 - x1) * 0.58) mid++;
                }
            }
        }
        if (total < 800) return InputMode.NONE;
        if (mid > 1500) return InputMode.VOICE;
        return InputMode.TEXT;
    }

    public Btn findBottomLeftIcon() {
        int y1 = (int) (h * 0.928), y2 = h;
        int x1 = 15, x2 = (int) (w * 0.16);
        if (x1 >= x2 || y1 >= y2) return null;
        int[] cols = new int[x2 - x1];
        for (int y = y1; y < y2; y++)
            for (int x = x1; x < x2; x++) {
                int i = y * w + x;
                if (r(i) < 175 && g(i) < 175 && b(i) < 175) cols[x - x1]++;
            }
        int start = -1;
        for (int i = 0; i < cols.length; i++) {
            if (cols[i] > 4) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        int end = start;
        for (int i = start; i < cols.length; i++) {
            if (i - end <= 12 && cols[i] > 0) end = i;
            else if (i - end > 12) break;
        }
        if (end - start < 15) return null;
        int cx = x1 + (start + end) / 2;
        long sy = 0;
        int n = 0;
        for (int y = y1; y < y2; y++)
            for (int x = x1 + start; x <= x1 + end; x++) {
                int i = y * w + x;
                if (r(i) < 175 && g(i) < 175 && b(i) < 175) {
                    sy += y;
                    n++;
                }
            }
        if (n == 0) return null;
        Btn b = new Btn();
        b.x = cx;
        b.y = (int) (sy / n);
        return b;
    }

    // ---------- 发送按钮 ----------
    public Btn findSendButton() {
        int y1 = (int) (h * 0.89), y2 = h;
        int x1 = (int) (w * 0.55), x2 = (int) (w * 0.99);
        long sx = 0, sy = 0;
        int n = 0;
        for (int y = y1; y < y2; y++)
            for (int x = x1; x < x2; x++)
                if (isGreen(y * w + x)) {
                    sx += x;
                    sy += y;
                    n++;
                }
        if (n < 400) return null;
        Btn b = new Btn();
        b.x = (int) (sx / n);
        b.y = (int) (sy / n);
        return b;
    }

    public boolean sendButtonGone() {
        return findSendButton() == null;
    }

    public boolean dmInputHasText() {
        int y1 = (int) (h * 0.928), y2 = Math.min(h, (int) (h * 0.996));
        int x1 = (int) (w * 0.16), x2 = (int) (w * 0.63);
        int c = 0;
        for (int y = y1; y < y2; y++)
            for (int x = x1; x < x2; x++) {
                int i = y * w + x;
                if (r(i) < 100 && g(i) < 100 && b(i) < 100) c++;
            }
        return c > 3000;
    }
}
