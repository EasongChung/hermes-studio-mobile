package com.hermes.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * ScreenOrientationHelper - 小屏手机竖屏锁定。
 *
 * 【规则】
 * 最短边 < 600dp 视为手机小屏，锁定竖屏；
 * 最短边 ≥ 600dp（平板/大屏）保持系统默认方向，允许横屏。
 *
 * 【为什么用 smallestScreenWidthDp】
 * 该值不受当前横竖屏影响，能稳定判断设备物理尺寸类别。
 */
object ScreenOrientationHelper {

    private const val TAG = "HermesOrientation"
    private const val PHONE_SMALLEST_WIDTH_DP = 600

    /**
     * 若当前设备为小屏手机，则将 Activity 锁定为竖屏。
     */
    fun lockPortraitOnPhone(activity: AppCompatActivity) {
        val smallestWidthDp = resolveSmallestWidthDp(activity)
        val isPhone = smallestWidthDp > 0 && smallestWidthDp < PHONE_SMALLEST_WIDTH_DP
        if (isPhone) {
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            Log.d(TAG, "Lock portrait for phone, smallestWidthDp=$smallestWidthDp")
        } else {
            Log.d(TAG, "Keep free orientation, smallestWidthDp=$smallestWidthDp")
        }
    }

    private fun resolveSmallestWidthDp(activity: AppCompatActivity): Int {
        val configSw = activity.resources.configuration.smallestScreenWidthDp
        if (configSw > 0) return configSw

        // 极少数 ROM 可能返回 0，回退到 DisplayMetrics 估算。
        val metrics: DisplayMetrics = activity.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val heightDp = metrics.heightPixels / metrics.density
        return minOf(widthDp, heightDp).toInt()
    }

    fun isLandscape(activity: AppCompatActivity): Boolean {
        return activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}
