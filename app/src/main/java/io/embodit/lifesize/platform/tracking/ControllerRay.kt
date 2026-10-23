package io.embodit.lifesize.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.lifesize.domain.Quat4
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.platform.LifeLog
import kotlin.math.abs

/**
 * The controller ray, read once per frame (ported from Spatial Ruler's ControllerPointerSource, pose
 * only). It only drives the placing ghost (design `ghost`: shown on the ray channel only); placing
 * itself is the system tap on the floor catcher, whichever pointer made it. The right controller
 * aims when both are there. On the emulator the controllers are online in controller mode only.
 *
 * It also reads the thumbsticks' X axis (v0.1.1, device feedback: the stick turns the animal) from the
 * provider's action listener (SDK doc "Obtain controller input actions"). The listener runs on the data
 * source thread: it only stores the value; the scene reads it once per frame.
 */
class ControllerRay {
    data class Ray(val origin: Vec3, val dir: Vec3)

    private var provider: ControllerTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    @Volatile private var stickX = 0f
    @Volatile private var stickAtMs = 0L
    @Volatile var actionCount = 0L
        private set

    // the stick pushed further of the two controllers
    private val actions =
        ControllerTrackingProvider.ControllerActionListener { a ->
            val l = a.left.thumbstickValue.x
            val r = a.right.thumbstickValue.x
            stickX = if (abs(r) >= abs(l)) r else l
            stickAtMs = SystemClock.elapsedRealtime()
            actionCount++
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(actions) }.onFailure { LifeLog.w("TRACK controller actions: $it") }
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        LifeLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(actions) }
            runCatching { p.stop() }
        }
        provider = null
        stickX = 0f
    }

    /** For the frame log: support state, action callbacks so far, the last stick X. */
    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} actions=$actionCount x=${"%.2f".format(stickX)}"

    /** Thumbstick X in [-1, 1] (right is +), 0 when no controller reports or the last report is stale. */
    fun stick(now: Long): Float {
        val p = provider ?: return 0f
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return 0f
        if (now - stickAtMs > STICK_STALE_MS) return 0f
        return stickX.coerceIn(-1f, 1f)
    }

    fun sample(): Ray? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            LifeLog.i("TRACK controller support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = data.right ?: data.left ?: return null
        val origin = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rotation = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!origin.isFinite() || origin.length < 1e-4f) return null
        val dir = rotation.rotate(Vec3.AHEAD)
        if (!dir.isFinite() || dir.length < 0.5f) return null
        return Ray(origin, dir.normalized())
    }

    private companion object {
        const val STICK_STALE_MS = 1_000L
    }
}
