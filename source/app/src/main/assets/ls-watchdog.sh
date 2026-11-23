#!/system/bin/sh
# 灵手 shell 侧看门狗（UID 2000 / DiLink 4.0）
#
# 为什么需要它：
#   App 内的四层守护（native daemon、:guard、RTC 心跳、BOOT_COMPLETED）全部跑在
#   com.byd.ls 自己的 UID 下。这台车机熄火是「整机 suspend」而不是断电，唤醒时没有任何
#   广播，BOOT_COMPLETED 永远不来；进程一旦被清，就没有任何东西能把它拉回来。
#   本脚本常驻在 UID 2000：只能被 suspend 冻结，不会被按 UID 清理，唤醒后 sleep 到期
#   立刻巡检 —— 这是唯一能覆盖「休眠唤醒」的一层。
#
# 用法：
#   sh /data/local/tmp/ls-watchdog.sh loop    # 常驻（单例）
#   sh /data/local/tmp/ls-watchdog.sh once    # 单次检查
#
# 日志：/data/local/tmp/ls-watchdog.log    单例：/data/local/tmp/ls-watchdog.pid

PKG=com.byd.ls
A11Y=$PKG/$PKG.service.LsAccessibilityService
A11Y_SHORT=$PKG/.service.LsAccessibilityService
A11Y_MATCH=label=灵手
MAIN=$PKG/.ui.MainActivity
BOOTRECV=$PKG/$PKG.receiver.BootReceiver
TICK=$PKG.cluster.TICK
RESTART=$PKG.action.RESTART
RESTARTRX=$PKG/$PKG.receiver.RestartReceiver
CARD=$PKG/$PKG.cluster.ui.CardActivity
CARD_FLAG=/sdcard/clusterlab_card_any.txt      # App 主进程写的卡片总开关，0=全关
NLS=$PKG/$PKG.cluster.data.MediaListener
LOG=/data/local/tmp/ls-watchdog.log
PIDFILE=/data/local/tmp/ls-watchdog.pid
STAMP=/data/local/tmp/ls-watchdog.a11y
HEAL=/data/local/tmp/ls-heal.req
TICKSTAMP=/data/local/tmp/ls-watchdog.tick   # 上一轮循环墙钟秒（识别休眠唤醒）
HEALHIST=/data/local/tmp/ls-watchdog.heal.hist  # 硬恢复时间戳，用于 10 分钟限流
SELF_APK=/data/local/tmp/ls-self.apk
SELF_TS=/data/local/tmp/ls-self-reinstall.ts
SELF_LOG=/data/local/tmp/ls-self-install.log

log() { echo "$(date +%m-%d_%H:%M:%S) $*" >> "$LOG" 2>/dev/null; }

trimlog() {
  [ -f "$LOG" ] || return 0
  sz=$(wc -c < "$LOG" 2>/dev/null | tr -d " ")
  if [ -n "$sz" ] && [ "$sz" -gt 65536 ]; then
    tail -n 100 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
  fi
}

# 冷启动保护：/data/local/tmp 的 TICKSTAMP 会跨重启保留，旧逻辑把“上次开机到本次开机”
# 的时间差误判成 suspend→resume，开机 60s 后直接 am force-stop 自己。
# uptime 足够短时只重置计时，不做硬恢复，也不重建还没 attach 的卡片窗口。
uptime_sec() {
  cut -d. -f1 /proc/uptime 2>/dev/null
}

in_boot_guard() {
  up=$(uptime_sec); [ -n "$up" ] || return 1
  [ "$up" -lt 120 ]
}

# 系统把 ComponentName 写成 com.byd.ls/.service.X；工程里写全名。
# beta5 只比全名，车机回读短名 → 永远判“不在名单” → 每轮追加一份，
# 制造重复项并让 AMS 反复 bind。两种写法都认。
a11y_in_list() {
  case ":$1:" in
    *":$A11Y:"*|*":$A11Y_SHORT:"*) return 0 ;;
  esac
  return 1
}

alive() {
  pidof "$PKG" >/dev/null 2>&1 && return 0
  # 部分 toybox 裁剪了 pidof / ps -A，两级兜底：args 里出现包名即视为活着
  ps -A 2>/dev/null | grep -q "[c]om.byd.ls" && return 0
  ps 2>/dev/null | grep -q "[c]om.byd.ls"
}

