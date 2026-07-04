#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DEVICE="${DEVICE:-}"
REQUESTED_COUNT="${1:-9}"
HANDOFF_COUNT="$REQUESTED_COUNT"
CAPTURE_TEXT="${CAPTURE_TEXT:-true}"
VIEWER_KIND=""
WORKFLOW_STARTED_EPOCH="$(date +%s)"
VIDEO_CACHE_ROOT="${VIDEO_CACHE_ROOT:-/sdcard/Android/data/com.tencent.mm/cache/ThumbVideoCache/CdnDownload/Cache}"
VIDEO_CACHE_MIN_BYTES="${VIDEO_CACHE_MIN_BYTES:-102400}"
VIDEO_CACHE_SETTLE_SECONDS="${VIDEO_CACHE_SETTLE_SECONDS:-6}"
AUTO_OPEN_MOMENTS="${AUTO_OPEN_MOMENTS:-true}"
PACKAGE="com.perrychoi.wechatmomentscontroller"
COMPONENT="$PACKAGE/.CommandActivity"

if [[ ! -x "$ADB" ]]; then
  ADB="adb"
fi

adb_cmd=("$ADB")
if [[ -n "$DEVICE" ]]; then
  adb_cmd+=("-s" "$DEVICE")
fi

run_adb() {
  "${adb_cmd[@]}" "$@"
}

current_focus() {
  run_adb shell dumpsys window | grep -Ei "mCurrentFocus" || true
}

ui_dump() {
  run_adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || return 1
  run_adb exec-out cat /sdcard/window.xml 2>/dev/null | tr -d '\r'
}

tap_text_if_visible() {
  local label="$1"
  local dump line bounds x1 y1 x2 y2 x y
  dump="$(ui_dump || true)"
  line="$(printf '%s\n' "$dump" | grep -m 1 "text=\"$label\"" || true)"
  [[ -n "$line" ]] || return 1
  bounds="$(printf '%s\n' "$line" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
  [[ "$bounds" =~ ^[0-9]+[[:space:]][0-9]+[[:space:]][0-9]+[[:space:]][0-9]+$ ]] || return 1
  read -r x1 y1 x2 y2 <<<"$bounds"
  x=$(((x1 + x2) / 2))
  y=$(((y1 + y2) / 2))
  run_adb shell input tap "$x" "$y"
  return 0
}

screen_size() {
  run_adb shell wm size | tr -d '\r' | sed -n 's/.*Physical size: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1
}

tap_discover_moments_by_coordinates() {
  local width height discover_x bottom_y moments_x moments_y
  read -r width height <<<"$(screen_size)"
  [[ -n "${width:-}" && -n "${height:-}" ]] || return 1
  discover_x=$((width * 5 / 8))
  bottom_y=$((height - 145))
  moments_x=$((width / 5))
  moments_y=$((height * 14 / 100))
  echo "Opening Moments by coordinate fallback"
  run_adb shell input tap "$discover_x" "$bottom_y"
  sleep 0.7
  run_adb shell input tap "$moments_x" "$moments_y"
  return 0
}

rescue_single_text_view_copy() {
  local width height long_x long_y copy_x copy_y focus
  read -r width height <<<"$(screen_size)"
  [[ -n "${width:-}" && -n "${height:-}" ]] || return 1
  long_x=$((width * 52 / 100))
  long_y=$((height * 22 / 100))
  copy_x=$((width / 2))
  copy_y=$((height * 64 / 100))
  echo "SingleTextView detected; rescuing full text copy"
  run_adb shell input swipe "$long_x" "$long_y" "$long_x" "$long_y" 900
  sleep 0.9
  run_adb shell input tap "$copy_x" "$copy_y"
  sleep 0.4
  run_adb shell am broadcast -a "$PACKAGE.COMMAND" \
    --es workflow native_copy_copied >/dev/null
  sleep 2
  for _ in $(seq 1 2); do
    focus="$(current_focus)"
    if ! echo "$focus" | grep -Eqi "SnsSingleTextView"; then
      break
    fi
    run_adb shell input keyevent BACK
    sleep 0.8
  done
  return 0
}

ensure_moments_surface() {
  [[ "$AUTO_OPEN_MOMENTS" == "true" ]] || return 0
  local focus
  focus="$(current_focus)"
  if echo "$focus" | grep -Eqi "SnsTimeLine|ImproveSnsTimeline"; then
    return 0
  fi
  if echo "$focus" | grep -Eqi "LauncherUI"; then
    if tap_text_if_visible "朋友圈"; then
      echo "Opened Moments from current WeChat page"
      for _ in $(seq 1 10); do
        sleep 1
        focus="$(current_focus)"
        if echo "$focus" | grep -Eqi "SnsTimeLine|ImproveSnsTimeline"; then
          return 0
        fi
      done
    fi
    tap_discover_moments_by_coordinates || return 0
    for _ in $(seq 1 10); do
      sleep 1
      focus="$(current_focus)"
      if echo "$focus" | grep -Eqi "SnsTimeLine|ImproveSnsTimeline"; then
        return 0
      fi
    done
  fi
}

