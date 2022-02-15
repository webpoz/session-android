package org.thoughtcrime.securesms.webrtc.data

// get the video rotation from a specific rotation, locked into 90 degree
// chunks offset by 45 degrees
fun Int.quadrantRotation() = when (this % 360) {
    in 315 until 360,
    in 0 until 45 -> 90
    in 45 until 135 -> 180
    in 135 until 225 -> 270
    else -> 0
}