SHELL := /bin/bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c

GRADLE ?= ./gradlew
ADB ?= adb

APP_ID := com.couchtasks.android
MAIN_ACTIVITY := com.couchtasks.android.MainActivity
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk
RELEASE_UNSIGNED_APK := app/build/outputs/apk/release/app-release-unsigned.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-sideload.apk
DEBUG_KEYSTORE ?= $(HOME)/.android/debug.keystore

.PHONY: help build release sideload test check connected-check verify clean clean-emulator-cache install launch start-emulator stop-emulator

help:
	@echo "Targets:"
	@echo "  make build          Build the debug APK"
	@echo "  make release        Build a signed public release APK"
	@echo "  make sideload       Build a minified APK signed with the local debug key"
	@echo "  make test           Run debug unit tests"
	@echo "  make check          Run unit tests, lint, and debug build"
	@echo "  make connected-check Run instrumented tests (boots and stops the emulator if no device is connected)"
	@echo "  make verify         Run check and connected-check"
	@echo "  make clean          Clean Gradle outputs"
	@echo "  make clean-emulator-cache Clear app data/cache on the first running emulator"
	@echo "  make install        Install debug APK on the first running emulator"
	@echo "  make launch         Launch the app on the first running emulator"
	@echo "  make start-emulator Boot the headless emulator (creating its AVD if needed), install, launch, and open scrcpy"
	@echo "  make stop-emulator  Stop the emulator and scrcpy"
	@echo ""
	@echo "Options:"
	@echo "  AVD_NAME=name       AVD for start-emulator (default: couch-tasks-api36, stored in .avd/)"
	@echo "  EMULATOR_FLAGS=...  Extra emulator flags for start-emulator"
	@echo "  SCRCPY=0            Do not open scrcpy from start-emulator"
	@echo "  DEBUG_KEYSTORE=file Keystore for make sideload signing (default: $(DEBUG_KEYSTORE))"
	@echo "  ADB=/path/to/adb    adb executable override"

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
		echo "zipalign not found. Set ANDROID_HOME/ANDROID_SDK_ROOT to an SDK with Build Tools 37.0.0." >&2
		exit 1
	fi
	if [[ -n "$$build_tools" && -x "$$build_tools/apksigner" ]]; then
		apksigner="$$build_tools/apksigner"
	elif command -v apksigner >/dev/null 2>&1; then
		apksigner="apksigner"
	else
		echo "apksigner not found. Set ANDROID_HOME/ANDROID_SDK_ROOT to an SDK with Build Tools 37.0.0." >&2
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
	if $(ADB) devices 2>/dev/null | awk '$$2 == "device" { found = 1 } END { exit !found }'; then
		$(GRADLE) connectedDebugAndroidTest
	else
		echo "No connected device; booting the emulator for this run..."
		trap 'bash scripts/emulator.sh stop' EXIT
		bash scripts/emulator.sh boot
		ANDROID_SERIAL="emulator-$${EMULATOR_PORT:-5554}" $(GRADLE) connectedDebugAndroidTest
	fi

verify: check connected-check

clean:
	$(GRADLE) clean

clean-emulator-cache:
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		echo "No running emulator found. Use 'make start-emulator' to start one." >&2
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
		echo "No running emulator found. Use 'make start-emulator' to start one." >&2
		exit 1
	fi
	$(ADB) -s "$$serial" install -r "$(DEBUG_APK)"

launch:
	serial="$$( $(ADB) devices | awk '$$1 ~ /^emulator-/ && $$2 == "device" { print $$1; exit }' )"
	if [[ -z "$$serial" ]]; then
		echo "No running emulator found. Use 'make start-emulator' to start one." >&2
		exit 1
	fi
	$(ADB) -s "$$serial" shell am start -n "$(APP_ID)/$(MAIN_ACTIVITY)"

start-emulator:
	APP_ID="$(APP_ID)" MAIN_ACTIVITY="$(MAIN_ACTIVITY)" bash scripts/emulator.sh start

stop-emulator:
	bash scripts/emulator.sh stop
