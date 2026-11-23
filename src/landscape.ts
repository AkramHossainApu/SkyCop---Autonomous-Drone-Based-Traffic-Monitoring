/**
 * Makes sure the picture sent to the headset is never taller than it is wide.
 *
 * Phone browsers ignore the 16:9 request when the phone is held upright and deliver a tall portrait
 * picture, which fills only a sliver of each eye in VR. Landscape frames pass through untouched;
 * a portrait frame is cropped to a centered square. The phone can be turned at any time and the
 * shape follows. Stopping the returned stream's track also stops the camera.
 */
export function landscapeStream(source: MediaStream, frameRate = 30): MediaStream {
  const video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.srcObject = source;
  void video.play().catch(() => undefined);

  const canvas = document.createElement('canvas');
  canvas.width = 1280;
  canvas.height = 720;
  const context = canvas.getContext('2d', { alpha: false });
  const output = canvas.captureStream(frameRate);
  const track = output.getVideoTracks()[0];

  let running = true;
  const draw = () => {
    const width = video.videoWidth;
    const height = video.videoHeight;
    if (!context || width === 0 || height === 0) return;
    // Landscape or square: as it is. Portrait: the middle square.
    const side = Math.min(width, height);
    const outWidth = width >= height ? width : side;
    const outHeight = width >= height ? height : side;
    if (canvas.width !== outWidth || canvas.height !== outHeight) {
      canvas.width = outWidth;
      canvas.height = outHeight;
    }
    const sy = width >= height ? 0 : (height - side) / 2;
    context.drawImage(video, 0, sy, outWidth, outHeight, 0, 0, outWidth, outHeight);
  };

  // Draw on every new camera frame where the browser says when that is, else on a steady timer.
  type FrameVideo = HTMLVideoElement & { requestVideoFrameCallback?: (callback: () => void) => number };
  const framed = video as FrameVideo;
  let timer = 0;
  if (typeof framed.requestVideoFrameCallback === 'function') {
    const onFrame = () => {
      if (!running) return;
      draw();
      framed.requestVideoFrameCallback!(onFrame);
    };
    framed.requestVideoFrameCallback(onFrame);
  } else {
    timer = window.setInterval(draw, 1000 / frameRate);
  }

  if (track) {
    const stopOutput = track.stop.bind(track);
    track.stop = () => {
      running = false;
      window.clearInterval(timer);
      source.getTracks().forEach((t) => t.stop());
      video.srcObject = null;
      stopOutput();
    };
  }
  return output;
}
