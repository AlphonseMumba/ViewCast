package com.example.viewcast

import java.util.concurrent.atomic.AtomicLong

/** État partagé entre le service de diffusion et l'interface (lecture seule côté interface). */
object CastStats {
    @Volatile var running = false
    @Volatile var clients = 0
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var targetFps = 0
    @Volatile var audioActive = false
    @Volatile var audioMuted = false
    @Volatile var secure = false
    @Volatile var pin = ""

    val bytesSent = AtomicLong()
    val framesEncoded = AtomicLong()
    val authFailures = AtomicLong()

    fun reset(width: Int, height: Int, fps: Int) {
        this.width = width
        this.height = height
        targetFps = fps
        clients = 0
        audioActive = false
        audioMuted = false
        bytesSent.set(0)
        framesEncoded.set(0)
        authFailures.set(0)
        pin = ""
        secure = false
    }

    fun clear() {
        running = false
        clients = 0
        audioActive = false
        pin = ""
        secure = false
    }
}
