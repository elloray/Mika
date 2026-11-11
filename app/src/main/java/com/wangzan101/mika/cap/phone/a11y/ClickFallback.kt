package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.uistate.Hant
import kotlin.math.hypot

/**
 * 点击通道兜底的纯逻辑判据:
 * - [mayBeOutward]:按钮文字或资源 ID 像发送、提交、付款、删除、确认等对外或不可逆动作时不补点第二条通道,宁可只试一次;
 * - [pickTarget]:坐标点击无反应时,在包含 (x, y) 的可点目标里挑面积最小且不超过整屏比例上限的一个改走节点点击。
 */
internal object ClickFallback {
    /** 坐标反查可点节点时,目标面积不超过整屏的这么多,高度不超过屏高的这么多(排除整屏或半屏背景容器)。 */
    const val MAX_AREA_SHARE = 0.35
    const val MAX_HEIGHT_SHARE = 0.50
    private const val MAX_BUTTON_LABEL_LEN = 24

    private val CONFIRM_WORDS = setOf(
        "确认", "确定", "完成", "好", "好的", "允许", "同意",
        "ok", "okay", "confirm", "done", "yes", "allow", "agree",
    )
    private val OUTWARD_ID_WORDS = setOf(
        "send", "submit", "pay", "payment", "checkout", "order",
        "delete", "remove", "confirm", "post", "publish", "reply",
    )

    /**
     * 开关类按钮:第二条通道再点一次会把刚打开的状态关回去(收藏、关注、点赞、静音),
     * 或重复发起(接听、拨打)。自绘图标只换颜色时画面差不到 3 格,树也不变,只能靠名字拦。
     */
    private val STATEFUL_CJK = setOf("关注", "订阅", "收藏", "点赞", "喜欢", "静音", "接听", "拨打", "挂断")
    private val STATEFUL_EN = setOf(
        "follow", "following", "unfollow", "like", "liked", "favorite", "favourite",
        "subscribe", "subscribed", "mute", "muted",
    )
    private val ID_SPLIT = Regex("(?=[A-Z])|[/_\\-.:]+")

    /** 包含一点的可点候选:[item] 调用方对象,[left]/[top]/[right]/[bottom] 为裁剪到屏幕内的像素边界。 */
    data class Candidate<T>(
        val item: T,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val label: String? = null,
        val resourceId: String? = null,
        val scrollable: Boolean = false,
        val webView: Boolean = false,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val area: Long get() = width.toLong() * height
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    /**
     * 名字或资源 ID 像发送、提交、支付、删除、确认按钮,或是开关类按钮时返回 true,
     * 不自动补点第二条通道:宁可这次点不动,也不能把刚打开的状态点回去或重复发起。
     */
    fun mayBeOutward(label: String?, resourceId: String?): Boolean {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed.length <= MAX_BUTTON_LABEL_LEN) {
            if (IrreversibleDetector.matchName(trimmed) != null) return true
            val norm = Hant.fold(trimmed).filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
            if (norm in CONFIRM_WORDS) return true
            if (STATEFUL_CJK.any { it in norm } || norm in STATEFUL_EN) return true
        }
        if (!resourceId.isNullOrBlank()) {
            val tail = resourceId.substringAfterLast('/')
            val words = tail.split(ID_SPLIT).map { it.lowercase() }.filter { it.isNotEmpty() }
            if (words.any { it in OUTWARD_ID_WORDS || it in STATEFUL_EN }) return true
        }
        return false
    }

    /**
     * 从候选里挑出包含 ([x], [y])、非滚动容器、非 WebView、非对外动作且尺寸不过大的最小可点目标;
     * 面积相同时选中心离 ([x], [y]) 最近的。
     */
    fun <T> pickTarget(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        candidates: Iterable<Candidate<T>>,
    ): T? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val maxArea = (screenWidth.toLong() * screenHeight * MAX_AREA_SHARE).toLong()
        val maxHeight = (screenHeight * MAX_HEIGHT_SHARE).toInt()
        return candidates
            .filter { c ->
                !c.scrollable && !c.webView &&
                    c.width > 0 && c.height in 1..maxHeight &&
                    c.area in 1..maxArea &&
                    c.contains(x, y) &&
                    !mayBeOutward(c.label, c.resourceId)
            }
            .minWithOrNull(
                compareBy<Candidate<T>> { it.area }
                    .thenBy { hypot((it.centerX - x).toDouble(), (it.centerY - y).toDouble()) }
            )?.item
    }
}
