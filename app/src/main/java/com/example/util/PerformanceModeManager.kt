package com.example.util

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.remember
import com.example.data.model.PerformanceMode
import com.example.ui.components.ArtworkCacheManager
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.withContext

@Immutable
data class DisplayModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float
)

@Immutable
data class DisplayCapabilityInfo(
    val currentRefreshRateHz: Float = 60f,
    val maxSupportedRefreshRateHz: Float = 60f,
    val supports120Hz: Boolean = false,
    val preferredDisplayModeId: Int = 0,
    val effectiveDisplayHz: Float = 60f,
    val effectiveRenderFps: Float = 60f,
    val activeMode: PerformanceMode = PerformanceMode.FPS_60_HZ_120
)

/**
 * Manages PureTune's decoupled Display Refresh Rate (Hz) and Rendering Frame Rate (FPS):
 *
 * 1. [PerformanceMode.FPS_60_HZ_120] ("60 FPS + 120Hz"):
 *    - Keeps the physical display locked at 120Hz (via WindowManager.LayoutParams.preferredDisplayModeId
 *      and preferredRefreshRate = 120f on supported panels) so touch sampling and panel scanout stay at 120Hz.
 *    - Never forces the display down to 60Hz.
 *    - Paces UI rendering to 60 FPS (1:2 hardware VSYNC cadence = 16.67ms frame budget on a 120Hz panel)
 *      using Android View frame-rate APIs (API 34+) and VSYNC-aligned Compose MonotonicFrameClock scheduling,
 *      with zero timers or busy-loops.
 *
 * 2. [PerformanceMode.FPS_120_HZ_120] ("120 FPS + 120Hz"):
 *    - Keeps the physical display at 120Hz and targets full 120 FPS rendering (1:1 VSYNC cadence = 8.33ms).
 *    - Automatically falls back safely to the device's maximum supported refresh rate (e.g. 90Hz or 60Hz)
 *      on hardware that does not support 120Hz.
 */
object PerformanceModeManager {

    @Volatile
    var currentMode: PerformanceMode = PerformanceMode.FPS_60_HZ_120
        private set

    @Volatile
    var latestCapabilityInfo: DisplayCapabilityInfo = DisplayCapabilityInfo()
        private set

    /**
     * Pure capability resolution from display modes. Decouples target display Hz from target render FPS
     * and guarantees safe fallback on devices without 120Hz support.
     */
    fun resolveCapabilitiesFromModes(
        currentRefreshRate: Float,
        currentWidth: Int,
        currentHeight: Int,
        supportedModes: List<DisplayModeSpec>,
        mode: PerformanceMode
    ): DisplayCapabilityInfo {
        val safeCurrentHz = if (currentRefreshRate > 0f) currentRefreshRate else 60f
        if (supportedModes.isEmpty()) {
            val supports120 = safeCurrentHz >= 119.0f
            val effectiveHz = safeCurrentHz
            val effectiveFps = when (mode) {
                PerformanceMode.FPS_60_HZ_120 -> minOf(60f, effectiveHz)
                PerformanceMode.FPS_120_HZ_120 -> if (supports120) 120f else effectiveHz
            }
            return DisplayCapabilityInfo(
                currentRefreshRateHz = safeCurrentHz,
                maxSupportedRefreshRateHz = safeCurrentHz,
                supports120Hz = supports120,
                preferredDisplayModeId = 0,
                effectiveDisplayHz = effectiveHz,
                effectiveRenderFps = effectiveFps,
                activeMode = mode
            )
        }

        val sameResolutionModes = supportedModes.filter {
            it.physicalWidth == currentWidth && it.physicalHeight == currentHeight
        }.ifEmpty { supportedModes }

        val maxSupportedHz = sameResolutionModes.maxOf { it.refreshRate }
        val supports120 = maxSupportedHz >= 119.0f

        // Prefer a 120Hz mode at the active resolution if available; otherwise pick the highest supported Hz
        val bestDisplayMode = sameResolutionModes
            .filter { it.refreshRate in 118.0f..122.0f }
            .minByOrNull { abs(it.refreshRate - 120.0f) }
            ?: sameResolutionModes.maxByOrNull { it.refreshRate }
            ?: supportedModes.first()

        // Display remains at 120Hz (or highest supported hardware Hz) in BOTH modes; never forced down to 60Hz
        val effectiveDisplayHz = bestDisplayMode.refreshRate
        val effectiveRenderFps = when (mode) {
            PerformanceMode.FPS_60_HZ_120 -> minOf(60f, effectiveDisplayHz)
            PerformanceMode.FPS_120_HZ_120 -> if (supports120) 120f else effectiveDisplayHz
        }

        return DisplayCapabilityInfo(
            currentRefreshRateHz = safeCurrentHz,
            maxSupportedRefreshRateHz = maxSupportedHz,
            supports120Hz = supports120,
            preferredDisplayModeId = bestDisplayMode.modeId,
            effectiveDisplayHz = effectiveDisplayHz,
            effectiveRenderFps = effectiveRenderFps,
            activeMode = mode
        )
    }

