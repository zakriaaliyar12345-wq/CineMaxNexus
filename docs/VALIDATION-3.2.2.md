# CineMax Nexus 3.2.2 validation

The earlier 3.2.1 release APK crashed when Room tried to instantiate `StudyDatabase_Impl`. Version 3.2.2 retains the generated Room constructors, refreshes music artwork after the library scan finishes, displays music load errors in landscape, and catches failures in the scheduled stale-media job.

## Results

- JVM: 53 tests, 0 failures.
- Android emulator: 30 tests, 0 failures. Covered background video/music, service handoff, seek preview, English/Farsi rotation, subtitles, music library/queue/lyrics, offline speech, study features, Room, playlists, media information, and sleep timer.
- Android lint: 0 errors, 147 warnings. The warnings are existing style/performance advisories; none block the build.
- Debug and release APK builds succeeded in the original local validation. Those builds shared a local signing certificate. This repository uses Android's standard debug signing and keeps release credentials local.
- Minified release APK installed on an isolated Android 37 emulator. The library opened and a local video reached `PlayerActivity` with the app process alive. The scheduled cleanup job ran, with no `AndroidRuntime` crash entry.

The Android tests used a valid 15.15-second local MP4 and a staged English Vosk model. The first test attempt used a zero-duration clip, and the first full run was blocked by Android's one-time full-screen tutorial. The clean rerun above is the validation result.

## Limits

These checks cover the app's automated and local release flows. Codec, network
stream, and device compatibility can vary. Private-folder and settings flows
have no dedicated end-to-end UI test in this suite.
