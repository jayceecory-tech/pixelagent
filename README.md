# PixelAgent

微信公众号互关自动化 APK（调试中）。

目标：在手机上自动完成  
**搜互关帖 → 打开文章评论区 → 点头像 → 公众号名片关注 → 私信求回关**

不读取微信无障碍树（微信会清空），采用：

- **MediaProjection** 截屏 + **纯像素检测**识别页面/按钮
- **无障碍手势** `dispatchGesture` 点击/滑动
- **ADB Keyboard** 广播注入中文（设备默认输入法需为 AdbIME）
- **悬浮窗控制**：常驻微信上层，点「开始/停止」，避免 vivo 切后台后流程被冻住

> 仅供个人调试与学习。自动化关注/私信可能触发平台风控，请自行控制频率与合规风险。

---

## 当前状态

| 项目 | 状态 |
|------|------|
| 版本 | **0.13**（`versionCode=13`） |
| 截屏引擎 | 已修 Android 14 `MediaProjection.Callback` 必需注册 |
| 像素缓冲 | 已修 `rowStride` 导致的 `ArrayIndexOutOfBounds` |
| 中文输入 | 优先 `ADB_INPUT_B64`；已去掉无焦点时的 `ADB_CLEAR_TEXT`（会弄崩 AdbIME） |
| 页面识别 | 评论/名片/私信/搜索/文章/首页 分类；搜索条与 DM 判定已收紧 |
| 悬浮窗 | **0.12+**：左上角蓝色控件，避免误检关注绿钮/遮挡点击 |
| 自动化完整链路 | **调试中**（搜文→评论→关注→私信 坐标与状态机仍在真机校准） |

调试机：vivo，物理分辨率 **1260×2800**。坐标按该分辨率实测。

---

## 相关 APK

| 文件 | 说明 |
|------|------|
| `app-debug-0.13.apk` | PixelAgent 当前调试包 |
| `app-debug-0.2.apk` … `0.12.apk` | 历史调试包 |
| `MinisApp-1.14-arm64-v8a.apk` | 配套安装包（包名 `com.openminis.app`） |

### 0.9–0.13 调整摘要

| 版本 | 说明 |
|------|------|
| 0.9–0.12 | 悬浮窗改色/左上角；绿钮检测排除右侧 overlay；文章/评论页不再强行回搜索 |
| 0.13 | 评论头像要求右侧有文字；误入聊天/小程序立即 BACK |

> 调试机：vivo 1260×2800。中文注入需临时用 ADB Keyboard。

## 仓库内文件

```
pixelagent/
├── README.md
├── app-debug-0.13.apk          # 当前调试包
├── app-debug-0.2.apk … 0.12.apk
├── app/
│   └── src/main/java/com/pixel/agent/
│       ├── MainActivity.java
│       ├── TestRunner.java
│       ├── detector/PixelDetector.java
│       ├── engine/CaptureService.java
│       ├── engine/TapService.java
│       ├── flow/FollowFlow.java
│       └── ui/FloatingWindowService.java
```

---

## 架构（简图）

```text
┌─────────────────┐     截屏帧      ┌──────────────────┐
│  MainActivity   │ ──────────────► │  CaptureService  │
│  悬浮窗 开始/停止 │                 │  MediaProjection │
└────────┬────────┘                 └────────┬─────────┘
         │ FollowFlow                        │ ARGB pixels
         ▼                                   ▼
┌─────────────────┐    坐标点击      ┌──────────────────┐
│   FollowFlow    │ ──────────────► │    TapService    │
│  搜索→评论→关注  │                 │  dispatchGesture │
│  →私信→下一篇    │                 └──────────────────┘
└────────┬────────┘
         │ ADB_INPUT_B64
         ▼
┌─────────────────┐
│  ADB Keyboard   │  中文注入（需默认 IME）
└─────────────────┘
```

### FollowFlow 状态机（目标）

