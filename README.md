# CineMaxNexus

CineMax Nexus 3.2.2 is a local media player for Android built with Java, Media3
and Room. Browse videos and music, resume playback, manage playlists, and
use mini-player controls while browsing. The video player supports dual
subtitles, speed and pitch controls, and A–B repeat. The study workspace
adds timestamped notes, clip export, and optional offline English speech
indexing. The interface supports English and Farsi.

## Build

Open this folder in Android Studio and let it configure the local SDK.
Install Android SDK Platform 37. The Gradle daemon uses JDK 25, and the app
source targets Java 17. The app supports Android 7.0 (API 24) and later.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux, use `./gradlew`. The debug APK is under
`app/build/outputs/apk/debug/` and uses standard Android debug signing.
An installation signed with a different key cannot be updated with this APK.

`assembleRelease` builds a minified, unsigned APK by default. To sign it,
create an ignored `keystore.properties` file at the project root with
`storeFile`, `storePassword`, `keyAlias`, and `keyPassword` pointing to your
own release signing credentials.

See [the 3.2.2 validation notes](docs/VALIDATION-3.2.2.md) for previous
device checks and [speech dependency notices](app/libs/README.txt) for
the bundled Vosk and JNA libraries. The English speech model is downloaded
separately.
