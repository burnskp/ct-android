SHELL := /bin/bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c

GRADLE ?= ./gradlew
ADB ?= adb
EMULATOR ?= emulator
AVDMANAGER ?= avdmanager
AVD_NAME ?= Pixel_8_pro
AVD_PACKAGE ?= system-images;android-36;google_apis;x86_64
AVD_DEVICE ?= pixel_8_pro
EMULATOR_FLAGS ?=
EMULATOR_LOG ?= /tmp/couch-tasks-emulator.log
SCRCPY ?= scrcpy
SCRCPY_FLAGS ?= --no-audio
SCRCPY_LOG ?= /tmp/couch-tasks-scrcpy.log
SCRCPY_CONNECT ?= 1
SCRCPY_WAIT_SECONDS ?= 60
ANDROID_AVD_HOME ?= $(if $(XDG_CONFIG_HOME),$(XDG_CONFIG_HOME)/.android/avd,$(HOME)/.android/avd)
export ANDROID_AVD_HOME

APP_ID := com.couchtasks.android
MAIN_ACTIVITY := com.couchtasks.android.MainActivity
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk
RELEASE_UNSIGNED_APK := app/build/outputs/apk/release/app-release-unsigned.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-sideload.apk
DEBUG_KEYSTORE ?= $(HOME)/.android/debug.keystore

.PHONY: help build release sideload test check connected-check verify clean clean-emulator-cache install launch list-avds create-avd connect-scrcpy run-emulator stop-emulator

help:
	@echo "Targets:"
	@echo "  make build          Build the debug APK"
	@echo "  make release        Build a signed public release APK"
	@echo "  make sideload       Build a minified APK signed with the local debug key"
	@echo "  make test           Run debug unit tests"
	@echo "  make check          Run unit tests, lint, and debug build"
	@echo "  make connected-check Run instrumented tests on a connected device/emulator"
	@echo "  make verify         Run check and connected-check"
	@echo "  make clean          Clean Gradle outputs"
	@echo "  make clean-emulator-cache Clear app data/cache on the first running emulator"
	@echo "  make install        Install debug APK on the first running emulator"
	@echo "  make launch         Launch the app on the first running emulator"
	@echo "  make list-avds      List available local AVDs"
	@echo "  make create-avd     Create a Pixel 8 Pro API 36 AVD"
	@echo "  make connect-scrcpy Connect to the first running emulator with scrcpy"
	@echo "  make run-emulator   Start a headless emulator if needed, connect with scrcpy, install, and launch"
	@echo "  make stop-emulator  Stop the first running emulator"
	@echo ""
	@echo "Options:"
	@echo "  AVD_NAME=name       AVD to create/start (default: $(AVD_NAME))"
	@echo "  AVD_PACKAGE=pkg     System image package for create-avd"
	@echo "  AVD_DEVICE=device   Hardware profile for create-avd"
	@echo "  ANDROID_AVD_HOME=dir AVD storage/listing dir (default: $(ANDROID_AVD_HOME))"
	@echo "  EMULATOR_FLAGS=...  Extra emulator flags; run-emulator adds -no-window and -no-audio by default"
	@echo "  EMULATOR_LOG=file   Emulator log path (default: $(EMULATOR_LOG))"
	@echo "  SCRCPY=scrcpy       scrcpy executable override"
	@echo "  SCRCPY_FLAGS=...    Extra scrcpy flags (default: $(SCRCPY_FLAGS))"
	@echo "  SCRCPY_CONNECT=0    Start emulator without auto-launching scrcpy"
	@echo "  SCRCPY_LOG=file     scrcpy log path (default: $(SCRCPY_LOG))"
	@echo "  SCRCPY_WAIT_SECONDS=n Seconds for connect-scrcpy to wait (default: $(SCRCPY_WAIT_SECONDS))"
	@echo "  DEBUG_KEYSTORE=file Keystore for make sideload signing (default: $(DEBUG_KEYSTORE))"
	@echo "  ADB=/path/to/adb    adb executable override"
	@echo "  EMULATOR=emulator   emulator executable override"

build:
	$(GRADLE) :app:assembleDebug

release:
	tag="$$(git tag --points-at HEAD --list 'v*')"
	if [[ -z "$$tag" || "$$tag" == *$$'\n'* ]]; then
		echo "Public releases require exactly one vMAJOR.MINOR.PATCH tag on HEAD." >&2
		exit 1
	fi
	version_output="$$(python3 scripts/release-version.py "$$tag" --format gradle)"
	mapfile -t version_args <<< "$$version_output"
	bash scripts/sign-release.sh --check
	$(GRADLE) "$${version_args[@]}" :app:assembleRelease
	bash scripts/sign-release.sh "$(RELEASE_UNSIGNED_APK)" app/build/outputs/apk/release/app-release.apk

