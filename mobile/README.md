# SkyCop app (Android and iOS)

The app receives the SkyCop website's camera feed over WebRTC and shows it side by side for a VR headset. It never opens the phone's own camera. It also contains the native VR flight simulation.

## Watch the live camera

1. On the camera device, open the SkyCop website and tap **Start camera**.
2. Tap the scan button next to the code field and point it at the website's QR code (it only accepts SkyCop codes), or type the code (for example `abc-def-ghj`) and tap **Connect**. The app remembers the code.
3. When the preview shows **Live**, tap **Watch in VR**.

The live view is drawn by the same native VR renderer as the simulation: the video hangs as a screen in a dark room, stays put while your head moves, and takes the video's own shape, upright, whichever way the camera phone is held, so nothing is cropped. Gamepad **X** or **B** switches to the flight simulation; tap the screen (or press Start) for the menu and **EXIT SIMULATION** to go back to the app.

Both devices must be on the same Wi-Fi. Pairing details pass, encrypted, through free public relays (MQTT brokers, with ntfy.sh as a fallback); video goes directly between the devices. The app keeps retrying if the camera stops or the Wi-Fi drops. The protocol lives in `pairing.ts` (app) and `../src/pairing.ts` (website); keep the two in step.

## Flight simulation

Tap **Open** next to Flight simulation. The simulator lives in [`modules/skycop-sim`](modules/skycop-sim): Kotlin + OpenGL ES 3 on Android, Swift + Metal on iOS, with the same flight model, city, traffic, textures, menu and controls on both (the Swift core is a line-for-line port of the Kotlin core). It runs outside the React Native JavaScript thread, so nothing in the UI can stall it.

The city is a real part of Dhaka, built from OpenStreetMap data: choose **Bashundhara R/A** (around NSU and Jamuna Future Park) or **Mohammadpur** (Town Hall, Asad Gate, Saat Gambuj mosque) under **MAP** in the menu. Every road, building footprint, park, canal and junction is where it is in the real city; building heights are estimated from their type and size, since OpenStreetMap rarely has them for Dhaka. The drone starts on a dock near NSU or the Saat Gambuj mosque. Take off with **A**. **EXIT SIMULATION**, second in the menu, goes back to the app. The screen shows only the essentials, small, in the corners: flight mode, battery and flight time, speed and height. In VR the camera follows your head one-to-one: turn, look up or down, or tilt, and the view does the same. Press **R3** (or Select) while looking straight ahead to set your forward direction.

| Control | Pilot layout (default) | Classic layout |
| --- | --- | --- |
| Left stick | Up/down, turn | Forward/back, turn |
| Right stick | Move in any direction | Look around |
| LT / RT | Down / up | Down / up |
| LB / RB | Slower / faster flight mode | Slide left / right |
| D-pad left / right | Flight mode | Flight mode |
| D-pad up / down | Camera height (camera tilt when head tracking is off) | Same |
| A | Take off / land | Take off / land |
| B | Fly to the nearest dock and land (press again to cancel) | Same |
| X | Switch to live camera and back | Switch to live camera and back |
| Y | Chase / drone camera / survey view | Chase / drone camera / survey view |
| R3 or Select | Set "forward" to where you are looking | Same |
| Start | Menu | Menu |

Battery and docks:

- Junctions of the real road network have charging docks on masts at their corners, about one every 150 m (77 in Bashundhara, 91 in Mohammadpur).
- A full battery gives about 10 minutes of hovering (a bit less when flying hard). The corner shows the charge and the flight time left; while returning, the distance to the dock.
- Landed on a dock, the drone charges from empty to full in 10 seconds.
- Below 8 % it flies to the nearest dock by itself (push a stick to take over). If the battery runs flat in the air, the drone drops and comes back on the dock nearest to where it fell. A crash does the same.

Traffic is 1,400 to 1,600 Dhaka vehicles (cars, green CNG auto-rickshaws, cycle rickshaws with their pullers, local buses, motorbikes with riders and microbuses) driving on the left along the real roads. They pick their own turns, follow the vehicle ahead, take turns at junctions (signals at the big crossings), never enter a junction whose exit is full, and come back in at the map's edge. Buses, CNGs and rickshaws stop for passengers and rickshaws crawl, so the main roads jam up and clear on their own.

Flight modes:

- **CINE**: slow and gentle, with obstacle braking.
- **NORMAL**: balanced, with obstacle braking.
- **SPORT**: fast, no obstacle braking, so you can crash.
- **ATTI**: no GPS hold, so the drone drifts with momentum and wind.

The menu has the layout switch, VR or full screen, head tracking, lens correction and spacing, comfort vignette, sensitivity, deadzone and more. Its bottom edge shows live stick and trigger positions. If a controller's axes look wrong there, run **CALIBRATE CONTROLLER**; the result is saved per controller. In full-screen mode without a controller, on-screen sticks appear.

Dhaka touches: six to ten storey apartment blocks with water tanks and stair rooms on the roof, shop signboards along the bigger roads, tin sheds in the lanes, mosques with domes and minarets (Saat Gambuj has its seven domes), concrete poles with sagging wires along the streets, raised medians on the main roads, and the rest of the city continuing past the map's edge into the haze. Maps are rebuilt from OpenStreetMap with `python3 modules/skycop-sim/maps/build_maps.py`. Map data © OpenStreetMap contributors (ODbL); the credit is shown in the simulator menu.

The city uses 18 surface textures with relief (brick, plaster, glass curtain wall, shopfronts, asphalt, paving, grass, bark, carbon fiber and more). They are painted by `core/Textures.kt` on first open, so there are no image assets to ship or license. Detail, shadows and resolution scale automatically to the phone's GPU and temperature.

The simulator is a local Expo module, so `npx expo prebuild` keeps it. Run its physics, input and traffic tests (needs Android Studio, no device):

```sh
modules/skycop-sim/tests/run.sh
```

Print live telemetry from a connected device with:

```sh
adb shell setprop log.tag.SkyCopSim DEBUG
adb logcat -s SkyCopSim
```

## Build for Android

Open this folder's `android/` directory in Android Studio and run the `app` configuration. The native `android/` directory is generated locally by `npx expo prebuild --platform android` and is ignored by Git; regenerate it after cloning if needed.

## Install on a physical Android phone

The standalone release APK is written to `mobile/android/app/build/outputs/apk/release/app-release.apk`. Copy it to the phone and open it to install; Android may ask you to allow installs from that file source. This build is signed with the development key for testing.

With USB debugging enabled and the phone connected, install it with:

```sh
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
```

The current test APK is built for `arm64-v8a` (`./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a`) and uses a development signing key. It is not a Play Store release. A physical phone must support that architecture.

## Build for iOS

Needs a Mac with Xcode and CocoaPods. From this folder:

```sh
npx expo prebuild --platform ios
cd ios && pod install && cd ..
npx expo run:ios
```

`ios/` is generated and ignored by Git, like `android/`. To install on an iPhone, open `ios/SkyCop.xcworkspace` in Xcode, choose your Apple ID team under **Signing & Capabilities**, connect the iPhone and press Run. iOS asks once for permission to find devices on the local network; allow it, or the live video cannot connect. Gamepads work through Apple's GameController framework (Xbox, PlayStation, MFi and most Bluetooth controllers).
