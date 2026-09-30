#!/usr/bin/env bash
# PixelAgent 真机调试批量命令（vivo 1260x2800）
# 用法示例:
#   export SER=10AF3J2PPA0021N
#   ./scripts/debug-adb.sh install ./app-debug-0.17.apk
#   ./scripts/debug-adb.sh setup
#   ./scripts/debug-adb.sh capture
#   ./scripts/debug-adb.sh float
#   ./scripts/debug-adb.sh start-flow
#   ./scripts/debug-adb.sh logs
#   ./scripts/debug-adb.sh restore-ime
set -euo pipefail

SER="${SER:-10AF3J2PPA0021N}"
ADB=(adb -s "$SER")
APK="${1:-}"
CMD="${2:-help}"
APK_PATH="${APK:-./app-debug-0.17.apk}"

a() { "${ADB[@]}" "$@"; }

need_device() {
  local out
  out=$(adb devices | awk 'NR>1 && $2=="device"{print $1}')
  if [[ -z "$out" ]]; then
    echo "未检测到设备，请检查 USB / adb devices"
    exit 1
  fi
  SER="$out"
  ADB=(adb -s "$SER")
  echo "device=$SER"
}

installer_risk_dialog() {
  # vivo 安装确认：勾选风险 → 继续安装（坐标按实测）
  echo "处理 vivo 安装确认对话框..."
  sleep 1
  local f="/tmp/px_installer.xml"
  a shell "uiautomator dump /sdcard/px_installer.xml" >/dev/null || true
  a pull /sdcard/px_installer.xml "$f" >/dev/null || true
  if grep -q "packageinstaller" "$f" 2>/dev/null; then
    a shell "input tap 380 2449" || true
    sleep 2
    a shell "input tap 630 2621" || true
    sleep 4
  fi
  a shell "dumpsys package com.pixel.agent | grep -E 'versionCode|versionName'"
}

capture_grant_dialog() {
  # MediaProjection：选「共享整个屏幕」→ 继续 → 关隐私应用保护 → 开始
  echo "处理截屏授权对话框..."
  a shell "input tap 801 2260" || true   # 打开范围下拉
  sleep 1.2
  a shell "input tap 623 2036" || true    # 共享整个屏幕
  sleep 1
  a shell "input tap 630 2662" || true    # 继续
  sleep 1.8
  # 隐私应用保护开关（若出现）
  a shell "input tap 1046 2516" || true
  sleep 0.6
  a shell "input tap 630 2662" || true    # 开始
  sleep 3
  a shell "am start -n com.pixel.agent/.MainActivity" || true
  sleep 2
  local f="/tmp/px_cap.xml"
  a shell "uiautomator dump /sdcard/px_cap.xml" >/dev/null || true
  a pull /sdcard/px_cap.xml "$f" >/dev/null || true
  if grep -q "截屏服务已就绪" "$f" 2>/dev/null; then
    echo "截屏服务已就绪 ✓"
  else
    echo "截屏可能未就绪，请再执行 ./scripts/debug-adb.sh capture"
  fi
}

