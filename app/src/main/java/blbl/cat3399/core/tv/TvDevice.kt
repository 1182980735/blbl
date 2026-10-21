package blbl.cat3399.core.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 判断当前设备是否为Android TV设备
 *
 * 通过三种方式进行检测，任一条件满足即判定为TV设备：
 * 1. 检查系统是否支持television硬件特性
 * 2. 检查系统是否支持Leanback UI框架
 * 3. 检查当前UI模式是否为电视模式
 *
 * @return 如果是TV设备返回true，否则返回false
 */
fun Context.isTvDevice(): Boolean {
    val appContext = applicationContext
    val packageManager = appContext.packageManager

    // 方式1：检查television硬件特性
    if (packageManager.hasSystemFeature(FEATURE_TELEVISION)) return true

    // 方式2：检查Leanback UI框架支持（Android TV标准框架）
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true

    // 方式3：检查当前UI模式是否为电视模式
    val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/** 电视设备硬件特性标识 */
private const val FEATURE_TELEVISION = "android.hardware.type.television"
