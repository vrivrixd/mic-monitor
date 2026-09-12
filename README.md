# Mic Monitor

Streams the audio from your phone microphone to another device over Wi-Fi, with no program
and no audio driver installed on the computer. Listening happens inside the browser.

Unlike similar apps, this one tries to open the microphone in stereo when the device
supports it, and it can also swap the left and right channels.

## How to use

1. Connect the phone and the computer to the same Wi-Fi network.
2. Open the app and tap **Start**.
3. Type the address shown on screen into the browser on the computer.
4. On the page that opens, click **Start**.

Before that click the page requests no audio and takes no listener slot. After it the sound
begins and the controls appear. Gain, microphone, audio channels, buffer and mute all live
on the page itself, and changes apply on both sides at once.

Only one computer listens at a time. Closing the tab, or pressing **Disconnect** on the
page, frees the slot without stopping the phone. Settings on the phone can allow several
computers to listen at the same time.

## Project status

| Item | State |
| --- | --- |
| Minimum Android | 7.0 |
| Format on the wire | 16-bit PCM, 48 kHz |
| Audio buffer | 30 to 1000 ms, typed in |
| Audio channels | mono, stereo, or stereo with the sides swapped |
| Playback in the browser | chunks scheduled on the audio timeline |
| Languages | English, Portuguese (Brazil and Portugal), Spanish, French, Italian, German, Arabic, Hindi, Russian, Vietnamese, Japanese, Chinese (Simplified and Traditional), Korean, Finnish, Danish, Ukrainian, Czech, Turkish |
| Sound card selection from the browser | unavailable over a plain connection |

Choosing the output device from the page requires a secure connection, which this version
does not use. Until then the output is chosen in the volume mixer of the operating system.

## Building

### What you need installed

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9 or newer |
| Android SDK, platform | android-35 |
| Android SDK, build tools | 35.0.0 |

Android Studio ships both the SDK and a JDK, but it is not required. The Android command
line tools plus a standalone JDK 17 work just as well.

### Pointing at the SDK

The project needs to know where the Android SDK lives. Pick either path.

Create a `local.properties` file in the project root holding the SDK path:

```
sdk.dir=/path/to/android/sdk
```

Or set the `ANDROID_HOME` environment variable to the same folder.

If the platform and the build tools are not installed yet, use the SDK manager:

```
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### Producing the APK

From the project root:

```
gradle assembleRelease
```

The file lands in:

```
app/build/outputs/apk/release/MicMonitor-1.1.apk
```

For a debug build, with more talkative logging:

```
gradle assembleDebug
```

### Installing on the phone

With the device plugged in and USB debugging enabled:

```
adb install -r app/build/outputs/apk/release/MicMonitor-1.1.apk
```

Without a cable, copy the APK to the phone and open the file there. Android will ask for
permission to install from an unknown source.

## Signing

The signing key is not part of this repository. Without it the commands above still work
and produce an unsigned APK, which is fine for reading the code but will not install on a
device.

To produce an installable APK, create your own key and put the file at
`app/micmonitor.p12`:

```
keytool -genkeypair -v -storetype PKCS12 -keystore app/micmonitor.p12 \
        -alias micmonitor -keyalg RSA -keysize 2048 -validity 20000
```

Then pass the passwords through environment variables and build:

```
export MIC_MONITOR_STORE_PASSWORD=your-password
export MIC_MONITOR_KEY_ALIAS=micmonitor
export MIC_MONITOR_KEY_PASSWORD=your-password
gradle assembleRelease
```

An APK built with your own key is a different application as far as Android is concerned.
Switching between it and the released builds requires uninstalling first.

## Credits

The Vietnamese translation was reviewed by
[nguyenninhhoang](https://github.com/nguyenninhhoang), who also translated VBRecorder.

## Layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/.../MainActivity.kt` | main screen, menu and dialogs |
| `app/src/main/java/.../HelpActivity.kt` | help screen with the list of topics |
| `app/src/main/java/.../SettingsActivity.kt` | settings screen |
| `app/src/main/java/.../StreamService.kt` | foreground service that keeps the stream alive |
| `app/src/main/java/.../AudioEngine.kt` | microphone capture and gain |
| `app/src/main/java/.../MicServer.kt` | embedded HTTP and WebSocket server |
| `app/src/main/assets/web/` | page served to the browser on the computer |
| `app/src/main/res/values*/strings.xml` | app text, one file per language |
