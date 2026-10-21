package blbl.cat3399.core.model

/**
 * 弹幕数据模型
 *
 * 表示单个弹幕的完整信息，包括时间戳、模式、文本、颜色、字体大小等。
 */
data class Danmaku(
    val timeMs: Int,
    val mode: Int,
    val text: String,
    val color: Int,
    val fontSize: Int,
    val weight: Int,
    val midHash: String? = null,
    val dmid: Long? = null,
    val attr: Int = 0,
)

/**
 * 判断弹幕是否为高赞弹幕
 *
 * 通过attr字段的第3位判断是否为高赞弹幕。
 */
val Danmaku.isHighLiked: Boolean
    get() = (attr and (1 shl 2)) != 0
