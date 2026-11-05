# Panta Connect

Panta Connect is a maintained KDE Connect Android fork with image clipboard reception. It builds on Jakob Müller Galvez's `2321631060249313c9273b3ce4bc027a44652bc8` image-clipboard implementation and remains compatible with stock KDE Connect Desktop. It is an independent project, not an official KDE release.

The primary workflow is copying a screenshot on the desktop and inserting it into an Android app through the keyboard's image clipboard suggestion. Phone-to-desktop Share image remains available. Android's restrictions on background clipboard reads still apply to automatic phone-to-desktop clipboard sharing.

## Install the beta

Add [the signed custom repository](https://panta82.github.io/panta-connect/repo/?fingerprint=912391A240B2C0ED437831EE659B6291D640FCD62A31C50876DBCCCA25F6E58D) in F-Droid, then install Panta Connect. You can also download the APK and matching source from [GitHub releases](https://github.com/panta82/panta-connect/releases). This repository is separate from the main F-Droid catalogue. The [project page](https://panta82.github.io/panta-connect/) includes the repository fingerprint.

The fork uses application ID `net.pantas.connect`, with `.debug` for development builds. It installs beside official KDE Connect and needs its own pairing. Its private release signing key is separate from KDE's keys. Pair Panta Connect with your desktop, allow background operation, copy a desktop screenshot, then select the keyboard's Image suggestion in a supported destination app. Gboard with X is the tested combination. Avoid running both apps' clipboard plugins for the same desktop while testing.

Version `0.1.0-beta.2` passed all 162 unit tests and an F-Droid upgrade from beta 1 on an Xperia 10 III with Android 11. The pairing survived the update and an app restart. Desktop screenshot insertion into X passed in foreground and after a short screen-off interval. Long-term Doze/reboot reliability and other devices still need testing. Xperia is the development device; Jelly Max is reserved for a later release candidate. Report fork issues in [this repository's tracker](https://github.com/panta82/panta-connect/issues).

Image reception accepts PNG, JPEG, WebP, and GIF. Transfers must declare a positive size of at most 50 MiB. Images are checked without allocating their full pixel buffers and are limited to 32 megapixels and 32768 pixels per dimension. The private image cache is limited to 200 MiB and 64 files. The current image and a one-minute paste grace period are protected; old images expire after a day when another image arrives. If protected files fill the cache, new reception is rejected instead of deleting images still being pasted.

Only the latest pending image is retained, and a completed transfer cannot replace a newer clipboard update. The transport has a 30-second read timeout. Received clipboard images are temporary, not a photo archive.

## Build and test

Use JDK 21 and the Android SDK requested by the Gradle build. On the development host, `source ~/.local/share/kdeconnect-toolchain/env.sh` selects the installed tools. The host's `run-tests` wrapper limits resource use; elsewhere, run the enclosed Gradle command directly:

```bash
run-tests './gradlew --no-daemon --max-workers=1 --no-parallel testDebugUnitTest assembleDebug'
```

Release signing configuration is read from `$PANTA_CONNECT_SIGNING_PROPERTIES`, or `~/.config/panta-connect/signing.properties` by default. Keep that file and the referenced keystore private and backed up. Its fields are `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. The checked-in upstream debug key is not used. To build a signed release with the configured private key:

```bash
run-tests './gradlew --no-daemon --max-workers=1 --no-parallel testDebugUnitTest assembleRelease'
```

Release versions can be set with `-PforkVersionCode=2 -PforkVersionName=0.1.0-beta.2`; version codes must increase for F-Droid updates. Custom F-Droid repository metadata is in `fdroid/metadata/`. Publication requires the signed APK, its corresponding source archive, a signed repository index, and a chosen HTTPS host. The main F-Droid catalogue is a separate review process.

With `fdroidserver` and Python's PyYAML installed, stage a repository locally using `python3 tools/stage_fdroid.py build/outputs/apk/release/kdeconnect-android-release.apk --repo-url http://127.0.0.1:8768/repo`. F-Droid server 2.4.5 needs `pkg_resources`; a working isolated install is `uv tool install --with 'setuptools<81' fdroidserver`. The staging command checks the APK signature and identity, bundles the current source tree with its build version, and generates signed indexes. Run it immediately after building, before changing the source. It keeps private signing state under `~/.config/panta-connect/` and `~/.local/share/panta-connect/fdroid/`. Only the generated `repo/` directory is public content, copied to `build/fdroid-public/repo/`. Use the final HTTPS URL when preparing publication; the localhost URL is only for device tests.

See [the release publication instructions](fdroid/PUBLISHING.md) for GitHub Pages deployment. Signing happens locally; GitHub hosts the already signed files.

The upstream code and contributor credits remain under their original licenses. The upstream information below describes KDE Connect, including its official distribution channels.

# KDE Connect - upstream Android app

KDE Connect is a multi-platform app that allows your devices to communicate (eg: your phone and your computer).

## (Some) Features
- **Shared clipboard**: copy and paste between your phone and your computer (or any other device).
- **Notification sync**: Read and reply to your Android notifications from the desktop.
- **Share files and URLs** instantly from one device to another.
- **Multimedia remote control**: Use your phone as a remote for Linux media players.
- **Virtual touchpad**: Use your phone screen as your computer's touchpad and keyboard.

All this without wires, over the already existing Wi-Fi network, and using TLS encryption.

## About this app

This is a native Android port of the KDE Connect Qt app. You will find a more complete readme about KDE Connect [here](https://invent.kde.org/network/kdeconnect-kde/).

## How to install this app

You can install this app from the [Play Store](https://play.google.com/store/apps/details?id=org.kde.kdeconnect_tp) as well as [F-Droid](https://f-droid.org/repository/browse/?fdid=org.kde.kdeconnect_tp). Note you will also need to install the [desktop app](https://invent.kde.org/network/kdeconnect-kde) for it to work.

## Contributing

A lot of useful information, including how to get started working on KDE Connect and how to connect with the current developers, is on our [KDE Community Wiki page](https://community.kde.org/KDEConnect)

For bug reporting, please use [KDE's Bugzilla](https://bugs.kde.org). Please do not use the issue tracker in GitLab since we want to keep everything in one place.

To contribute patches, use [KDE Connect's Gitlab](https://invent.kde.org/network/kdeconnect-android/).
On Gitlab (as well as on our [old Phabricator](https://phabricator.kde.org/tag/kde_connect/)) you can find a task list with stuff to do and links to other relevant resources.
It is a good idea to also subscribe to the [KDE Connect mailing list](https://mail.kde.org/mailman/listinfo/kdeconnect).

Please know that all translations for all KDE apps are handled by the [localization team](https://l10n.kde.org/). If you would like to submit a translation, that should be done by working with the proper team for that language.

## License
[GNU GPL v2](https://www.gnu.org/licenses/gpl-2.0.html) and [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html)

KDE's official Android source is hosted in the [KDE project repository](https://invent.kde.org/network/kdeconnect-android/). This GitHub repository contains the independent Panta Connect fork.
