package com.example.util

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.runtime.Immutable
import com.example.ui.components.ArtworkCacheManager
import kotlin.math.abs

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
    val minTargetFps: Float = 60f,
    val supports90Hz: Boolean = false,
    val supports120Hz: Boolean = false,
    val preferredDisplayModeId: Int = 0,
    val effectiveDisplayHz: Float = 60f,
    val effectiveRenderFps: Float = 60f
)

/**
 * Internal automatic frame-rate policy for PureTune:
 *
 * - Minimum target: 60 FPS (where supported by hardware; never forces a device beyond its actual
 *   supported capability if hardware is < 60Hz).
 * - Automatically scales upward according to the device's actual display and rendering capability:
 *   - 60Hz devices  -> targets up to 60 FPS (60Hz)
 *   - 90Hz devices  -> scales up to 90 FPS (90Hz)
 *   - 120Hz devices -> scales up to 120 FPS (120Hz)
 *   - Higher-refresh-rate devices (e.g. 144Hz/165Hz) -> uses the highest stable supported refresh rate
 *     at the active physical resolution.
 * - Never intentionally caps a capable device to 60 FPS.
 * - Applied strictly at the Activity / Window / View level (`WindowManager.LayoutParams.preferredDisplayModeId`,
 *   `preferredRefreshRate`, and Android 14+ `View.setRequestedFrameRate`), never inside composables or
 *   during active scrolling, and with zero timers or busy-loops.
 */
object PerformanceModeManager {

    const val MIN_TARGET_FPS = 60f

    @Volatile
    var latestCapabilityInfo: DisplayCapabilityInfo = DisplayCapabilityInfo()
        private set

    @Volatile
    private var lastAppliedWindowHash: Int = 0

    @Volatile
    private var lastAppliedModeId: Int = -1

    @Volatile
    private var lastAppliedRefreshRate: Float = -1f

    /**
     * Pure capability and automatic policy resolution from the device's supported [DisplayModeSpec] list.
     *
     * Filters to modes matching the active physical resolution (`currentWidth` x `currentHeight`) so
     * selecting the highest refresh rate never triggers a resolution change or display mode-switch jank.
     */
    fun resolveAutomaticPolicyFromModes(
        currentRefreshRate: Float,
        currentWidth: Int,
        currentHeight: Int,
        supportedModes: List<DisplayModeSpec>
    ): DisplayCapabilityInfo {
        val safeCurrentHz = if (currentRefreshRate > 0f) currentRefreshRate else MIN_TARGET_FPS
        if (supportedModes.isEmpty()) {
            val minFps = minOf(MIN_TARGET_FPS, safeCurrentHz)
            return DisplayCapabilityInfo(
                currentRefreshRateHz = safeCurrentHz,
                maxSupportedRefreshRateHz = safeCurrentHz,
                minTargetFps = minFps,
                supports90Hz = safeCurrentHz >= 89.0f,
                supports120Hz = safeCurrentHz >= 119.0f,
                preferredDisplayModeId = 0,
                effectiveDisplayHz = safeCurrentHz,
                effectiveRenderFps = safeCurrentHz
            )
        }

        // Only consider modes with the exact same physical resolution to prevent resolution-switch jank
        val sameResolutionModes = supportedModes.filter {
            currentWidth > 0 && currentHeight > 0 &&
                it.physicalWidth == currentWidth &&
                it.physicalHeight == currentHeight
        }.ifEmpty { supportedModes }

        val bestMode = sameResolutionModes.maxByOrNull { it.refreshRate } ?: supportedModes.first()
        val maxSupportedHz = bestMode.refreshRate.coerceAtLeast(1f)

        // Minimum target is 60 FPS where supported by the panel; never exceed actual hardware capability
        val minTargetFps = minOf(MIN_TARGET_FPS, maxSupportedHz)

        // Automatically scale upward to the highest stable refresh rate supported at this resolution
        val targetHz = maxSupportedHz
        val targetFps = maxSupportedHz

        return DisplayCapabilityInfo(
            currentRefreshRateHz = safeCurrentHz,
            maxSupportedRefreshRateHz = maxSupportedHz,
            minTargetFps = minTargetFps,
            supports90Hz = maxSupportedHz >= 89.0f,
            supports120Hz = maxSupportedHz >= 119.0f,
            preferredDisplayModeId = bestMode.modeId,
            effectiveDisplayHz = targetHz,
            effectiveRenderFps = targetFps
        )
    }