    /**
     * Queries the active display's refresh-rate modes without modifying window state.
     */
    fun queryDisplayCapabilities(
        activity: Activity,
        mode: PerformanceMode = currentMode
    ): DisplayCapabilityInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return resolveCapabilitiesFromModes(
                currentRefreshRate = 60f,
                currentWidth = 0,
                currentHeight = 0,
                supportedModes = emptyList(),
                mode = mode
            )
        }
        return try {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            } ?: return resolveCapabilitiesFromModes(60f, 0, 0, emptyList(), mode)

            val currentDisplayMode = activeDisplay.mode
            val currentHz = currentDisplayMode?.refreshRate ?: activeDisplay.refreshRate
            val currentW = currentDisplayMode?.physicalWidth ?: 0
            val currentH = currentDisplayMode?.physicalHeight ?: 0

            val specs = activeDisplay.supportedModes?.map { dm ->
                DisplayModeSpec(
                    modeId = dm.modeId,
                    physicalWidth = dm.physicalWidth,
                    physicalHeight = dm.physicalHeight,
                    refreshRate = dm.refreshRate
                )
            } ?: emptyList()

            resolveCapabilitiesFromModes(
                currentRefreshRate = currentHz,
                currentWidth = currentW,
                currentHeight = currentH,
                supportedModes = specs,
                mode = mode
            )
        } catch (_: Throwable) {
            resolveCapabilitiesFromModes(60f, 0, 0, emptyList(), mode)
        }
    }

    /**
     * Applies the selected [PerformanceMode] to the Activity window and view hierarchy:
     * 1. Locks the physical display refresh rate to 120Hz (or highest supported hardware refresh rate)
     *    via [android.view.WindowManager.LayoutParams.preferredDisplayModeId] and
     *    [android.view.WindowManager.LayoutParams.preferredRefreshRate] in BOTH 60 FPS and 120 FPS modes.
     * 2. Configures the rendering frame rate target (60 FPS vs 120 FPS) on the root DecorView (API 34+)
     *    and updates Compose/artwork frame pacing without forcing the display down to 60Hz.
     */
    fun applyPerformanceMode(
        activity: Activity,
        mode: PerformanceMode
    ): DisplayCapabilityInfo {
        currentMode = mode
        ArtworkCacheManager.setPerformanceMode(mode)

        val info = queryDisplayCapabilities(activity, mode)
        latestCapabilityInfo = info

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val window = activity.window
                if (window != null) {
                    val params = window.attributes
                    val targetModeId = info.preferredDisplayModeId
                    val targetDisplayHz = info.effectiveDisplayHz

                    // Keep the physical display at 120Hz (or max supported Hz) for BOTH 60 FPS + 120Hz and 120 FPS + 120Hz
                    val modeChanged = targetModeId != 0 && params.preferredDisplayModeId != targetModeId
                    val hzChanged = abs(params.preferredRefreshRate - targetDisplayHz) > 0.1f
                    if (modeChanged || hzChanged) {
                        if (targetModeId != 0) {
                            params.preferredDisplayModeId = targetModeId
                        }
                        params.preferredRefreshRate = targetDisplayHz
                        window.attributes = params
                    }

                    // On Android 14+ (API 34+), set the View rendering frame rate target (60f vs 120f)
                    // independently of the Window's 120Hz preferredDisplayModeId.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        window.decorView.setRequestedFrameRate(info.effectiveRenderFps)
                    }
                }
            } catch (_: Throwable) {
                // Safe no-op on emulators or OEM builds that restrict window attributes
            }
        }

        return info
    }
}