float_blue_start() {
  # 在悬浮窗面板内找蓝色「开始」按钮中心
  a exec-out screencap -p > /tmp/px_float.png
  python3 - <<'PY'
from PIL import Image
import numpy as np
a=np.array(Image.open('/tmp/px_float.png').convert('RGB'))
# 0.17 面板常见区域：左上状态栏下
reg=a[133:700,0:850]
blue=((reg[:,:,2]>140)&(reg[:,:,0]<90)&(reg[:,:,1]>50)&(reg[:,:,1]<160))
if blue.sum()>80:
    ys,xs=np.where(blue)
    # 取偏左的一团（开始按钮在左）
    left=blue[:, :max(1, blue.shape[1]//2)]
    if left.sum()>40:
        ys,xs=np.where(left)
    cx,cy=(xs.min()+xs.max())//2, 133+(ys.min()+ys.max())//2
    print(f"{cx} {cy}")
else:
    print("233 357")
PY
}

cmd_install() {
  need_device
  local apk="${1:-$APK_PATH}"
  echo "install $apk"
  a push "$apk" /data/local/tmp/agent.apk
  a shell "am force-stop com.android.packageinstaller" || true
  a shell "pm install -r -t /data/local/tmp/agent.apk" >/tmp/px_install.log 2>&1 &
  sleep 5
  installer_risk_dialog
  cat /tmp/px_install.log || true
}

cmd_setup() {
  need_device
  echo "启用无障碍(仅 PixelAgent+Minis) / 输入法=ADB Keyboard"
  a shell "settings put secure enabled_accessibility_services com.pixel.agent/com.pixel.agent.engine.TapService:com.openminis.app/com.openminis.app.accessibility.MinisAccessibilityService"
  a shell "settings put secure accessibility_enabled 1"
  a shell "settings put secure default_input_method com.android.adbkeyboard/.AdbIME"
  # 电池白名单，降低 vivo 冻结
  a shell "dumpsys deviceidle whitelist +com.pixel.agent" || true
  a shell "am start -n com.pixel.agent/.MainActivity"
  sleep 2
  a shell "dumpsys accessibility | grep 'Enabled services' | head -3"
  a shell "settings get secure default_input_method"
}

cmd_capture() {
  need_device
  a shell "am start -n com.pixel.agent/.MainActivity"
  sleep 2
  a shell "input tap 630 690"   # 启动截屏服务
  sleep 3
  capture_grant_dialog
}

cmd_float() {
  need_device
  a shell "am start -n com.pixel.agent/.MainActivity"
  sleep 1
  a shell "input tap 630 1026"  # 显示悬浮窗
  sleep 2
  a shell "dumpsys window windows 2>/dev/null | grep -o 'type=2038 com.pixel.agent, frame=\\[Rect([^]]*)\\]'" || true
}

cmd_start-flow() {
  need_device
  # 点悬浮窗蓝色开始
  local xy
  xy=$(float_blue_start)
  echo "tap start $xy"
  # eslint-disable-next-line
  read -r sx sy <<<"$xy"
  a logcat -c || true
  a shell "input tap $sx $sy"
  sleep 8
  a logcat -d | grep "PixelAgent.Flow" | head -40 || true
}

cmd_logs() {
  need_device
  a logcat -d | grep -E "PixelAgent|Selftest|SELFTEST|pageKind" | grep -v Aconfig | tail -80 || true
}

cmd_logs-follow() {
  need_device
  a logcat -s PixelAgent.Flow PixelAgent.Float PixelAgent.Capture PixelAgent.Tap
}

cmd-restore-ime() {
  need_device
  echo "恢复搜狗输入法 vivo"
  a shell "settings put secure default_input_method com.sohu.inputmethod.sogou.vivo/.SogouIME"
  a shell "settings get secure default_input_method"
}

cmd-verify-foreground() {
  need_device
  echo "前台包名(无障碍):"
  a shell "dumpsys accessibility | grep -i pixel | head -5" || true
  echo "ActivityManager 前台:"
  a shell "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity' | head -5" || true
}

cmd-help() {
  cat <<EOF
PixelAgent debug script
  SER=<adb serial>  $0 install <apk>     # 安装并处理 vivo 确认框
  $0 setup                              # 无障碍+ADB Keyboard
  $0 capture                            # 启动截屏并处理授权框
  $0 float                              # 弹出悬浮窗
  $0 start-flow                         # 点悬浮窗开始并看 Flow 日志
  $0 logs                               # 最近 PixelAgent 日志
  $0 logs-follow                        # 实时 Flow 日志
  $0 restore-ime                        # 恢复搜狗输入法
  $0 verify-foreground                  # 检查前台/无障碍
EOF
}

case "$CMD" in
  install) cmd_install "$APK" ;;
  setup) cmd_setup ;;
  capture) cmd_capture ;;
  float) cmd_float ;;
  start-flow|start) cmd_start-flow ;;
  logs) cmd_logs ;;
  logs-follow|follow) cmd_logs-follow ;;
  restore-ime|ime) cmd-restore-ime ;;
  verify-foreground|fg) cmd_verify_foreground ;;
  *) cmd-help ;;
esac
