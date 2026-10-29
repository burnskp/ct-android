# Couch Tasks Android

Android task app with local Room storage, Compose UI, share-target import, and optional mTLS sync. Requires Android 16 (API 36) or newer. Tasks work locally without a server or account.

## Development

Use JDK 17 and the checked-in Gradle wrapper. Building and testing require Android SDK Platform 37, Build Tools 37.0.0, and an API 37 Play Store system image. The cloister dev sandbox supplies the Android tools, emulator, scrcpy, and a per-project `~/.gradle`; if its SDK lacks the required packages, install them in a writable SDK and point `ANDROID_HOME` to it. The Nix shell (`nix develop`, or direnv via `.envrc`) only adds the pre-commit formatting hook.

The app compiles against and targets API 37, and the default test emulator runs API 37.

Outside the sandbox:

1. Install JDK 17 and Android Studio or Android SDK command-line tools.
2. Install Android SDK Platform 37 and Build Tools 37.0.0 using SDK Manager. With command-line tools, run `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"` and accept licenses with `sdkmanager --licenses`.
3. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your SDK directory. Alternatively, set `sdk.dir` in your ignored `local.properties`. Remove stale SDK paths when switching installations.
4. In Android Studio, select JDK 17 under Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK.

On Linux/macOS:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Optional Make targets require Bash and GNU Make:

```sh
make check            # unit tests, lint, debug build
make connected-check  # instrumented tests; boots and stops the emulator if no device is connected
make verify           # both checks
make sideload         # minified APK signed with the local debug key
make release          # minified APK signed with explicit release credentials
```

Install the emulator image with `sdkmanager "system-images;android-37.0;google_apis_playstore;x86_64"`.

To try the app in an emulator (requires KVM and the `system-images;android-37.0;google_apis_playstore;x86_64` image):

```sh
make start-emulator   # boots a headless API 37 emulator, installs and launches the app, opens scrcpy
make stop-emulator    # shuts down the emulator and scrcpy
```

The first `make start-emulator` creates the AVD in `$ANDROID_AVD_HOME` (the sandbox's per-project `~/.android/avd`, or the ignored `.avd/` directory when unset), which also holds the emulator log. Running it again while the emulator is up just reinstalls and relaunches the app. Pass `SCRCPY=0` to skip the viewer or `EMULATOR_FLAGS=...` to add emulator flags.

Before dependency maintenance, run `make check`, update one dependency group at a time, then run `make verify` with a device/emulator. Hosted CI runs unit tests, debug and release lint, debug and unsigned release builds, and instrumented-test compilation on pushes to main, pull requests, and manual dispatch:

```sh
./gradlew --continue testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
```

CI retains test/lint reports and successful debug APKs for seven days. Instrumented tests still run locally with `make connected-check`; CI compiles them but does not run an emulator.

Dependabot checks Gradle dependencies and GitHub Actions every Monday, batching all available upgrades (including major versions) into one version-update PR per ecosystem, with at most one such PR open per ecosystem. Security updates use separate batches when Dependabot security updates are enabled in repository settings. Updates require review and are not automatically merged.

## Sync and your data

Sync uses the self-hosted [Couch Tasks server](https://github.com/burnskp/couch-tasks). Follow the [Android sync setup guide](android-sync-client-guide.md) for server links, certificate preparation, and app configuration.

Each sync sends the complete local project and task snapshot to your configured server: project names, task titles and notes, status/order, timestamps, identifiers, revisions, and deletion records. All authorized clients of a server share one dataset; there are no separate private user accounts. TLS protects transport; the server receives the task contents. Sync is not a backup or edit history.

Certificate passwords are encrypted using Android Keystore. Settings, password preferences, and the local task database are excluded from Android cloud backup and device transfer. Preserve your tasks through a verified sync before replacing an installation or clearing app data.

## APK releases

See [release signing and publication](docs/releases.md) for local signing and automated GitHub tag releases. `make sideload` produces `app/build/outputs/apk/release/app-release-sideload.apk` using the local Android debug key. This is for local development, not public distribution.

## License

[MIT](LICENSE). Existing third-party notices, including the Gradle wrapper notices, retain their original terms.
