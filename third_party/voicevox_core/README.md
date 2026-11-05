# VOICEVOX CORE binaries

This directory contains official VOICEVOX CORE 0.17.1 binaries downloaded from
the [VOICEVOX/voicevox_core 0.17.1 release](https://github.com/VOICEVOX/voicevox_core/releases/tag/0.17.1).
VOICEVOX Engine is not included.

## JVM package

`java_packages/` at the repository root is the local Maven repository for the
official Java API package. Gradle can resolve these coordinates:

- Desktop (Windows, macOS, Linux): `jp.hiroshiba.voicevoxcore:voicevoxcore:0.17.1`
- Android: `jp.hiroshiba.voicevoxcore:voicevoxcore-android:0.17.1`

The desktop JAR contains the Java API native bridge for Windows x86/x64,
macOS x64/arm64, and Linux x64/arm64. The Android AAR is also present, but the
Android app currently remains pinned to its existing 0.16.4 AAR.

The official `java_packages.zip` SHA-256 was verified before extraction:

```text
d30d4d3ab782f033c246285e2c46253633837c69d8a8d0b376cc3f85d6fcc7a1
```

## iOS and macOS XCFramework

`0.17.1/voicevox_core-xcframework-0.17.1.zip` is the official XCFramework
archive. It contains device and simulator slices for iOS arm64 and a universal
macOS arm64/x86_64 framework. The archive is kept intact because it contains
framework symlinks that should be extracted on macOS when integrating it into
the Xcode project.

The downloaded archive SHA-256 is:

```text
d01e1d511895944a86857d736bc0e0a1a079a4ed68f985477f4f327bdb22ec61
```

## License

VOICEVOX CORE is MIT-licensed. The downloaded binary and voice models have
additional runtime/model terms; see the upstream release and
[`docs/VOICEVOX_TERMS.md`](../../docs/VOICEVOX_TERMS.md) before distribution.
