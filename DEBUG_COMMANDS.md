# PixelAgent 调试命令备忘

真机（vivo，1260×2800）上调试时常用的批量 ADB 命令。  
可直接执行：`scripts/debug-adb.sh`，也可按下面手工粘贴。

设备序列号示例：`SER=10AF3J2PPA0021N`

## 1. 安装最新 APK

```bash
adb -s $SER push app-debug-0.17.apk /data/local/tmp/agent.apk
adb -s $SER shell pm install -r -t /data/local/tmp/agent.apk
# vivo 会弹「安全守护」确认框：
adb -s $SER shell input tap 380 2449   # 勾选「已了解风险」
sleep 2
adb -s $SER shell input tap 630 2621   # 继续安装
adb -s $SER shell dumpsys package com.pixel.agent | grep -E 'versionCode|versionName'
```

## 2. 环境准备（无障碍 / 输入法）

```bash
# 只开 PixelAgent + Minis，不要开 TalkBack
adb -s $SER shell settings put secure enabled_accessibility_services \
  com.pixel.agent/com.pixel.agent.engine.TapService:com.openminis.app/com.openminis.app.accessibility.MinisAccessibilityService
adb -s $SER shell settings put secure accessibility_enabled 1
# 自动化中文注入需要 ADB Keyboard
adb -s $SER shell settings put secure default_input_method com.android.adbkeyboard/.AdbIME
# 降低 vivo 后台冻结
adb -s $SER shell dumpsys deviceidle whitelist +com.pixel.agent
```

## 3. 启动截屏服务（处理授权框）

```bash
adb -s $SER shell am start -n com.pixel.agent/.MainActivity
sleep 2
adb -s $SER shell input tap 630 690    # 「1. 启动截屏服务」
sleep 3
# 系统录屏弹窗：
adb -s $SER shell input tap 801 2260   # 打开「共享范围」下拉
sleep 1.2
adb -s $SER shell input tap 623 2036   # 选择「共享整个屏幕」
sleep 1
adb -s $SER shell input tap 630 2662   # 继续
sleep 1.8
adb -s $SER shell input tap 1046 2516  # 关闭「隐私应用保护」（否则录不到微信）
sleep 0.6
adb -s $SER shell input tap 630 2662   # 开始
sleep 3
adb -s $SER shell am start -n com.pixel.agent/.MainActivity
# 状态栏应为：截屏服务已就绪 ✓
```

## 4. 悬浮窗

```bash
adb -s $SER shell input tap 630 1026   # 「3. 显示悬浮窗」
# 悬浮窗帧位置：
adb -s $SER shell dumpsys window windows 2>/dev/null | grep -o 'type=2038 com.pixel.agent, frame=\[Rect([^]]*)\]'
# 蓝色「开始」按钮约在面板左侧：实测常见 209,385
adb -s $SER exec-out screencap -p > /tmp/px_float.png
```

## 5. 启动互关流程

```bash
# 点悬浮窗开始（坐标以截图为准，0.17 约 209,385）
adb -s $SER shell input tap 209 385
# 或点主界面：4. 弹出悬浮窗并打开微信
adb -s $SER shell input tap 630 1498
```

## 6. 日志

```bash
# 最近日志
adb -s $SER logcat -d | grep PixelAgent | tail -80
# 实时跟踪流程
adb -s $SER logcat -s PixelAgent.Flow PixelAgent.Float PixelAgent.Capture PixelAgent.Tap
# 关键字段
#   fg=com.tencent.mm          → 微信前台
#   前台不是微信: xxx, 拉回微信  → 误触恢复
#   点头像后 page=CARD_UNFOLLOWED → 进入公众号名片
```

## 7. 恢复键盘（调试结束后）

```bash
# 应用内按钮：5. 恢复键盘
# 或 ADB：
adb -s $SER shell settings put secure default_input_method \
  com.sohu.inputmethod.sogou.vivo/.SogouIME
adb -s $SER shell settings get secure default_input_method
```

## 8. 查看前台 / 无障碍

```bash
adb -s $SER shell dumpsys accessibility | grep -E 'Enabled services|Bound services' | head -6
adb -s $SER shell dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity' | head -5
adb -s $SER shell dumpsys window | grep mCurrentFocus
```

## 9. 安装 MinisApp（可选）

```bash
adb -s $SER install -r -t MinisApp-1.14-arm64-v8a.apk
# vivo 确认框同样：380,2449 → 630,2621
```

## 一键脚本

```bash
export SER=10AF3J2PPA0021N
./scripts/debug-adb.sh install ./app-debug-0.17.apk
./scripts/debug-adb.sh setup
./scripts/debug-adb.sh capture
./scripts/debug-adb.sh float
./scripts/debug-adb.sh start-flow
./scripts/debug-adb.sh logs-follow
./scripts/debug-adb.sh restore-ime
```
