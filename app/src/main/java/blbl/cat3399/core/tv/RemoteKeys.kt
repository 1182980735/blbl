package blbl.cat3399.core.tv

import android.view.KeyEvent

/**
 * 遥控器按键工具类
 *
 * 提供遥控器按键的识别和映射功能，主要用于Android TV设备的遥控器操作支持
 */
object RemoteKeys {

    /**
     * 判断按键是否为刷新键
     *
     * 支持多种刷新相关的按键码：
     * - KEYCODE_REFRESH：标准刷新键
     * - KEYCODE_F5：键盘F5刷新键
     * - KEYCODE_MENU：部分遥控器的菜单键也映射为刷新功能
     *
     * @param keyCode 按键码
     * @return 如果是刷新键返回true，否则返回false
     */
    fun isRefreshKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_REFRESH ||
            keyCode == KeyEvent.KEYCODE_F5 ||
            keyCode == KeyEvent.KEYCODE_MENU
    }
}