# 进程启动时长（秒），解析失败返回 -1。
# beta5 实车教训：13:12:19 刚拉起，13:12:45 App 请求硬恢复就 force-stop；
# 进程只活了 26s，force-stop 把通知监听解绑，系统 10 分钟后才重绑，歌词空 10 分钟。
# 刚起 5 分钟内绝不 force-stop，交给 ensure_a11y 继续软重绑。
process_age_sec() {
  et=$(ps -A -o PID,ETIME,NAME 2>/dev/null | awk -v pkg="$PKG" '$3==pkg {print $2; exit}')
  [ -n "$et" ] || { echo -1; return; }
  days=0
  case "$et" in
    *-*) days=${et%%-*}; et=${et#*-};;
  esac
  secs=$(printf '%s\n' "$et" | awk -F: '{if (NF==3) print $1*3600+$2*60+$3; else if (NF==2) print $1*60+$2; else print $1}')
  [ -n "$secs" ] || secs=0
  echo $((days * 86400 + secs))
}

# 进程不在：只发显式广播拉起，绝不 am start（会弹主界面）。
# shell 发的广播默认带 EXCLUDE_STOPPED_PACKAGES，被 ROM 清理过的包处于 stopped 态时
# 一条都收不到 —— 必须显式加 -f 0x20（FLAG_INCLUDE_STOPPED_PACKAGES），这是以前
# "广播无效" 的唯一原因。
ensure_process() {
  alive && return 0
  log "进程不在，发 RESTART 广播拉起（静默）"
  am broadcast -a "$RESTART" -n "$RESTARTRX" -f 0x20 >/dev/null 2>&1
  sleep 2
  if alive; then log "RESTART 拉起成功"; return 0; fi
  log "RESTART 无效，发 TICK 广播（带 stopped 标志）"
  am broadcast -a "$TICK" -n "$BOOTRECV" -f 0x20 >/dev/null 2>&1
  sleep 2
  if alive; then log "TICK 拉起成功"; return 0; fi
  # 休眠唤醒后系统会用 ssc_skip 拦掉广播和 bindService，但 shell 的显式 Activity 启动仍可放行。
  # 只拉 display 1 的仪表卡片，不碰主界面；FLAG_INCLUDE_STOPPED_PACKAGES 用于清 stopped 态。
  log "广播均被拦截，直接拉仪表卡片（display 1）"
  am start-activity --display 1 -f 0x04000020 -n "$CARD" >/dev/null 2>&1
  sleep 2
  if alive; then log "直接拉起成功"; return 0; fi
  log "拉起失败，等下一轮（不弹界面）"
}

# 手势前台服务被系统掐掉：交给 BootReceiver 的 TICK 分支补（non-exported 服务不能被 shell 直启）
ensure_gesture() {
  alive || return 0
  n=$(dumpsys activity services "$PKG" 2>/dev/null | grep -c "GlobalGestureService")
  [ "$n" -gt 0 ] && return 0
  log "手势服务不在，TICK 补拉"
  am broadcast -a "$TICK" -n "$BOOTRECV" -f 0x20 >/dev/null 2>&1
}

# 卡片窗口不在 display 1：普通 am start 对已回收窗口是空操作，必须带 CLEAR_TASK
ensure_card() {
  alive || return 0
  in_boot_guard && return 0
  # 全关后 App 仍有主进程和手势服务，但仪表卡片不能存在；flag=0 时直接跳过，
  # 否则每轮巡检都会把空 CardActivity 反复拉起来。
  [ "$(cat "$CARD_FLAG" 2>/dev/null)" = "0" ] && return 0
  # 与 Card.CHECK_WIN 同一口径：只数窗口行，避免 dumpsys 其它段落提到类名造成误判
  n=$(dumpsys window windows 2>/dev/null | grep "Window #" | grep -c "cluster.ui.CardActivity")
  [ "$n" -gt 0 ] && return 0
  log "卡片窗口不在，重建 display 1"
  am start-activity --display 1 -f 0x04000000 -n "$CARD" >/dev/null 2>&1
}

