<!-- Shields from shields.io -->
[![][shield-license]][license]

# Kids Launcher

*Open · Clean · Minimal - and locked down when it needs to be.*

Kids Launcher is an Android home screen for a kid's phone that doubles as a
[Device Owner](https://developer.android.com/work/dpc/build-dpc) parental-control
agent. It pairs with [`kid-phone-server`][server-repo], a self-hosted admin web app a
parent uses to manage allowlisted apps, a bedtime/screen-time schedule, kiosk (lock-task)
mode, WiFi/Bluetooth restrictions, and an offline override PIN for when a phone can't
reach the server at all.

Under the hood it's a fork of [Josia Pietsch's µLauncher][ulauncher-repo] (itself a fork
of [finnmglas's Launcher][original-repo]) - all of that project's gesture-based
navigation and Minimalist Mode are still here and work exactly as before. The MDM/parental
-control functionality is new code added on top, living mostly under
`app/src/main/java/com/kidslauncher/mdm`.

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg"
     alt="screenshot"
     height="400">
     <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg"
     alt="screenshot"
     height="400">
     <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg"
     alt="screenshot"
     height="400">
     <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg"
     alt="screenshot"
     height="400">
     <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg"
     alt="screenshot"
     height="400">
     <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg"
     alt="screenshot"
     height="400">
     <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg"
     alt="screenshot"
     height="400">

## Parental-control features

- **Enrollment** against a [`kid-phone-server`][server-repo] instance via a short,
  one-shot code entered in Settings - no QR scanning required.
- **App allowlist** - suspends/hides everything not explicitly allowed, set from the
  server's admin UI.
- **Kiosk (lock-task) mode**, with per-device control over which system chrome stays
  available while pinned (status bar, notifications, home button, recents, power menu,
  lock screen).
- **Bedtime / screen-time schedule**, enforced even offline against the device's own
  clock.
- **WiFi / Bluetooth restrictions** (open / restricted / disabled).
- **Offline override PIN** - a parent-set failsafe, verified fully offline (PBKDF2
  against a locally cached hash), that temporarily lifts every restriction if the phone
  ever can't reach the server at all.
- **Settings is PIN-gated** behind the same code, so a kid can't tamper with enrollment
  or sync - plus a manual "pause all restrictions" kill-switch as an emergency escape
  hatch.
- **Silent self-update** - the launcher can be updated by the server without any user
  interaction, via `PackageInstaller` (requires Device Owner).

## Launcher features (from upstream)

By default, Kids Launcher only displays the date, time and a wallpaper.
Pressing back or swiping up (this can be configured) opens a list
of all installed apps, which can be searched efficiently.

**Minimalist Mode** (Settings → Launcher → Minimalist Mode) replaces that with a plain text
list of a chosen set of apps. By default every other gesture is disabled except long click,
which always opens settings regardless of what's bound to it — or leave "Allow gestures" on
to keep the list-view look while every gesture still works normally.

The following gestures are available:
 - volume up / down,
 - swipe up / down / left / right,
 - swipe with two fingers,
 - swipe on the left / right resp. top / bottom edge,
 - tap, then swipe up / down / left / right,
 - draw < / > / V / Λ
 - click on date / time,
 - double click,
 - long click,
 - back button.

To every gesture you can bind one of the following actions:
 - launch an app,
 - open a list of all / favorite / private apps,
 - open Kids Launcher settings,
 - toggle private space lock,
 - lock the screen,
 - toggle the torch,
 - volume up / down,
 - go to previous / next audio track.

Kids Launcher is compatible with [work profile](https://www.android.com/enterprise/work-profile/),
so apps like [Shelter](https://gitea.angry.im/PeterCxy/Shelter) can be used.

## License

New code (the MDM/parental-control functionality) is licensed under the [GNU GPLv3 or
later][license]. Code inherited from the upstream Launcher/µLauncher lineage remains
under its original [MIT terms][license-mit].

---
  [server-repo]: https://github.com/siesta5787/kid-phone-server
  [original-repo]: https://github.com/finnmglas/Launcher
  [ulauncher-repo]: https://github.com/jrpie/launcher

<!-- Shields and Badges -->

  [shield-license]: https://img.shields.io/badge/license-GPLv3-007ec6?style=flat
  [license]: LICENSE
  [license-mit]: LICENSE-MIT-UPSTREAM
