# SkyCop

SkyCop streams a live drone camera from a web page to the SkyCop phone app, which shows it side by side for a VR headset. The app also contains a VR flight simulation of the drone over a city with live traffic. The app runs on Android and iOS.

## How it works

1. On the device with the camera, open the SkyCop website (GitHub Pages) and tap **Start camera**. The page shows a pairing code and a QR code.
2. On the phone, scan the QR code with the camera app, or open SkyCop and type the code.
3. When the preview says **Live**, tap **Watch in VR** and put the phone in the headset.

Both devices must be on the same Wi-Fi. Nothing has to run on a computer.

GitHub Pages is HTTPS, so the page cannot talk to a phone on the local network directly. The two sides exchange their WebRTC connection details once through [ntfy.sh](https://ntfy.sh), a free public message relay. Every message is encrypted with AES-256-GCM using a key derived from the pairing code, so the relay sees only ciphertext. The video goes directly between the two devices. One phone watches at a time; a second phone with the same code connects when the first one leaves. The page and the app keep the same code between sessions, and the app reconnects to it by itself when opened; **New code** on the page makes another one. Treat the code like a password: anyone with it on the same network can watch the feed.

Guest Wi-Fi, client isolation and some firewalls block device-to-device connections even on one network. There is no TURN relay. The page offers one Google STUN server for the few networks where that helps.

## Flight simulation

Tap **Open** next to Flight simulation in the app. It is a native engine (Kotlin + OpenGL ES on Android, Swift + Metal on iOS) with 250 Hz flight physics, head tracking, gamepad support, and procedurally painted textures. When a live feed is connected, gamepad **X** switches between the simulation and the live video. See [mobile/README.md](mobile/README.md#flight-simulation) for the controls.

## Run the website locally

Install Node.js 20.19+ or 22.12+, then from this folder:

```sh
npm install
npm run dev
```

Open the printed localhost URL and allow camera access. The camera needs localhost or HTTPS; opening the file directly will not work. The QR code points at the page's own address, so for a phone to use the QR code the page must be served from an address the phone can open (GitHub Pages). Typing the code works from anywhere.

## Deploy

Select **GitHub Actions** under **Settings > Pages > Build and deployment**. The workflow builds and deploys every push to `main`.

See [mobile/README.md](mobile/README.md) for building and installing the Android and iOS apps.