# 无障碍：不在名单就合并写回；在名单但 Bound 段里没有 → 摘掉再写回（温和重绑，绝不 force-stop）
ensure_a11y() {
  cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
  if ! a11y_in_list "$cur"; then
    case "$cur" in
      null|"") new="$A11Y_SHORT" ;;
      *) new="$cur:$A11Y_SHORT" ;;
    esac
    log "无障碍不在名单，合并写回"
    settings put secure enabled_accessibility_services "$new" >/dev/null 2>&1
    settings put secure accessibility_enabled 1 >/dev/null 2>&1
    return 0
  fi
  en=$(settings get secure accessibility_enabled 2>/dev/null)
  [ "$en" = "1" ] || settings put secure accessibility_enabled 1 >/dev/null 2>&1
  alive || return 0
  ds=$(dumpsys accessibility 2>/dev/null)
  bound=$(printf %s "$ds" | awk "/Bound services/{f=1;next} /Enabled services/{f=0} f" | grep -c "$A11Y_MATCH")
  if [ "$bound" -gt 0 ]; then rm -f "$STAMP"; return 0; fi
  # 单次判不出来不动手：dumpsys 偶发缺段，连续两轮才算真故障
  if [ ! -f "$STAMP" ]; then
    echo 1 > "$STAMP" 2>/dev/null
    log "无障碍疑似未绑定（bound=0），下一轮复核"
    return 0
  fi
  rm -f "$STAMP" 2>/dev/null
  without=$(printf %s "$cur" | sed -e "s#$A11Y_SHORT:##" -e "s#:$A11Y_SHORT##" -e "s#$A11Y:##" -e "s#:$A11Y##")
  [ "$without" = "$cur" ] && without=""
  log "无障碍连续两轮未绑定，温和重绑"
  settings put secure enabled_accessibility_services "$without" >/dev/null 2>&1
  sleep 2
  settings put secure enabled_accessibility_services "$cur" >/dev/null 2>&1
  sleep 2
  if dumpsys accessibility 2>/dev/null | grep -q "$A11Y_MATCH"; then
    log "重绑成功"
  else
    log "重绑未成功，等下一轮"
    if ! in_boot_guard && a11y_binding; then
      log "重绑未成功且 Binding 悬空 → 本地重装"
      reinstall_self
    fi
  fi
}

# 1.4.102：硬恢复 —— 由本脚本（UID 2000，独立进程）执行。
# App 自己用 adblib 跑「force-stop 自己」时，杀进程会掐掉 socket，adbd 对整个会话发
# SIGHUP → 后半段 RESTART 根本没执行（1.4.94–1.4.99 全栽在这）。本脚本是外部进程，
# 杀 App 不影响它跑完后半段。
a11y_bound() {
  dumpsys accessibility 2>/dev/null | awk "/Bound services/{f=1;next} /Enabled services/{f=0} f" | grep -q "$A11Y_MATCH"
}

# AMS 正在 bind（Binding services 段里有灵手）：此时 force-stop 只会把绑定打断。
a11y_binding() {
  dumpsys accessibility 2>/dev/null | grep "Binding services" | grep -q "LsAccessibilityService"
}

# 休眠唤醒后 AMS 可能把 ServiceRecord 清掉，只留下 Binding services 里的悬空条目。
# 此时 settings 去/回、0→1 都无效，只有让 PackageManager 重装同版本 APK 才能重建绑定。
# 由 UID 2000 执行，pm install 杀 App 不会中断本脚本；2 分钟冷却，避免反复重装。
media_listener_ok() {
  dumpsys notification --noredact 2>/dev/null |
    awk '/Live notification listeners/{f=1;next} /Snoozed notification listeners/{f=0} f' |
    grep -q "$PKG"
}

# 唤醒后系统可能只保留 NLS 的悬空记录，不重新 bind；切歌时新通知才会触发绑定。
# 用 shell 的 disallow/allow 主动重绑，通常不需要重装 App。
media_listener_rebind() {
  log "唤醒后通知监听未绑定，shell disallow/allow 重绑"
  cmd notification disallow_listener "$NLS" >/dev/null 2>&1
  sleep 1
  cmd notification allow_listener "$NLS" >/dev/null 2>&1
  sleep 2
}

