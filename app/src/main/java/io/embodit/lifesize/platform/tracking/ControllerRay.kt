package io.embodit.lifesize.platform.tracking

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.lifesize.domain.Quat4
import io.embodit.lifesize.domain.Vec3
import io.embodit.lifesize.platform.LifeLog

/**
 * The controller ray, read once per frame (ported from Spatial Ruler's ControllerPointerSource, pose
 * only). It only drives the placing ghost (design `ghost`: shown on the ray channel only); placing
 * itself is the system tap on the floor catcher, whichever pointer made it. The right controller
 * aims when both are there. On the emulator the controllers are online in controller mode only.
 */
class ControllerRay {
    data class Ray(val origin: Vec3, val dir: Vec3)

    private var provider: ControllerTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        LifeLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
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
}