/**
 * VSYNC-aligned [MonotonicFrameClock] for "60 FPS + 120Hz" mode on high-refresh-rate displays (> 90Hz).
 *
 * When the display runs at 120Hz (8.33ms hardware VSYNC interval) and "60 FPS + 120Hz" is active,
 * this clock paces Compose animations and scroll flings to every 2nd hardware VSYNC (16.67ms = 60 FPS)
 * while the physical display panel remains at 120Hz.
 *
 * - Purely event-driven: only runs when a Compose animation or scroll fling invokes [withFrameNanos].
 * - Zero timers, zero `delay()`, zero busy-loops, and zero CPU work when the UI is idle.
 */
class SixtiesOnHighHzFrameClock(
    private val delegate: MonotonicFrameClock
) : MonotonicFrameClock {

    @Volatile
    private var lastDeliveredFrameNanos: Long = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        // First VSYNC tick from Android Choreographer
        var candidateNanos = 0L
        val firstResult = delegate.withFrameNanos { frameTimeNanos ->
            val prev = lastDeliveredFrameNanos
            // On a 120Hz display, consecutive VSYNCs are ~8.33ms apart (< 13.5ms).
            // If this VSYNC is only 1 tick after the last rendered frame, defer to the next VSYNC (16.67ms).
            if (prev != 0L && (frameTimeNanos - prev) in 1L..MIN_60FPS_FRAME_INTERVAL_NANOS) {
                candidateNanos = -1L
                null
            } else {
                lastDeliveredFrameNanos = frameTimeNanos
                candidateNanos = frameTimeNanos
                onFrame(frameTimeNanos)
            }
        }
        if (candidateNanos != -1L) {
            @Suppress("UNCHECKED_CAST")
            return firstResult as R
        }
        // Second VSYNC tick (~16.67ms after previous frame on a 120Hz display -> exact 60 FPS)
        return delegate.withFrameNanos { secondFrameNanos ->
            lastDeliveredFrameNanos = secondFrameNanos
            onFrame(secondFrameNanos)
        }
    }

    companion object {
        // 13.5ms threshold cleanly separates a single 120Hz VSYNC (8.33ms) or 90Hz VSYNC (11.11ms)
        // from a 60Hz VSYNC (16.67ms).
        private const val MIN_60FPS_FRAME_INTERVAL_NANOS = 13_500_000L
    }
}

private class SixtiesOnHighHzFlingBehavior(
    private val delegate: FlingBehavior
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val currentClock = coroutineContext[MonotonicFrameClock] ?: return with(delegate) {
            performFling(initialVelocity)
        }
        val pacedClock = if (currentClock is SixtiesOnHighHzFrameClock) {
            currentClock
        } else {
            SixtiesOnHighHzFrameClock(currentClock)
        }
        return withContext(pacedClock) {
            with(delegate) {
                performFling(initialVelocity)
            }
        }
    }
}

/**
 * Returns a [FlingBehavior] aligned with the active [PerformanceMode]:
 * - [PerformanceMode.FPS_60_HZ_120] on a >85Hz display: paces fling decay frames to 60 FPS (every 2nd VSYNC)
 *   while the hardware display panel remains at 120Hz.
 * - [PerformanceMode.FPS_120_HZ_120] (or 60Hz fallback displays): uses native 1:1 VSYNC fling scheduling.
 */
@Composable
fun rememberPerformanceFlingBehavior(
    mode: PerformanceMode = PerformanceModeManager.currentMode
): FlingBehavior {
    val defaultFling = ScrollableDefaults.flingBehavior()
    val effectiveHz = PerformanceModeManager.latestCapabilityInfo.effectiveDisplayHz
    return remember(defaultFling, mode, effectiveHz) {
        if (mode == PerformanceMode.FPS_60_HZ_120 && effectiveHz >= 85f) {
            SixtiesOnHighHzFlingBehavior(defaultFling)
        } else {
            defaultFling
        }
    }
}