# 车机熄火/亮屏是 quickboot，不是长休眠：看门狗经常来不及用墙钟 gap 识别。
# 新进程起来 120s 内先做无损重绑：A11Y 走 settings 摘/回，NLS 走 allow 二次重试。
# 只有 A11Y 仍停在 Binding 悬空时才重装；单纯 NLS 未绑不再杀 App，下一轮继续重绑。
quick_wake_repair() {
  in_boot_guard && return 0
  age=$(process_age_sec)
  [ "$age" -ge 0 ] && [ "$age" -lt 120 ] || return 0
  if a11y_bound && media_listener_ok; then return 0; fi

  if ! a11y_bound; then
    log "唤醒后无障碍未绑定，先温和重绑"
    soft_rebind_a11y && log "唤醒后无障碍温和重绑成功"
  fi

  if ! media_listener_ok; then
    media_listener_rebind
    if ! media_listener_ok; then
      log "通知监听第一次重绑未生效，再试一次"
      media_listener_rebind
    fi
  fi

  if a11y_bound && media_listener_ok; then
    log "唤醒后重绑已恢复"
    return 0
  fi

  ab=0; a11y_bound && ab=1
  ml=0; media_listener_ok && ml=1
  if [ "$ab" = "0" ] && a11y_binding; then
    log "唤醒后仍异常(a11y=$ab nls=$ml, A11Y Binding 悬空)，执行本地重装"
    reinstall_self
  else
    log "唤醒后仍异常(a11y=$ab nls=$ml)，等下一轮重绑，不重装"
  fi
}

reinstall_self() {
  now=$(date +%s 2>/dev/null); [ -n "$now" ] || return 0
  last=$(cat "$SELF_TS" 2>/dev/null); [ -n "$last" ] || last=0
  if [ $((now - last)) -lt 120 ]; then
    log "本地重装冷却中(<2min)，跳过"
    return 0
  fi
  # 每次重装前都从当前安装包刷新副本，避免 /data/local/tmp 里残留旧版本 APK。
  p=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
  [ -n "$p" ] || { log "本地重装跳过：拿不到 base.apk"; return 0; }
  cp "$p" "$SELF_APK" 2>/dev/null || { log "本地重装跳过：复制 base.apk 失败"; return 0; }
  echo "$now" > "$SELF_TS" 2>/dev/null
  log "无障碍 Binding 悬空，执行本地重装恢复（零流量）"
  pm install -r -d "$SELF_APK" > "$SELF_LOG" 2>&1
  sleep 8
  am broadcast -a "$RESTART" -n "$RESTARTRX" -f 0x20 >/dev/null 2>&1
  sleep 3
  if alive; then log "本地重装后进程已起"; else log "本地重装后进程未起，等下一轮"; fi
}

# 1.5.0-beta5：硬恢复前先做一次无损重绑（只摘/回自己的条目）。
# 成功就不 force-stop；这是 beta4 自杀循环的第一道闸。
soft_rebind_a11y() {
  alive || return 1
  cur=$(settings get secure enabled_accessibility_services 2>/dev/null)
  if ! a11y_in_list "$cur"; then
    # 1.5.0-beta5：不在名单先合并写回再判定。开机/刚 force-stop 后名单尚未写回，
    # 属"没写上"不是"绑坏了"；直接判失败会把刚拉起的过程误杀（beta4 自杀点之一）。
    case "$cur" in
      null|"") new="$A11Y_SHORT" ;;
      *) new="$cur:$A11Y_SHORT" ;;
    esac
    log "硬恢复前发现不在名单，先合并写回"
    settings put secure enabled_accessibility_services "$new" >/dev/null 2>&1
    settings put secure accessibility_enabled 1 >/dev/null 2>&1
    sleep 2
    a11y_bound && return 0
    cur="$new"
  fi
  without=$(printf %s "$cur" | sed -e "s#$A11Y_SHORT:##" -e "s#:$A11Y_SHORT##" -e "s#$A11Y:##" -e "s#:$A11Y##")
  [ "$without" = "$cur" ] && without=""
  log "硬恢复前先温和重绑"
  settings put secure enabled_accessibility_services "$without" >/dev/null 2>&1
  sleep 2
  settings put secure enabled_accessibility_services "$cur" >/dev/null 2>&1
  settings put secure accessibility_enabled 1 >/dev/null 2>&1
  sleep 2
  a11y_bound
}

