# SkyCop Android App

This native React Native app receives the SkyCop website camera feed over WebRTC. It does not open or request the phone's camera. The release APK runs standalone; it does not need Expo Go, a browser page, or a Metro server after installation.

## Local emulator test

1. In Android Studio, open **Device Manager** and start the existing `Pixel_9_Pro` virtual device.
2. In a terminal, start the local signaling relay:

   ```sh
   cd signaling-server
   npm install
   npm start
   ```

3. Build and install the app directly in the emulator:

   ```sh
   cd mobile
   npm run android -- --variant release
   ```

   This creates a native Android app with WebRTC included. Do not open the Expo Go app or `localhost:8081` to run the release build.

4. On the Mac, start the website with `npm run dev`. In the browser, open its local Vite URL and allow the website to use the Mac camera.
5. In the Android app, leave the signaling address as `ws://10.0.2.2:8787`, enter the 32-character pairing code shown by the website, and tap **Connect to website camera**.
6. On the website, start the camera preview and tap **Connect Android viewer**. The app should change from **NO SIGNAL** to the live feed.

The Android emulator reaches the Mac through `10.0.2.2`. A physical phone on the same Wi-Fi can use the Mac's LAN address for local testing, for example `ws://192.168.1.20:8787`.

Port `8081` is only used by Metro during debug development and hot reload. The installed release app includes its JavaScript bundle and does not connect to that port.

## Run from Android Studio

Open this folder's `android/` directory in Android Studio and run the `app` configuration. The native `android/` directory is generated locally by `npx expo prebuild --platform android` and is ignored by Git; regenerate it after cloning if needed.

## Install on a physical Android phone

The standalone release APK is written to `mobile/android/app/build/outputs/apk/release/app-release.apk`. Copy it to the phone and open it to install; Android may ask you to allow installs from that file source. This build is signed with the development key for testing.

With USB debugging enabled and the phone connected, install it with:

```sh
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
```

For live viewing, keep the phone and Mac on the same Wi-Fi. In the app, replace the emulator address `ws://10.0.2.2:8787` with the Mac's LAN address, for example `ws://192.168.1.20:8787`. Allow inbound port `8787` through the Mac firewall if prompted.

## Signaling security

The website and app can be on different networks, but the local relay above cannot do that. GitHub Pages is static hosting; it cannot host the signaling or TURN service. For internet use:

1. Deploy `signaling-server/` to a Node host that supports WebSocket upgrades and TLS termination.
2. Set `ALLOWED_ORIGINS` on the server to the exact GitHub Pages origin, for example `https://OWNER.github.io` (no path).
3. Configure a public TURN service, such as coturn with `use-auth-secret`, then set `TURN_URLS` and the matching `TURN_SECRET` on the signaling host only. Optionally set `TURN_TTL_SECONDS`; it defaults to one hour. Open the TURN service's UDP/TCP relay ports.
4. Add a GitHub Actions repository variable named `VITE_SIGNALING_URL` with the public endpoint, for example `wss://signal.your-domain.example`. The Pages workflow injects this when it builds the site.
5. Build the Android APK with `EXPO_PUBLIC_SIGNALING_URL=wss://signal.your-domain.example npm run android -- --variant release`. Alternatively, change the signaling address in the installed app.

STUN alone cannot traverse every carrier NAT or firewall. The signaling service returns short-lived TURN credentials to the paired clients; never put `TURN_SECRET` in GitHub, website variables, or the APK. The random 128-bit pairing code is a temporary bearer secret, not a user account. Add authentication, rate limiting, and operational monitoring before production deployment.

The local relay and `ws://` emulator address are only for development. The public website uses `wss://` because it is served over HTTPS.
