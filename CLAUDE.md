# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
The website (freemytv.io) is in `docs/`: static HTML, CSS and JS, no build step. A push to `main` that changes it
publishes it to GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (English) and `docs/pt/index.html`
(Brazilian Portuguese) are the same page in two languages, so change both; visitors from Brazil land on the
Portuguese one by themselves, and the PT/EN switch in the menu is remembered.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone (one step at a time)

The person you're helping may not be technical. Keep it short, friendly, and do **one short step at a time**, waiting for their reply before advancing:

1. **Ask them to enable USB debugging & find the TV IP (if not given yet):**
   - Settings → System → About → press OK 7 times on *Build* / *Android TV OS build* until developer mode activates.
   - Back in System → *Developer options* → turn on *USB debugging*.
   - Check *Network & Internet* for the TV's local IP address and share it with you.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin@17 android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: WSL, then as on Linux.
3. **Settings (`config.properties`):** run `./build.sh` once if needed (it copies `config.example.properties`). Fill in:
   - `team=` (`flamengo`, `palmeiras`, `corinthians`, `sao-paulo`, `vasco`, `fluminense`, `botafogo`, `gremio`, `internacional`, `cruzeiro`, `atletico-mg`, `bahia`, `athletico-pr`, `santos`, `bragantino`, `coritiba`, `vitoria`, `mirassol`, `remo`, `chapecoense`, or `none`).
   - If weather is wanted: `weather_enabled=true`, `city=`, `latitude=`, `longitude=` (decimal degrees for sunset + forecast). If they chose no weather: `weather_enabled=false` and `city=`.
   - `tmdb_region=`, `language=`, `currency=`, `temperature=`, and `ps5_hdmi_volume=true|false`.
4. **The TMDB key is theirs and secret.** If they want movie/show recommendations, guide them to create a free key
   (`themoviedb.org` → Settings → API) and paste it after `tmdb_key=` in `config.properties` themselves (or put it only
   in `config.properties`, never print or commit it). Without a key everything works except TMDB picks & screensaver backdrops.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Connect & Install:** `adb connect <IP>:5555`. Tell them to accept *Allow USB debugging?* on the TV screen and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`.
7. **Tell them what the switch does** before activating: the home screen and screensaver change, bloatware/promo apps
   are safely disabled (`pm disable-user`, never uninstalled), and *Original* brings it all back.
8. **Activate Home Mode:**
   - Android 12+ (API 31+): have them open *Free My TV* → *Início / Home Screen* → *Free My TV* on the TV and accept the localhost ADB prompt.
   - Android 11 (API 30, e.g. TCL): `HomeMode` delegates to `LegacyHomeBridge` (`WRITE_SECURE_SETTINGS` can be granted via `adb shell pm grant io.freemytv android.permission.WRITE_SECURE_SETTINGS`). For **zero-flicker** Home button on TCL Android 11 (when the user asked for bloatware/launcher optimization), you can disable the stock home launcher via ADB: `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`, `adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith`, `adb shell cmd package set-home-activity io.freemytv/.HomeActivity`, plus enable `LegacyHomeBridge` accessibility service. Reversing is `pm enable` + `set-home-activity`.
   - Optional AdGuard Private DNS (only if the user asked for it): `adb shell settings put global private_dns_mode hostname && adb shell settings put global private_dns_specifier dns.adguard-dns.com`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

TVs that don't run Google TV or Android TV (Samsung, LG and others): the way is a Google TV streamer on HDMI
(Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 30 (Android 11), targetSdk 33. No Gradle, no libraries: only the Android SDK.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text is bilingual, `Ui.tr("pt", "en")`: add both. Comments in English, short, only where the why
  isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.

## Always

- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.

## TCL performance branch

README.md documents this branch; README.upstream.md preserves upstream instructions. Private configuration, signing keys, generated output and personal APKs must remain local. Historical benchmarks are before/after local optimization, not a comparison to current upstream. `ClubData` supports configuring Brazilian Série A clubs via `team=` in `config.properties` (or `none`), optional weather via `weather_enabled=`, and optional PS5 HDMI-1 volume via `ps5_hdmi_volume=`. Do not disable debugging on the owner's TV: they explicitly requested that it remain on.