# 10 分钟内最多 1 次 force-stop；超过后只保留温和重绑，避免反复自杀。
hard_recover_allowed() {
  now=$(date +%s 2>/dev/null); [ -n "$now" ] || return 0
  [ -f "$HEALHIST" ] || : > "$HEALHIST"
  tmp="$HEALHIST.tmp"
  : > "$tmp" 2>/dev/null
  n=0
  while read -r ts; do
    [ -n "$ts" ] || continue
    if [ $((now - ts)) -lt 600 ]; then
      echo "$ts" >> "$tmp" 2>/dev/null
      n=$((n + 1))
    fi
  done < "$HEALHIST"
  mv "$tmp" "$HEALHIST" 2>/dev/null
  [ "$n" -lt 1 ]
}

record_hard_recover() {
  now=$(date +%s 2>/dev/null)
  [ -n "$now" ] && echo "$now" >> "$HEALHIST" 2>/dev/null
  return 0
}

hard_recover() {
  wait_s=${1:-1}
  ignore_boot=${2:-0}
  if [ "$ignore_boot" != "1" ] && in_boot_guard; then
    rm -f "$HEAL" 2>/dev/null
    log "冷启动保护期内，跳过硬恢复(wait=${wait_s}s)"
    return 0
  fi

  # 1.4.169 的卡片窗口判据已移除：卡片在不在不能代表无障碍健康。
  # 无障碍已绑定 → 不做任何破坏性动作。
  if a11y_bound; then
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "无障碍已绑定，跳过硬恢复(wait=${wait_s}s)"
    return 0
  fi

  # 先无损重绑（两次快速尝试）；成功则本次不杀进程。
  if soft_rebind_a11y; then
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "温和重绑成功，跳过硬恢复(wait=${wait_s}s)"
    return 0
  fi
  log "第 1 次温和重绑未成功，1 秒后重试"
  sleep 1
  if soft_rebind_a11y; then
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "第 2 次温和重绑成功，跳过硬恢复(wait=${wait_s}s)"
    return 0
  fi
  log "两次温和重绑均未成功，进入硬恢复判断"

  # 进程已经不在时，force-stop 没有意义；只拉起，不制造一次假“自杀”。
  if ! alive; then
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "进程不在，先拉起，不做硬恢复(wait=${wait_s}s)"
    ensure_process
    return 0
  fi

  # 进程刚起不足 5 分钟：force-stop 只会打断正在建立的绑定。
  # beta5 实车 13:12:19 拉起、13:12:45 被硬恢复，通知监听随即解绑，歌词空 10 分钟。
  age=$(process_age_sec)

  # 休眠唤醒后 AMS 可能只留下 Binding 悬空条目，ServiceRecord 已被清掉。
  # 这种状态 force-stop 与 settings 重写都无效，只有重装同版本 APK 能重建绑定；
  # 先等进程起 60 秒（冷启动通常已自然绑定），再交给本地重装。
  if a11y_binding; then
    if [ "$age" -ge 0 ] && [ "$age" -lt 60 ]; then
      rm -f "$HEAL" "$STAMP" 2>/dev/null
      log "无障碍仍在 Binding services 且进程仅 ${age}s，等下一轮软重绑"
      return 0
    fi
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "无障碍仍在 Binding services，执行本地重装修复"
    reinstall_self
    return 0
  fi

  if [ "$age" -ge 0 ] && [ "$age" -lt 300 ]; then
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "进程启动仅 ${age}s（<300s），拒绝 force-stop，交给下一轮软重绑"
    return 0
  fi

  if ! hard_recover_allowed; then
    rm -f "$HEAL" "$STAMP" 2>/dev/null
    log "硬恢复限流：10 分钟内已 1 次，跳过(wait=${wait_s}s)"
    return 0
  fi

  record_hard_recover
  rm -f "$HEAL" "$STAMP" 2>/dev/null
  log "硬恢复开始(wait=${wait_s}s)"
  am force-stop "$PKG" >/dev/null 2>&1
  sleep "$wait_s"
  am broadcast -a "$RESTART" -n "$RESTARTRX" -f 0x20 >/dev/null 2>&1
  sleep 2
  # force-stop 后立即做一次无障碍巡检；不要等下一轮 60s。
  ensure_a11y
  ensure_card
  sleep 3
  if alive; then
    log "硬恢复：进程已起"
  else
    am broadcast -a "$TICK" -n "$BOOTRECV" -f 0x20 >/dev/null 2>&1
    sleep 2
    alive && log "硬恢复：TICK 拉起成功" || log "硬恢复：拉起失败，等下一轮"
  fi
  sleep 8
  if a11y_bound; then log "硬恢复成功：a11y 已绑定"; return 0; fi
  # 不再第二次 force-stop：第一次杀进程已经把包置为 stopped，8 秒内再杀一次只会
  # 把刚起来的进程重新打断，AMS 更不会重建绑定。交给下一轮 ensure_a11y 温和重绑。
  log "硬恢复后仍未绑定，留给下一轮 ensure_a11y 温和重绑"
  return 0
}

