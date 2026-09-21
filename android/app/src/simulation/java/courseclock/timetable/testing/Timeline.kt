package courseclock.timetable.testing

import kotlin.math.ceil

/** 墙钟仅作真实时间入口；播放进度完全由单调时钟驱动。 */
data class Timeline(val anchor: Long, val elapsedAnchor: Long, val speed: Double, val enabled: Boolean) {
    init {
        require(speed.isFinite() && speed in 0.0..120.0)
    }

    fun now(elapsed: Long, realNow: Long): Long = if (!enabled) realNow else
        anchor + ((elapsed - elapsedAnchor).coerceAtLeast(0) * speed).toLong()

    fun delayUntil(target: Long, elapsed: Long, realNow: Long): Long? {
        val remaining = target - now(elapsed, realNow)
        if (remaining <= 0) return 0
        if (enabled && speed == 0.0) return null
        return ceil(remaining / if (enabled) speed else 1.0).toLong()
    }
}
