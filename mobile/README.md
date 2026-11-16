# SkyCop Android App

This native React Native app receives the SkyCop website camera feed over WebRTC. It does not open or request the phone's camera. The release APK runs standalone and does not need Expo Go or Metro after installation.

## Pair with the website

1. Connect the website device and Android device to the same network. They must be allowed to connect directly to each other.
2. Start the signaling host on the Mac as described in the root README.
3. On the website, start the camera and select **Connect Android viewer**.
4. Enter the displayed meeting code in Android. Use `ws://10.0.2.2:8787` as the host for the emulator, or `ws://MAC_LAN_IP:8787` for a physical phone.
5. Tap **Join camera**. To stop sharing, disconnect in the app or stop the website camera.

The meeting code grants access to the camera feed; share it privately. GitHub Pages requires a public `wss://` signaling address: use the temporary Mac tunnel described in the root README, and enter the same address in the website and Android app. No TURN relay is included, so guest networks, firewalls, and device/browser combinations can prevent direct connections even on the same Wi-Fi.

## Run from Android Studio

Open this folder's `android/` directory in Android Studio and run the `app` configuration. The native `android/` directory is generated locally by `npx expo prebuild --platform android` and is ignored by Git; regenerate it after cloning if needed.

## Install on a physical Android phone

The standalone release APK is written to `mobile/android/app/build/outputs/apk/release/app-release.apk`. Copy it to the phone and open it to install; Android may ask you to allow installs from that file source. This build is signed with the development key for testing.

With USB debugging enabled and the phone connected, install it with:

```sh
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
```

The current test APK is built for `arm64-v8a` and uses a development signing key. It is not a Play Store release. A physical phone must support that architecture.