```text
HOME → 搜索"公众号互关" → SEARCH_RESULTS
     → 文章 tab → 最新 → ARTICLE
     → 点评论入口 → COMMENTS
     → 点头像 → CARD_UNFOLLOWED / CARD_FOLLOWED
     → 点关注 → 点私信 → DM
     → ADBKeyboard 输入 → 发送 → 返回 COMMENTS
     → 无更多头像 → 返回列表 → 下一篇 ARTICLE
```

---

## 使用步骤（真机）

1. **安装 APK**  
   安装 `app-debug-0.8.apk`（或自行 `assembleDebug`）。

2. **启动截屏服务**  
   打开 PixelAgent →「1. 启动截屏服务」  
   系统弹窗务必选 **「共享整个屏幕」**。  
   若有 **「隐私应用保护」**，务必 **关闭**（否则录不到微信）。

3. **只开启 PixelAgent 无障碍**  
   无障碍设置 → 已下载的服务 → **只打开 PixelAgent**。  
   **不要打开 TalkBack**。vivo 会弹「请求监控和控制设备」，点 **允许**。

4. **显示悬浮窗**  
   允许「显示在其他应用上层」。  
   主界面点「3. 显示悬浮窗」或「4. 弹出悬浮窗并打开微信」。

5. **输入法**  
   系统默认输入法切到 **ADB Keyboard**（`com.android.adbkeyboard/.AdbIME`）。

6. **微信前台点悬浮窗「开始」**  
   流程会尝试自动搜索互关帖并处理评论。  
   主界面「0. 测试截屏+检测」可用 logcat 看 `pageKind` / 头像数 / 绿钮。

### logcat

```bash
adb logcat -s PixelAgent.Flow PixelAgent.Test PixelAgent.Capture PixelAgent.Tap PixelAgent.Float
```

### 构建

```bash
# Android SDK 34 + JDK 17
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

---

## 主要修复记录（调试阶段）

| 版本 | 问题 | 修复 |
|------|------|------|
| 0.3 | Android 14 截屏崩溃 | `createVirtualDisplay` 前注册 `MediaProjection.Callback` |
| 0.4 | 截屏缓冲越界 | 按 `rowStride` 逐行读取像素 |
| 0.5+ | 中文私信失败 | ADB Keyboard 广播；去掉无焦点 `ADB_CLEAR_TEXT` |
| 0.6 | 误判搜索/私信页 | 收紧 `looksLikeSearchBar` / DM 需底部输入栏 |
| 0.7 | 点头像后状态乱跳 | 校验是否进入名片页再计数 |
| **0.8** | vivo 切微信后流程难控 | **悬浮窗** 常驻控制，微信前台点开始 |

---

## 已知限制 / 调试中

1. **自动搜文→评论区** 真机校准中：坐标对 1260×2800 有效，其它分辨率需改 `FollowFlow` / `PixelDetector`。
2. **vivo 无障碍**：应用切后台后手势可能断开；悬浮窗与电池白名单可缓解，但非绝对。
3. **页面识别阈值**：微信版本/UI 变化会导致 `pageKind` 误判，需结合截图调阈值。
4. **风控**：连续关注/私信可能被限制；代码里有 4–9s 随机间隔与节流，仍需人工控制。
5. **个人微信名片 / 视频号**：目标场景是公众号互关帖；误点个人号时流程应 BACK 跳过（调试中）。

---

## 环境要求

- Android 10+（minSdk 29），测试机 vivo + 1260×2800
- 已安装 **ADB Keyboard**
- 已开启无障碍 **PixelAgent**
- 已授予 **屏幕录制（整个屏幕）** 与 **悬浮窗**
- 编译：JDK 17 + Android SDK 34

---

## 许可与合规

本仓库代码仅供学习与个人调试。  
请遵守微信平台规则与当地法律；不得用于骚扰、批量营销或其它违规用途。
