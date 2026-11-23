package com.oney.WebRTCModule

import org.webrtc.VideoTrack

/**
 * Lives in react-native-webrtc's package so it can reach the stream lookup that RTCView uses,
 * which is package-private. The lookup has to run on WebRTC's executor, like RTCView does it.
 */
object SkyCopStreamAccess {
    fun videoTrack(module: WebRTCModule, tag: String, done: (VideoTrack?) -> Unit) {
        ThreadUtils.runOnExecutor {
            val track = try {
                module.getStreamForReactTag(tag)?.videoTracks?.firstOrNull()
            } catch (error: Exception) {
                null
            }
            done(track)
        }
    }

    fun onExecutor(work: () -> Unit) = ThreadUtils.runOnExecutor { work() }
}
