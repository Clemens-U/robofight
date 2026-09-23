package robofight.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 8-bit chiptune sound effects for the arena, played via [SoundPool] (low
 * latency, ideal for short SFX). All sounds are pre-generated square/noise
 * WAVs shipped in res/raw/ — no audio libraries, fully offline.
 *
 * Usage:
 *   RetroSound.get(context).on(true).playHit()
 */
class RetroSound private constructor(context: Context) {

    private val pool: SoundPool
    private var hitSound = 0
    private var blockSound = 0
    private var boomSound = 0
    private var enabled = true

    private val hitVol = 0.9f
    private val blockVol = 0.8f
    private val boomVol = 1.0f

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()

        hitSound = pool.load(context, R.raw.hit, 1)
        blockSound = pool.load(context, R.raw.block, 1)
        boomSound = pool.load(context, R.raw.boom, 1)
    }

    fun on(value: Boolean) {
        enabled = value
    }

    fun isOn() = enabled

    /** Shot connects: descending square blip + noise "thock". */
    fun playHit() {
        if (enabled) pool.play(hitSound, hitVol, hitVol, 1, 0, 1f)
    }

    /** Shield absorbs: high clean "tink" + tiny noise tick. */
    fun playBlock() {
        if (enabled) pool.play(blockSound, blockVol, blockVol, 1, 0, 1f)
    }

    /** Knock-out / round end: low descending square + noise boom. */
    fun playBoom() {
        if (enabled) pool.play(boomSound, boomVol, boomVol, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }

    companion object {
        @Volatile
        private var instance: RetroSound? = null

        fun get(context: Context): RetroSound =
            instance ?: synchronized(this) {
                instance ?: RetroSound(context.applicationContext).also { instance = it }
            }
    }
}
