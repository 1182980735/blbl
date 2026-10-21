package blbl.cat3399.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import blbl.cat3399.R
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.category.CategoryFragment
import blbl.cat3399.feature.custom.CustomPageFragment
import blbl.cat3399.feature.custom.CustomPageTabRegistry
import blbl.cat3399.feature.dynamic.DynamicFragment
import blbl.cat3399.feature.home.HomeFragment
import blbl.cat3399.feature.live.LiveFragment
import blbl.cat3399.feature.my.MyFragment
import blbl.cat3399.feature.search.SearchFragment

/**
 * 根导航规格，定义侧边栏中每个导航项的配置
 */
data class RootNavSpec(
    /** 导航ID */
    val navId: Int,
    /** 导航键名 */
    val key: String,
    /** 标题资源ID */
    @StringRes val titleRes: Int,
    /** 图标资源ID */
    @DrawableRes val iconRes: Int,
    /** 启动页面键名（用于设置启动时默认显示的页面） */
    val startupPageKey: String? = null,
    /** 是否启用此导航项 */
    val isEnabled: () -> Boolean = { true },
    /** 判断Fragment是否匹配此导航项 */
    val matchesFragment: (Fragment) -> Boolean,
    /** Fragment工厂方法 */
    val fragmentFactory: () -> Fragment,
)

/**
 * 主根导航注册表，管理侧边栏导航项和Fragment的映射关系
 */
object MainRootNavRegistry {
    private const val ROOT_TAG_PREFIX = "main_root_"

    private val specs: List<RootNavSpec> =
        listOf(
            RootNavSpec(
                navId = SidebarNavAdapter.ID_SEARCH,
                key = "search",
                titleRes = R.string.tab_search,
                iconRes = R.drawable.ic_nav_search,
                matchesFragment = { it is SearchFragment },
                fragmentFactory = { SearchFragment.newInstance() },
            ),
            RootNavSpec(
                navId = SidebarNavAdapter.ID_HOME,
                key = "home",
                titleRes = R.string.tab_recommend,
                iconRes = R.drawable.ic_nav_home,
                startupPageKey = AppPrefs.STARTUP_PAGE_HOME,
                matchesFragment = { it is HomeFragment },
                fragmentFactory = { HomeFragment.newInstance() },
            ),
            RootNavSpec(
                navId = SidebarNavAdapter.ID_CATEGORY,
                key = "category",
                titleRes = R.string.tab_category,
                iconRes = R.drawable.ic_nav_category,
                startupPageKey = AppPrefs.STARTUP_PAGE_CATEGORY,
                matchesFragment = { it is CategoryFragment },
                fragmentFactory = { CategoryFragment.newInstance() },
            ),
            RootNavSpec(
                navId = SidebarNavAdapter.ID_DYNAMIC,
                key = "dynamic",
                titleRes = R.string.tab_dynamic,
                iconRes = R.drawable.ic_nav_dynamic,
                startupPageKey = AppPrefs.STARTUP_PAGE_DYNAMIC,
                matchesFragment = { it is DynamicFragment },
                fragmentFactory = { DynamicFragment.newInstance() },
            ),
            RootNavSpec(
                navId = SidebarNavAdapter.ID_LIVE,
                key = "live",
                titleRes = R.string.tab_live,
                iconRes = R.drawable.ic_nav_live,
                startupPageKey = AppPrefs.STARTUP_PAGE_LIVE,
                matchesFragment = { it is LiveFragment },
                fragmentFactory = { LiveFragment.newInstance() },
            ),
            RootNavSpec(
                navId = SidebarNavAdapter.ID_CUSTOM,
                key = "custom",
                titleRes = R.string.tab_custom,
                iconRes = R.drawable.ic_nav_custom,
                startupPageKey = AppPrefs.STARTUP_PAGE_CUSTOM,
                isEnabled = {
                    CustomPageTabRegistry.isEnabled(
                        config = BiliClient.prefs.customPageConfig,
                        isLoggedIn = BiliClient.cookies.hasSessData(),
                    )
                },
                matchesFragment = { it is CustomPageFragment },
                fragmentFactory = { CustomPageFragment.newInstance() },
            ),
            RootNavSpec(
                navId = SidebarNavAdapter.ID_MY,
                key = "my",
                titleRes = R.string.tab_my,
                iconRes = R.drawable.ic_nav_my,
                startupPageKey = AppPrefs.STARTUP_PAGE_MY,
                matchesFragment = { it is MyFragment },
                fragmentFactory = { MyFragment.newInstance() },
            ),
        )

    /** 获取所有启用的导航规格 */
    fun enabledSpecs(): List<RootNavSpec> = specs.filter { it.isEnabled() }

    /** 获取所有可作为启动页面的导航规格 */
    fun startupSpecs(): List<RootNavSpec> = specs.filter { it.startupPageKey != null }

    /** 获取所有启用且可作为启动页面的导航规格 */
    fun enabledStartupSpecs(): List<RootNavSpec> = enabledSpecs().filter { it.startupPageKey != null }

    /** 获取侧边栏导航项列表 */
    fun sidebarItems(context: Context): List<SidebarNavAdapter.NavItem> {
        return enabledSpecs().map { spec ->
            SidebarNavAdapter.NavItem(
                id = spec.navId,
                title = context.getString(spec.titleRes),
                iconRes = spec.iconRes,
            )
        }
    }

    /** 根据启动页面键解析导航ID */
    fun resolveLaunchNavId(startupPageKey: String): Int {
        return enabledStartupSpecs().firstOrNull { it.startupPageKey == startupPageKey }?.navId
            ?: SidebarNavAdapter.ID_HOME
    }

    /** 获取启动页面的标题 */
    fun startupTitle(context: Context, startupPageKey: String): String {
        return startupSpecs().firstOrNull { it.startupPageKey == startupPageKey }
            ?.let { context.getString(it.titleRes) }
            ?: context.getString(R.string.tab_recommend)
    }

    /** 根据导航ID获取导航规格（不检查是否启用） */
    fun specForNavId(navId: Int): RootNavSpec? = specs.firstOrNull { it.navId == navId }

    /** 根据导航ID获取启用的导航规格 */
    fun enabledSpecForNavId(navId: Int): RootNavSpec? = specForNavId(navId)?.takeIf { it.isEnabled() }

    /** 获取所有启用的导航ID数组 */
    fun rootNavIds(): IntArray = enabledSpecs().map { it.navId }.toIntArray()

    /** 根据导航ID生成Fragment标签 */
    fun rootTagFor(navId: Int): String {
        val spec = specForNavId(navId) ?: throw IllegalArgumentException("Unknown root navId=$navId")
        return ROOT_TAG_PREFIX + spec.key
    }

    /** 判断标签是否为根导航Fragment标签 */
    fun isRootTag(tag: String?): Boolean = tag?.startsWith(ROOT_TAG_PREFIX) == true

    /** 根据Fragment获取对应的导航ID */
    fun navIdForFragment(fragment: Fragment?): Int? {
        if (fragment == null) return null
        val byTag = specs.firstOrNull { rootTagFor(it.navId) == fragment.tag }
        if (byTag != null) return byTag.navId
        return specs.firstOrNull { it.matchesFragment(fragment) }?.navId
    }
}