sideload:
	$(GRADLE) :app:assembleRelease
	sdk_root="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-}}"
	build_tools="$${sdk_root:+$$sdk_root/build-tools/37.0.0}"
	if [[ -n "$$build_tools" && -x "$$build_tools/zipalign" ]]; then
		zipalign="$$build_tools/zipalign"
	elif command -v zipalign >/dev/null 2>&1; then
		zipalign="zipalign"
	else
		echo "zipalign not found. Enter 'nix develop' or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
		exit 1
	fi
	if [[ -n "$$build_tools" && -x "$$build_tools/apksigner" ]]; then
		apksigner="$$build_tools/apksigner"
	elif command -v apksigner >/dev/null 2>&1; then
		apksigner="apksigner"
	else
		echo "apksigner not found. Enter 'nix develop' or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
		exit 1
	fi
	if [[ ! -f "$(DEBUG_KEYSTORE)" ]]; then
		mkdir -p "$$(dirname "$(DEBUG_KEYSTORE)")"
		keytool -genkeypair \
			-keystore "$(DEBUG_KEYSTORE)" \
			-storepass android \
			-keypass android \
			-alias androiddebugkey \
			-keyalg RSA \
			-keysize 2048 \
			-validity 10000 \
			-dname "CN=Android Debug,O=Android,C=US" >/dev/null
	fi
	aligned_apk="$${TMPDIR:-/tmp}/couch-tasks-release-aligned.apk"
	"$$zipalign" -f -p 4 "$(RELEASE_UNSIGNED_APK)" "$$aligned_apk"
	"$$apksigner" sign \
		--ks "$(DEBUG_KEYSTORE)" \
		--ks-key-alias androiddebugkey \
		--ks-pass pass:android \
		--key-pass pass:android \
		--out "$(RELEASE_APK)" \
		"$$aligned_apk"
	"$$apksigner" verify "$(RELEASE_APK)"
	rm -f "$$aligned_apk"
	echo "Release APK ready: $(RELEASE_APK)"

test:
	$(GRADLE) testDebugUnitTest

check:
	python3 -m unittest discover -s scripts -p 'test_*.py'
	$(GRADLE) testDebugUnitTest lintDebug assembleDebug

connected-check:
	$(GRADLE) connectedDebugAndroidTest

verify: check connected-check

clean:
	$(GRADLE) clean

clean-emulator-cache:
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		echo "No running emulator found. Use 'make run-emulator' to start one." >&2
		exit 1
	fi
	if $(ADB) -s "$$serial" shell pm path "$(APP_ID)" >/dev/null 2>&1; then
		echo "Clearing app data/cache for $(APP_ID) on $$serial..."
		$(ADB) -s "$$serial" shell am force-stop "$(APP_ID)" >/dev/null 2>&1 || true
		$(ADB) -s "$$serial" shell pm clear "$(APP_ID)"
	else
		echo "$(APP_ID) is not installed on $$serial; nothing to clear."
	fi

install: build
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		echo "No running emulator found. Use 'make run-emulator' to start one." >&2
		exit 1
	fi
	$(ADB) -s "$$serial" install -r "$(DEBUG_APK)"

launch:
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		echo "No running emulator found. Use 'make run-emulator' to start one." >&2
		exit 1
	fi
	$(ADB) -s "$$serial" shell am start -n "$(APP_ID)/$(MAIN_ACTIVITY)"

list-avds:
	$(EMULATOR) -list-avds

create-avd:
	avd_package='$(AVD_PACKAGE)'
	sdk_root="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-}}"
	image_dir="$${sdk_root:+$$sdk_root/$${avd_package//;/\/}}"
	if [[ -z "$$sdk_root" || ! -d "$$image_dir" ]]; then
		echo "Missing Android emulator system image: $$avd_package" >&2
		echo "Expected under: $${image_dir:-<ANDROID_HOME is not set>}" >&2
		echo "Install the system image in your system Android SDK/Android Studio tools." >&2
		exit 1
	fi
	if $(EMULATOR) -list-avds | grep -Fxq "$(AVD_NAME)"; then
		echo "AVD '$(AVD_NAME)' already exists."
	else
		echo "Creating AVD '$(AVD_NAME)' using $(AVD_PACKAGE) / $(AVD_DEVICE)..."
		echo "no" | $(AVDMANAGER) create avd --force --name "$(AVD_NAME)" --package "$(AVD_PACKAGE)" --device "$(AVD_DEVICE)"
	fi
	$(EMULATOR) -list-avds

