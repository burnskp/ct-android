#!/usr/bin/env bash
# Start or stop the project's headless Android emulator.
#
# Usage: scripts/emulator.sh start|boot|stop
#   start  boot (if needed), install and launch the app, open scrcpy
#   boot   boot (if needed) only
#
# Environment overrides:
#   AVD_NAME, AVD_PACKAGE, AVD_DEVICE  AVD to create/boot
#   ANDROID_AVD_HOME                   AVD storage dir (default: <repo>/.avd)
#   EMULATOR_PORT                      console port; serial is emulator-<port>
#   EMULATOR_FLAGS                     extra emulator flags
#   SCRCPY=0                           do not open a scrcpy viewer
#   APP_ID, MAIN_ACTIVITY              app to install and launch on start
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

AVD_NAME="${AVD_NAME:-couch-tasks-api36}"
AVD_PACKAGE="${AVD_PACKAGE:-system-images;android-36;google_apis_playstore;x86_64}"
AVD_DEVICE="${AVD_DEVICE:-pixel_8_pro}"
EMULATOR_PORT="${EMULATOR_PORT:-5554}"
EMULATOR_FLAGS="${EMULATOR_FLAGS:-}"
SCRCPY="${SCRCPY:-1}"
APP_ID="${APP_ID:-com.couchtasks.android}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-com.couchtasks.android.MainActivity}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-300}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$repo_root/.avd}"

serial="emulator-$EMULATOR_PORT"
state_dir="$ANDROID_AVD_HOME"
emulator_pid_file="$state_dir/emulator.pid"
scrcpy_pid_file="$state_dir/scrcpy.pid"
emulator_log="$state_dir/emulator.log"
scrcpy_log="$state_dir/scrcpy.log"
debug_apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"

# Background processes outlive this script; setsid (util-linux) also detaches
# them from the terminal's session when it is available.
detach=(nohup)
if command -v setsid >/dev/null 2>&1; then
	detach+=(setsid)
fi

die() {
	echo "$*" >&2
	exit 1
}

pid_alive() {
	local pid_file="$1" pid
	[[ -f "$pid_file" ]] || return 1
	pid="$(<"$pid_file")"
	[[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

device_booted() {
	[[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]
}

preflight() {
	local tool
	for tool in adb emulator avdmanager; do
		command -v "$tool" >/dev/null 2>&1 ||
			die "'$tool' not found. Run inside the dev sandbox or add the Android SDK tools to PATH."
	done
	local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
	local image_dir="$sdk_root/${AVD_PACKAGE//;//}"
	[[ -n "$sdk_root" && -d "$image_dir" ]] ||
		die "System image not found: $image_dir. Run inside the dev sandbox or add the Android SDK tools to PATH."
	if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
		echo "Warning: /dev/kvm is not accessible; the emulator will be very slow or fail to start." >&2
	fi
}

ensure_avd() {
	mkdir -p "$ANDROID_AVD_HOME"
	if emulator -list-avds 2>/dev/null | grep -Fxq "$AVD_NAME"; then
		return
	fi
	echo "Creating AVD '$AVD_NAME' ($AVD_PACKAGE, $AVD_DEVICE)..."
	echo "no" | avdmanager create avd --name "$AVD_NAME" --package "$AVD_PACKAGE" --device "$AVD_DEVICE" >/dev/null
}

boot_emulator() {
	local flags
	read -r -a flags <<<"$EMULATOR_FLAGS"
	echo "Starting emulator '$AVD_NAME' as $serial (log: $emulator_log)..."
	"${detach[@]}" emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" \
		-no-window -no-audio -no-boot-anim -netdelay none -netspeed full \
		"${flags[@]}" >"$emulator_log" 2>&1 </dev/null &
	echo "$!" >"$emulator_pid_file"

	echo "Waiting for Android to boot..."
	local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
	until device_booted; do
		if ! pid_alive "$emulator_pid_file"; then
			rm -f "$emulator_pid_file"
			tail -n 20 "$emulator_log" >&2 || true
			die "Emulator exited during startup. Full log: $emulator_log"
		fi
		if ((SECONDS >= deadline)); then
			die "Timed out after ${BOOT_TIMEOUT_SECONDS}s waiting for boot. See $emulator_log or run 'make stop-emulator'."
		fi
		sleep 2
	done
	echo "Emulator $serial booted."
}

start_scrcpy() {
	[[ "$SCRCPY" != "0" ]] || return 0
	if pid_alive "$scrcpy_pid_file"; then
		return
	fi
	if ! command -v scrcpy >/dev/null 2>&1; then
		echo "scrcpy not found; skipping viewer." >&2
		return
	fi
	echo "Opening scrcpy (log: $scrcpy_log)..."
	"${detach[@]}" scrcpy -s "$serial" --no-audio >"$scrcpy_log" 2>&1 </dev/null &
	echo "$!" >"$scrcpy_pid_file"
}

boot() {
	preflight
	adb start-server >/dev/null
	if device_booted; then
		echo "Emulator $serial is already running."
	else
		ensure_avd
		boot_emulator
	fi
}

start() {
	boot
	(cd "$repo_root" && ./gradlew :app:assembleDebug)
	adb -s "$serial" install -r "$debug_apk"
	adb -s "$serial" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
	start_scrcpy
}

stop_pid() {
	local pid_file="$1" pid
	pid_alive "$pid_file" || return 0
	pid="$(<"$pid_file")"
	kill -TERM "$pid" 2>/dev/null || true
	for _ in {1..10}; do
		kill -0 "$pid" 2>/dev/null || return 0
		sleep 1
	done
	kill -KILL "$pid" 2>/dev/null || true
}

stop() {
	stop_pid "$scrcpy_pid_file"
	rm -f "$scrcpy_pid_file"

	local running=0
	if pid_alive "$emulator_pid_file"; then
		running=1
	elif command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q "^${serial}[[:space:]]"; then
		running=1
	fi
	if ((running == 0)); then
		rm -f "$emulator_pid_file"
		echo "Emulator $serial is not running."
		return
	fi

	echo "Stopping emulator $serial..."
	adb -s "$serial" emu kill >/dev/null 2>&1 || true
	for _ in {1..30}; do
		if ! pid_alive "$emulator_pid_file" && ! adb devices 2>/dev/null | grep -q "^${serial}[[:space:]]"; then
			rm -f "$emulator_pid_file"
			echo "Emulator stopped."
			return
		fi
		sleep 1
	done

	echo "Graceful shutdown timed out; killing the emulator process..." >&2
	pid_alive "$emulator_pid_file" || die "Emulator $serial did not stop and no PID is recorded; stop it manually."
	stop_pid "$emulator_pid_file"
	rm -f "$emulator_pid_file"
	echo "Emulator stopped."
}

case "${1:-}" in
start) start ;;
boot) boot ;;
stop) stop ;;
*) die "Usage: $0 start|boot|stop" ;;
esac