# App 侧兜底请求文件（只在看门狗自己没来得及动手时才用）
hard_heal() {
  [ -f "$HEAL" ] || return 0
  log "收到 App 硬恢复请求"
  # hard_recover 内部先做无损重绑；失败才在 2 次/10 分钟限流内 force-stop。
  hard_recover 1 1
}

# 1.4.102：休眠唤醒检测 —— 循环被 suspend 冻住，resume 后墙钟会跳。
# 1.5.0-beta5：这里只做无损巡检，不再直接 force-stop。beta4 现场 22:42:36
# 把 78 秒循环延迟误判成唤醒，硬恢复把主进程杀了；唤醒恢复交给 ensure_a11y 和
# App 的显式请求（hard_heal）处理。
resume_check() {
  now=$(date +%s 2>/dev/null); [ -n "$now" ] || return 0
  if in_boot_guard; then
    echo "$now" > "$TICKSTAMP" 2>/dev/null
    return 0
  fi
  last=$(cat "$TICKSTAMP" 2>/dev/null); [ -n "$last" ] || last=$now
  echo "$now" > "$TICKSTAMP" 2>/dev/null
  gap=$((now - last))
  [ "$gap" -gt 60 ] || return 0
  log "检测到休眠唤醒(gap=${gap}s) → 先温和重绑，不硬杀"
  ensure_process
  ensure_a11y
  ensure_card
}

once() {
  resume_check
  ensure_process
  quick_wake_repair
  hard_heal
  ensure_gesture
  ensure_card
  ensure_a11y
}

# ---------------- loop：单例 + 周期巡检 ----------------
if [ "$1" = "loop" ]; then
  # 1.4.103：单例改用 mkdir 原子锁（pidfile 会被 rm/覆盖，历史上曾同时跑 5 个实例）
  LOCK=/data/local/tmp/ls-watchdog.lock
  if ! mkdir "$LOCK" 2>/dev/null; then
    old=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
      log "已有看门狗 pid=$old（lock 持有者），本实例退出"
      exit 0
    fi
    rm -rf "$LOCK" 2>/dev/null
    mkdir "$LOCK" 2>/dev/null || { log "拿锁失败，本实例退出"; exit 0; }
  fi
  echo $$ > "$LOCK/pid" 2>/dev/null
  echo $$ > "$PIDFILE"
  trap "rm -rf $LOCK; rm -f $PIDFILE; exit 0" EXIT INT TERM
  trimlog
  log "===== LOOP START pid=$$ ====="
  ensure_process
  quick_wake_repair
  ensure_gesture
  ensure_card
  ensure_a11y
  n=0
  while true; do
    resume_check
    sleep 5
    trimlog
    ensure_process
    quick_wake_repair
    hard_heal
    n=$((n + 1))
    if [ $((n % 3)) -eq 0 ]; then ensure_gesture; fi
    if [ $((n % 3)) -eq 0 ]; then ensure_card; fi
    if [ $((n % 12)) -eq 0 ]; then ensure_a11y; fi
  done
elif [ "$1" = "once" ] || [ -z "$1" ]; then
  once
fi
