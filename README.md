# SkyCop

SkyCop previews a website device's camera and provides a native Android viewer for a direct, same-network WebRTC connection.

## Android flight simulation

The detailed city simulation runs in the Android app, not the website. Tap **OPEN 3D VR SIMULATION** in the app to enter landscape stereo mode. A connected standard Bluetooth controller uses the left stick for drone yaw/throttle, the right stick for independent camera look, and the triggers for altitude. The simulation streams nearby city sectors, includes traffic lights and collision bounds, and queues cars at signals. Tap the screen to reveal the temporary HUD; tap **EXIT** to return to the receiver. When a live feed is connected, gamepad **X** switches between simulation and headset video without showing the transition HUD.

## Run locally

Install Node.js 20.19+ or 22.12+, then from this folder:

```sh
npm install
npm run dev
```

Open the localhost URL printed by Vite and allow camera access. `getUserMedia` is available on localhost and HTTPS origins; opening the page directly as a file will not work. Use **Start local preview** to request camera access and **Stop preview** to release it.

## Connect Android

First start the signaling host on your Mac from the repository root:

```sh
npm ci --prefix signaling-server
npm run signaling
```

Start the website with `npm run dev`, then start its camera and select **Connect Android viewer**. On a physical phone on the same Wi-Fi, use `ws://MAC_LAN_IP:8787` as the website's signaling address. Copy the invitation and open it on Android, or paste it into the app's meeting code field, then tap **Join camera**. For the Android emulator, use the displayed meeting code and enter `ws://10.0.2.2:8787` in the app instead, since the website's localhost address refers to a different device. Keep the host running throughout the session.

Meeting codes grant access to the camera feed. Share them privately. The host relays connection information, not video. Video is a direct WebRTC connection with no TURN server; guest Wi-Fi, client isolation, firewalls, and some device/browser combinations can prevent a connection even on the same network.

## GitHub Pages testing with your Mac

GitHub Pages is HTTPS and cannot use the Mac's unsecured `ws://` LAN address. For temporary testing without router port forwarding:

1. Install Node.js, npm, and Cloudflare's tunnel client (`brew install cloudflared`) on the Mac.
2. Run `./start-skycop-host.command` from the repository root and leave it running. It opens the website with the current tunnel address filled in and prints the website link in the terminal.
3. Start the website camera, select **Connect Android viewer**, and copy its invitation.
4. Send the invitation privately to Android. Open the link in the app, or paste it into the app's meeting code field, and tap **Join camera**.

Both devices should be on the same Wi-Fi and able to connect directly for video. This tunnel is publicly reachable, has no uptime guarantee, and gets a new address when restarted. Meeting codes and addresses should be shared privately. The signaling host has no user accounts and is not production-hardened; stop it with Ctrl+C when finished. No TURN relay is included, so client isolation or a firewall can still block video.

Select **GitHub Actions** under **Settings > Pages > Build and deployment**. The workflow deploys pushes to `main`; the launch-link and invitation features require this version of the website to be deployed and the updated Android APK installed. The runtime signaling field allows a new tunnel address without redeploying; the optional `VITE_SIGNALING_URL` repository variable can provide a default for a stable endpoint. For a fork, set `ALLOWED_ORIGINS` to the exact Pages origin plus local development origins and `SKYCOP_SITE_URL` to the fork's Pages URL before starting the launcher. See [mobile/README.md](mobile/README.md) for Android installation.