prefs() {
  run_adb shell run-as "$PACKAGE" cat shared_prefs/automation.xml 2>/dev/null || true
}

app_cat() {
  local path="$1"
  run_adb exec-out run-as "$PACKAGE" cat "$path" 2>/dev/null || true
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/}"
  printf '%s' "$value"
}

list_video_cache_candidates() {
  run_adb shell "find '$VIDEO_CACHE_ROOT' -type f 2>/dev/null | while IFS= read -r f; do
    size=\$(stat -c '%s' \"\$f\" 2>/dev/null || echo 0)
    [ \"\$size\" -ge '$VIDEO_CACHE_MIN_BYTES' ] || continue
    if head -c 16 \"\$f\" 2>/dev/null | od -An -tx1 | grep -q '66 74 79 70'; then
      mtime=\$(stat -c '%Y' \"\$f\" 2>/dev/null || echo 0)
      printf '%s\t%s\t%s\n' \"\$mtime\" \"\$size\" \"\$f\"
    fi
  done" | tr -d '\r'
}

VIDEO_CACHE_BEFORE_LINES=""
snapshot_video_cache_before() {
  VIDEO_CACHE_BEFORE_LINES="$(list_video_cache_candidates || true)"
}

video_cache_before_sig() {
  local needle="$1"
  printf '%s\n' "$VIDEO_CACHE_BEFORE_LINES" | awk -F '\t' -v needle="$needle" '
    $3 == needle {
      print $1 ":" $2
      exit
    }
  '
}

append_manifest_record() {
  local export_path="$1"
  local record="$2"
  local manifest="$export_path/manifest.jsonl"
  local tmp
  tmp="$(mktemp)"
  app_cat "$manifest" >"$tmp" || true
  printf '%s\n' "$record" >>"$tmp"
  run_adb shell run-as "$PACKAGE" dd of="$manifest" bs=4096 conv=fsync <"$tmp" >/dev/null
  rm -f "$tmp"
}

video_cache_import_record() {
  local status="$1"
  local reason="$2"
  local source_path="${3:-}"
  local dest_path="${4:-}"
  local source_mtime="${5:-0}"
  local source_size="${6:-0}"
  local confidence="${7:-none}"
  local captured_at
  captured_at="$(date '+%Y%m%d_%H%M%S')"
  printf '{"type":"video_cache_import","captureMode":"wechat_video_cache_import","capturedAt":"%s","status":"%s","reason":"%s","source":"wechat_thumb_video_cache","sourcePath":"%s","destPath":"%s","sourceMtime":%s,"sourceSize":%s,"confidence":"%s","viewerKind":"%s","workflowStartEpoch":%s}\n' \
    "$(json_escape "$captured_at")" \
    "$(json_escape "$status")" \
    "$(json_escape "$reason")" \
    "$(json_escape "$source_path")" \
    "$(json_escape "$dest_path")" \
    "$source_mtime" \
    "$source_size" \
    "$(json_escape "$confidence")" \
    "$(json_escape "$VIEWER_KIND")" \
    "$WORKFLOW_STARTED_EPOCH"
}

select_video_cache_candidate() {
  local line mtime size path before_sig sig priority confidence
  while IFS=$'\t' read -r mtime size path; do
    [[ -n "${path:-}" ]] || continue
    sig="$mtime:$size"
    before_sig="$(video_cache_before_sig "$path")"
    if [[ -z "$before_sig" || "$before_sig" != "$sig" ]]; then
      priority=30
      confidence="new_or_updated"
    elif (( mtime >= WORKFLOW_STARTED_EPOCH - 5 )); then
      priority=20
      confidence="touched_during_workflow"
    elif (( mtime >= WORKFLOW_STARTED_EPOCH - 3600 )); then
      priority=10
      confidence="recent_fallback"
    else
      priority=1
      confidence="latest_fallback"
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$priority" "$mtime" "$size" "$confidence" "$path"
  done < <(list_video_cache_candidates || true) | sort -t $'\t' -k1,1nr -k2,2nr -k3,3nr | head -n 1
}

import_video_cache_if_needed() {
  local export_path="$1"
  [[ -n "$export_path" ]] || return 0

  local manifest
  manifest="$(app_cat "$export_path/manifest.jsonl")"
  if ! printf '%s' "$manifest" | grep -q 'video_long_press_disabled_to_avoid_contactinfo'; then
    return 0
  fi
  if printf '%s' "$manifest" | grep -q '"type":"video_cache_import".*"status":"imported"'; then
    echo "Video cache already imported for this export"
    return 0
  fi

  echo "Video post detected; waiting ${VIDEO_CACHE_SETTLE_SECONDS}s for WeChat cache to settle"
  sleep "$VIDEO_CACHE_SETTLE_SECONDS"

  local selected priority mtime size confidence source_path dest_path record
  selected="$(select_video_cache_candidate || true)"
  if [[ -z "$selected" ]]; then
    echo "No MP4-like WeChat video cache candidate found"
    record="$(video_cache_import_record "skipped" "no_mp4_like_cache_candidate")"
    append_manifest_record "$export_path" "$record" || true
    return 0
  fi

  IFS=$'\t' read -r priority mtime size confidence source_path <<<"$selected"
  dest_path="$export_path/video_cache_001.mp4"
  echo "Importing WeChat video cache: $source_path -> $dest_path ($size bytes, $confidence)"
  if run_adb exec-out cat "$source_path" | run_adb shell run-as "$PACKAGE" dd of="$dest_path" bs=4096 conv=fsync >/dev/null; then
    record="$(video_cache_import_record "imported" "selected_mp4_like_cache_candidate" "$source_path" "$dest_path" "$mtime" "$size" "$confidence")"
    append_manifest_record "$export_path" "$record" || true
    run_adb shell run-as "$PACKAGE" ls -l "$dest_path" || true
  else
    echo "Failed to import WeChat video cache" >&2
    record="$(video_cache_import_record "failed" "copy_failed" "$source_path" "$dest_path" "$mtime" "$size" "$confidence")"
    append_manifest_record "$export_path" "$record" || true
  fi
}

is_video_export() {
  local export_path="$1"
  [[ -n "$export_path" ]] || return 1
  app_cat "$export_path/manifest.jsonl" | grep -q 'video_long_press_disabled_to_avoid_contactinfo'
}

resolve_recent_video_export() {
  local export_path="$1"
  if is_video_export "$export_path"; then
    printf '%s' "$export_path"
    return 0
  fi
  if [[ "$VIEWER_KIND" != "video" ]]; then
    printf '%s' "$export_path"
    return 0
  fi

  local candidate
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    if is_video_export "$candidate"; then
      echo "Using recent video export: $candidate" >&2
      printf '%s' "$candidate"
      return 0
    fi
  done < <(run_adb shell run-as "$PACKAGE" find \
    "/data/user/0/$PACKAGE/files/moments_native_saves" \
    -maxdepth 1 -type d -name 'native_save_*' 2>/dev/null | tr -d '\r' | sort -r | head -n 6)

  printf '%s' "$export_path"
}

finish_success() {
  local export_path="$1"
  export_path="$(resolve_recent_video_export "$export_path")"
  import_video_cache_if_needed "$export_path"
  echo "Export: ${export_path:-unknown}"
}

start_capture() {
  local assume="$1"
  local count="$2"
  run_adb shell am start -n "$COMPONENT" \
    --es workflow capture_images \
    --ei image_count "$count" \
    --ez capture_text "$CAPTURE_TEXT" \
    --ez assume_viewer "$assume" >/dev/null
}

stop_capture() {
  run_adb shell am start -n "$COMPONENT" --es workflow stop >/dev/null
}

wake_automation() {
  run_adb shell am broadcast -a "$PACKAGE.AUTOMATION_WAKE" \
    --es reason "${1:-script_watchdog}" >/dev/null
}

pref_value() {
  local xml="$1"
  local key="$2"
  printf "%s" "$xml" | sed -n "s/.*<string name=\"$key\">\\(.*\\)<\\/string>.*/\\1/p" | tail -n 1
}

pref_int() {
  local xml="$1"
  local key="$2"
  printf "%s" "$xml" | sed -n "s/.*<int name=\"$key\" value=\"\\([0-9][0-9]*\\)\".*/\\1/p" | tail -n 1
}

restart_capture_without_text() {
  local reason="$1"
  echo "Text copy fallback: $reason; restarting media capture without native text copy"
  stop_capture
  sleep 1
  CAPTURE_TEXT=false
  start_capture false "$REQUESTED_COUNT"
}

snapshot_video_cache_before
ensure_moments_surface
stop_capture || true
sleep 0.5
echo "Starting list-page native save, count=$REQUESTED_COUNT capture_text=$CAPTURE_TEXT"
start_capture false "$REQUESTED_COUNT"

viewer_seen=false
single_text_rescued=false
text_fallback_restarted=false
native_copy_started_epoch=0
for _ in $(seq 1 45); do
  focus="$(current_focus)"
  echo "$focus"
  xml="$(prefs)"
  phase="$(pref_int "$xml" "native_copy_phase")"
  if [[ -n "$phase" ]]; then
    now_epoch="$(date +%s)"
    if (( native_copy_started_epoch == 0 )); then
      native_copy_started_epoch="$now_epoch"
    fi
    wake_automation "native_copy_script_watchdog" || true
    if [[ "$text_fallback_restarted" != true ]] \
      && [[ "$CAPTURE_TEXT" == "true" ]] \
      && (( now_epoch - native_copy_started_epoch >= 10 )); then
      restart_capture_without_text "native copy phase $phase timed out"
      text_fallback_restarted=true
      native_copy_started_epoch=0
      sleep 2
      continue
    fi
  fi
  if [[ "$single_text_rescued" != true ]] \
    && echo "$focus" | grep -Eqi "SnsSingleTextView"; then
    rescue_single_text_view_copy || true
    single_text_rescued=true
    sleep 1
    continue
  fi
  if echo "$focus" | grep -Eqi "ContactInfoUI|ContactLabel|ContactRemark|MMWebViewUI|AppBrand|SnsUserUI|Label|Tag|客户|标签|小程序"; then
    echo "Non-media page detected while opening media, pressing Back"
    run_adb shell input keyevent BACK
    if [[ -n "$phase" && "$text_fallback_restarted" != true && "$CAPTURE_TEXT" == "true" ]]; then
      restart_capture_without_text "native text copy opened non-media page"
      text_fallback_restarted=true
      native_copy_started_epoch=0
      sleep 2
    fi
    sleep 1
    continue
  fi
  if echo "$focus" | grep -Eqi "SnsOnlineVideo|OnlineVideo|VideoActivity"; then
    HANDOFF_COUNT=1
    VIEWER_KIND="video"
    viewer_seen=true
    break
  fi
  if echo "$focus" | grep -Eqi "SnsBrowseUI|SnsImage|ImageGallery|GalleryUI"; then
    VIEWER_KIND="image"
    viewer_seen=true
    break
  fi
  sleep 1
done

if [[ "$viewer_seen" != true ]]; then
  echo "Timed out waiting for WeChat media viewer" >&2
  prefs
  exit 1
fi

echo "Viewer detected, preparing handoff"
sleep 1
xml="$(prefs)"
command="$(printf "%s" "$xml" | sed -n 's/.*<string name="command">\(.*\)<\/string>.*/\1/p' | tail -n 1)"
export_path="$(printf "%s" "$xml" | sed -n 's/.*<string name="last_export">\(.*\)<\/string>.*/\1/p' | tail -n 1)"
forced_handoff_previous_export=""
forced_handoff_started_epoch=0
if [[ "$command" == "none" ]]; then
  echo "Automation already completed before handoff"
  finish_success "$export_path"
  exit 0
fi
if [[ "$VIEWER_KIND" == "video" ]]; then
  phase="$(pref_int "$xml" "native_copy_phase")"
  if [[ "$text_fallback_restarted" == true || "$CAPTURE_TEXT" != "true" || -n "$phase" ]]; then
    echo "Video viewer detected after text fallback; forcing assume_viewer handoff"
    forced_handoff_previous_export="$export_path"
    forced_handoff_started_epoch="$(date +%s)"
    stop_capture || true
    sleep 0.5
    CAPTURE_TEXT=false
    start_capture true 1
  else
    echo "Video viewer detected; waiting without assume_viewer handoff"
  fi
else
  start_capture true "$HANDOFF_COUNT"
fi

for _ in $(seq 1 90); do
  focus="$(current_focus)"
  if echo "$focus" | grep -Eqi "ContactInfoUI|ContactLabel|ContactRemark|MMWebViewUI|AppBrand|SnsUserUI|Label|Tag|客户|标签|小程序"; then
    echo "Non-media page detected during save, pressing Back and stopping automation"
    run_adb shell input keyevent BACK
    stop_capture
    sleep 1
  fi
  xml="$(prefs)"
  status="$(printf "%s" "$xml" | sed -n 's/.*<string name="last_diagnostic">\(.*\)<\/string>.*/\1/p' | tail -n 1)"
  export_path="$(printf "%s" "$xml" | sed -n 's/.*<string name="last_export">\(.*\)<\/string>.*/\1/p' | tail -n 1)"
  command="$(printf "%s" "$xml" | sed -n 's/.*<string name="command">\(.*\)<\/string>.*/\1/p' | tail -n 1)"
  echo "${status:-waiting}"
  if [[ "$command" == "none" ]]; then
    if [[ -n "$forced_handoff_previous_export" \
      && "$export_path" == "$forced_handoff_previous_export" \
      && $(( $(date +%s) - forced_handoff_started_epoch )) -lt 10 ]]; then
      echo "Waiting for forced handoff export"
      sleep 1
      continue
    fi
    finish_success "$export_path"
    exit 0
  fi
  sleep 2
done

echo "Timed out waiting for completion" >&2
prefs
exit 1
