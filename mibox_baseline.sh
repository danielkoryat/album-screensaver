#!/bin/sh
# Read-only performance snapshot for Mi Box 3. Usage: sh mibox_baseline.sh <label> [--launch]
export MSYS_NO_PATHCONV=1
# --launch additionally measures cold-start time of a few apps (force-stops them first; interrupts playback).
S=${MIBOX_ADB:?set MIBOX_ADB to the box, e.g. 192.168.1.50:5555}; A="adb -s $S shell"; L=${1:-snapshot}; OUT="mibox_${L}_$(date +%Y%m%d_%H%M).txt"
{
echo "== $L  $(date)"
$A uptime
$A cat /proc/meminfo | grep -E 'MemTotal|MemFree|MemAvailable|^Cached|SwapTotal|SwapFree|AnonPages'
$A dumpsys meminfo | grep -E 'Total RAM|Free RAM|Used RAM|ZRAM'
echo "-- top PSS"; $A dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p' | head -16
echo "-- installd"; $A 'grep -E "VmRSS|VmSwap" /proc/$(pidof installd)/status'
echo "-- cpu (60s window)"; sleep 20; $A dumpsys cpuinfo | head -12
echo "-- cpufreq time_in_state"; $A cat /sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state
echo "-- temp"; $A dumpsys hardware_properties | grep 'CPU temperatures'
echo "-- storage"; $A df -h /data /system 2>/dev/null; $A dumpsys diskstats | grep -E 'Free|App Cache Size'
echo "-- process count"; $A ps -A | wc -l
echo "-- foreground services"; $A dumpsys activity services | grep -E 'ServiceRecord{|isForeground=true' | grep -B1 'isForeground=true' | grep -oE 'u0 [a-zA-Z0-9_.]+/' | sort -u
if [ "$2" = "--launch" ]; then
  echo "-- cold start (ms, TotalTime)"
  for p in com.google.android.youtube.tv com.netflix.ninja com.android.tv.settings; do
    a=$($A cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1)
    $A am force-stop $p; sleep 2
    echo "$p $($A am start -W -n $a | grep TotalTime)"
    $A input keyevent KEYCODE_HOME; sleep 3
  done
fi
} | tee "$OUT"
echo "saved $OUT"