    /**
     * Queries the active display's refresh-rate capabilities without mutating window attributes.
     */
    fun queryDisplayCapabilities(activity: Activity): DisplayCapabilityInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return resolveAutomaticPolicyFromModes(
                currentRefreshRate = MIN_TARGET_FPS,
                currentWidth = 0,
                currentHeight = 0,
                supportedModes = emptyList()
            )
        }
        return try {
            val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            } ?: return resolveAutomaticPolicyFromModes(MIN_TARGET_FPS, 0, 0, emptyList())

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

            resolveAutomaticPolicyFromModes(
                currentRefreshRate = currentHz,
                currentWidth = currentW,
                currentHeight = currentH,
                supportedModes = specs
            )
        } catch (_: Throwable) {
            resolveAutomaticPolicyFromModes(MIN_TARGET_FPS, 0, 0, emptyList())
        }
    }

    /**
     * Applies the automatic frame-rate policy once at the Activity / Window / View level:
     * - Never executes a display mode change while scrolling is active.
     * - Sets `WindowManager.LayoutParams.preferredDisplayModeId` and `preferredRefreshRate` to the
     *   highest supported refresh rate matching the current physical resolution (60Hz -> 60Hz,
     *   90Hz -> 90Hz, 120Hz -> 120Hz, 144Hz+ -> 144Hz+).
     * - On Android 14+ (API 34+), hints `View.REQUESTED_FRAME_RATE_CATEGORY_HIGH` on >60Hz panels
     *   and `View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL` on 60Hz panels so Android's Choreographer
     *   and SurfaceFlinger manage frame pacing natively without forcing unsupported frame rates.
     */
    fun applyAutomaticFrameRatePolicy(activity: Activity): DisplayCapabilityInfo {
        val window = activity.window
        val windowHash = System.identityHashCode(window)

        // Never trigger display-mode or window attribute changes mid-scroll
        if (ArtworkCacheManager.isScrollingActive() && windowHash == lastAppliedWindowHash) {
            return latestCapabilityInfo
        }

        val info = queryDisplayCapabilities(activity)
        latestCapabilityInfo = info

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && window != null) {
            try {
                val targetModeId = info.preferredDisplayModeId
                val targetHz = info.effectiveDisplayHz

                // Fast-path no-op if this exact Window already has the target modeId and refreshRate applied
                if (windowHash == lastAppliedWindowHash &&
                    targetModeId == lastAppliedModeId &&
                    abs(targetHz - lastAppliedRefreshRate) <= 0.1f
                ) {
                    return info
                }

                val params = window.attributes
                val modeChanged = targetModeId != 0 && params.preferredDisplayModeId != targetModeId
                val hzChanged = abs(params.preferredRefreshRate - targetHz) > 0.1f
                if (modeChanged || hzChanged) {
                    if (targetModeId != 0) {
                        params.preferredDisplayModeId = targetModeId
                    }
                    params.preferredRefreshRate = targetHz
                    window.attributes = params
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val category = if (info.effectiveDisplayHz > 60.5f) {
                        View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                    } else {
                        View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL
                    }
                    window.decorView.setRequestedFrameRate(category)
                }

                lastAppliedWindowHash = windowHash
                lastAppliedModeId = targetModeId
                lastAppliedRefreshRate = targetHz
            } catch (_: Throwable) {
                // Safe no-op on emulators or OEM builds that restrict window attributes
            }
        }

        return info
    }
}
