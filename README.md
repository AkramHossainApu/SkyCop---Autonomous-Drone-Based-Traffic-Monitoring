# SkyCop

The first SkyCop prototype milestone: request a device camera, show its local preview, and stop the camera cleanly. The traffic scene is an interactive 3D visualization; it is illustrative and does not represent live mapping or drone telemetry.

## Run locally

Install Node.js 20.19+ or 22.12+, then from this folder:

```sh
npm install
npm run dev
```

Open the localhost URL printed by Vite and allow camera access. `getUserMedia` is available on localhost and HTTPS origins; opening the page directly as a file will not work. Use **Start local preview** to request camera access and **Stop preview** to release it.

This milestone does not transmit video to another device. The next video milestone should add a signaling service and WebRTC, then connect a separately built Android viewer. Do not expose a camera stream without authenticated sessions.

## Language and platform direction

TypeScript is the project language. Keep browser camera capture and WebRTC browser APIs in this web client. For Android and iOS, use React Native with TypeScript and share protocol types and service logic where practical; native camera, permissions, and WebRTC integrations still need platform-specific adapters. A desktop app can reuse the web client in a desktop shell such as Electron or Tauri. This keeps one primary application language without pretending that the same camera APIs run identically on every platform.
