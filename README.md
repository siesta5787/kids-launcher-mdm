<!-- Shields from shields.io -->
[![][shield-license]][license]

# OCM Launcher

*Open · Clean · Minimal*

OCM Launcher is an Android home screen that lets you launch apps using swipe gestures and
button presses. It is *minimal, efficient and free of distraction* — with an added opt-in
**Minimalist Mode**: a plain text list of a handful of chosen apps as the home screen, with
every other gesture locked out except a long click back into settings (or left fully open,
if you just want the look without the lockdown).

This is a private, personal fork — it is not published anywhere and is not seeking outside
contributions or bug reports.

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


OCM Launcher is a fork of [Josia Pietsch's µLauncher][ulauncher-repo], which is itself a
fork of [finnmglas's app Launcher][original-repo].

## Features

By default, OCM Launcher only displays the date, time and a wallpaper.
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
 - open OCM Launcher settings,
 - toggle private space lock,
 - lock the screen,
 - toggle the torch,
 - volume up / down,
 - go to previous / next audio track.



OCM Launcher is compatible with [work profile](https://www.android.com/enterprise/work-profile/),
so apps like [Shelter](https://gitea.angry.im/PeterCxy/Shelter) can be used.

---
  [original-repo]: https://github.com/finnmglas/Launcher
  [ulauncher-repo]: https://github.com/jrpie/launcher

<!-- Shields and Badges -->

  [shield-license]: https://img.shields.io/badge/license-MIT-007ec6?style=flat
  [license]: LICENSE