stop-emulator:
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		echo "No running emulator found."
		exit 0
	fi

	echo "Stopping emulator $$serial..."
	if $(ADB) -s "$$serial" shell reboot -p >/dev/null 2>&1; then
		for _ in {1..30}; do
			if ! $(ADB) devices | awk '$$1 == serial && $$2 == "device" { found = 1 } END { exit !found }' serial="$$serial"; then
				echo "Emulator $$serial stopped."
				exit 0
			fi
			sleep 1
		done
		echo "Graceful shutdown timed out; trying to kill the local emulator process..." >&2
	else
		echo "Graceful shutdown request failed; trying to kill the local emulator process..." >&2
	fi

	port="$${serial#emulator-}"
	if [[ ! "$$port" =~ ^[0-9]+$$ ]]; then
		echo "Could not infer emulator console port from serial: $$serial" >&2
		exit 1
	fi
	pids=""
	if command -v lsof >/dev/null 2>&1; then
		pids="$$( lsof -tiTCP:"$$port" -sTCP:LISTEN 2>/dev/null || true )"
	fi
	if [[ -z "$$pids" ]] && command -v fuser >/dev/null 2>&1; then
		pids="$$( fuser "$$port"/tcp 2>/dev/null | tr ' ' '\n' | awk 'NF { print }' || true )"
	fi
	if [[ -z "$$pids" ]]; then
		echo "Could not find a local emulator process for $$serial. It may still need to be stopped manually." >&2
		exit 1
	fi
	kill $$pids
	echo "Sent SIGTERM to emulator process(es): $$pids"

connect-scrcpy:
	if ! command -v "$(SCRCPY)" >/dev/null 2>&1; then
		echo "scrcpy executable not found: $(SCRCPY)" >&2
		exit 1
	fi
	echo "Waiting for a running emulator..."
	serial=""
	for _ in $$(seq 1 "$(SCRCPY_WAIT_SECONDS)"); do
		serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
		if [[ -n "$$serial" ]]; then
			break
		fi
		sleep 1
	done
	if [[ -z "$$serial" ]]; then
		echo "Timed out waiting for a running emulator." >&2
		exit 1
	fi
	exec "$(SCRCPY)" -s "$$serial" $(SCRCPY_FLAGS)

run-emulator:
	avd_package='$(AVD_PACKAGE)'
	sdk_root="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-}}"
	image_dir="$${sdk_root:+$$sdk_root/$${avd_package//;/\/}}"
	if [[ -z "$$sdk_root" || ! -d "$$image_dir" ]]; then
		echo "Missing Android emulator system image: $$avd_package" >&2
		echo "Expected under: $${image_dir:-<ANDROID_HOME is not set>}" >&2
		echo "Install the system image in your system Android SDK/Android Studio tools." >&2
		exit 1
	fi
	$(GRADLE) :app:assembleDebug
	$(ADB) start-server >/dev/null
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		avd="$(AVD_NAME)"
		if ! $(EMULATOR) -list-avds | grep -Fxq "$$avd"; then
			echo "AVD '$$avd' does not exist." >&2
			echo "Create it with: make create-avd AVD_NAME=$$avd" >&2
			echo "Available AVDs:" >&2
			$(EMULATOR) -list-avds >&2
			exit 1
		fi
		emulator_flags='$(EMULATOR_FLAGS)'
		if [[ " $$emulator_flags " != *" -no-window "* && " $$emulator_flags " != *" -qt-hide-window "* ]]; then
			emulator_flags="-no-window $$emulator_flags"
		fi
		if [[ " $$emulator_flags " != *" -no-audio "* && " $$emulator_flags " != *" -noaudio "* && " $$emulator_flags " != *" -audio "* ]]; then
			emulator_flags="-no-audio $$emulator_flags"
		fi
		echo "Starting emulator AVD '$$avd' for scrcpy..."
		echo "Logging emulator output to $(EMULATOR_LOG)"
		nohup $(EMULATOR) -avd "$$avd" -netdelay none -netspeed full $$emulator_flags > "$(EMULATOR_LOG)" 2>&1 &
	fi

	echo "Waiting for emulator device..."
	for _ in {1..120}; do
		serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && ($$2 == "device" || $$2 == "offline") { print $$1; exit }' )"
		if [[ -n "$$serial" ]]; then
			break
		fi
		sleep 2
	done
	if [[ -z "$$serial" ]]; then
		echo "Timed out waiting for emulator. See $(EMULATOR_LOG)." >&2
		exit 1
	fi

	$(ADB) -s "$$serial" wait-for-device
	echo "Waiting for Android boot to complete on $$serial..."
	for _ in {1..180}; do
		booted="$$( $(ADB) -s "$$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true )"
		if [[ "$$booted" == "1" ]]; then
			break
		fi
		sleep 2
	done
	if [[ "$${booted:-}" != "1" ]]; then
		echo "Timed out waiting for Android boot. See $(EMULATOR_LOG)." >&2
		exit 1
	fi

	if [[ "$(SCRCPY_CONNECT)" != "0" ]]; then
		if command -v "$(SCRCPY)" >/dev/null 2>&1; then
			echo "Connecting with $(SCRCPY). Logging scrcpy output to $(SCRCPY_LOG)"
			nohup "$(SCRCPY)" -s "$$serial" $(SCRCPY_FLAGS) > "$(SCRCPY_LOG)" 2>&1 &
		else
			echo "scrcpy executable not found: $(SCRCPY)" >&2
		fi
	fi

	$(ADB) -s "$$serial" install -r "$(DEBUG_APK)"
	$(ADB) -s "$$serial" shell am start -n "$(APP_ID)/$(MAIN_ACTIVITY)"
