package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.DirectionalIcon
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.chat.ConversationHit
import com.wangzan101.mika.chat.ConversationSearch
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.keepsKeyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.lazyListScrollbar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import com.wangzan101.mika.i18n.uiLocale

/** 侧栏里的一条对话。[id] 为 null 是还没存进对话列表的当前对话。 */
data class DrawerConversation(
    val id: String?,
    val title: String,
    val subtitle: String,
    val running: Boolean,
    val current: Boolean,
    val enabled: Boolean,
    /** 最后改动的时间(写在标题右边);还没存进对话列表的为空。 */
    val time: String = "",
    /** 置顶了:标题前一个小图钉,长按菜单里是「取消置顶」。 */
    val pinned: Boolean = false,
    /** 在浏览器页上对着一页开始的对话:那个网站(主机名),标题后面一个「网页」小标签;不是为 null。 */
    val site: String? = null,
    /** 从另一个对话分支出来的:标题前一个小分支图标。 */
    val branch: Boolean = false,
)

/** 侧栏里的对话分段:进行中、置顶、最近。 */
data class DrawerSections(
    val running: List<DrawerConversation>,
    val recent: List<DrawerConversation>,
    val pinned: List<DrawerConversation> = emptyList(),
)

/** 搜索拿到的原始命中;显示前再按此刻的置顶、运行、时间换成侧栏里的行。 */
private data class SearchOutcome(
    val query: String,
    val hits: List<ConversationHit>,
    val searching: Boolean,
    val truncated: Boolean = false,
)

/** 当前对话在侧栏里的样子:有没有任务在跑、标题、副标题(在做什么)。 */
data class CurrentConversation(val id: String?, val running: Boolean, val title: String, val subtitle: String)

/**
 * 侧栏的三段:「进行中」是当前对话(有任务时)和别的有任务在跑的对话;「置顶」是其余置顶了的对话,最近置顶的在前;
 * 「最近」是剩下的对话,最近改过的在前。每个对话只出现在一段里:置顶的对话有任务在跑时列在进行中(带图钉),跑完回到置顶。
 * [canSwitch] 为假(任务进行中、没开并行)时只有当前对话可点。
 */
fun drawerSections(
    all: List<ConversationSummary>,
    current: CurrentConversation,
    others: List<String>,
    otherTitle: (String) -> String?,
    canSwitch: Boolean,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): DrawerSections {
    val byId = all.associateBy { it.id }
    fun timeOf(id: String?) = byId[id]?.takeIf { it.updatedAt != 0L }?.let { conversationTime(it.updatedAt, now, zone) }.orEmpty()
    fun pinned(id: String?) = byId[id]?.pinnedAt != null
    val running = buildList {
        if (current.running) add(DrawerConversation(current.id, current.title.ifBlank { tr(R.string.chat_new) }, current.subtitle, true, true, true, time = timeOf(current.id), pinned = pinned(current.id), site = byId[current.id]?.page?.host, branch = byId[current.id]?.branchOf != null))
        others.filter { it != current.id }.distinct().forEach { id ->
            val title = (byId[id]?.title ?: otherTitle(id))?.let(ChatCopy::plainTitle)?.ifBlank { null } ?: tr(R.string.drawer_running_task)
            add(DrawerConversation(id, title, tr(R.string.drawer_running), running = true, current = false, enabled = canSwitch, time = timeOf(id), pinned = pinned(id), site = byId[id]?.page?.host, branch = byId[id]?.branchOf != null))
        }
    }
    val runningIds = running.mapNotNull { it.id }.toSet()
    fun entry(s: ConversationSummary): DrawerConversation =
        drawerEntry(s, current.id, canSwitch, running = s.id in runningIds, time = timeOf(s.id))
    val rest = all.filter { it.id !in runningIds }
    val pinnedRows = rest.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }.map(::entry)
    val recent = rest.asSequence().filter { it.pinnedAt == null }.take(MAX_RECENT).map(::entry).toList()
    return DrawerSections(running, recent, pinnedRows)
}

/** 一条对话在侧栏里的样子:侧栏列表和搜索结果共用，免得两处规则走岔。 */
internal fun drawerEntry(
    s: ConversationSummary,
    currentId: String?,
    canSwitch: Boolean,
    running: Boolean,
    time: String,
    subtitle: String = "",
): DrawerConversation {
    val isCurrent = s.id == currentId
    return DrawerConversation(
        s.id, ChatCopy.plainTitle(s.title), subtitle, running, isCurrent, canSwitch || isCurrent, time,
        pinned = s.pinnedAt != null, site = s.page?.host, branch = s.branchOf != null,
    )
}

/** 对话的时间:今天写时刻,昨天写「昨天」,一周内写星期几,今年写几月几日,更早带年份。 */
fun conversationTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = t.toLocalDate()
    val days = today.toEpochDay() - day.toEpochDay()
    return when {
        days <= 0L -> "%d:%02d".format(t.hour, t.minute)
        days == 1L -> tr(R.string.drawer_yesterday)
        days < 7L -> t.dayOfWeek.getDisplayName(DateTextStyle.SHORT, uiLocale())
        day.year == today.year -> tr(R.string.date_month_day, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
        else -> tr(R.string.date_year_month_day, day.year, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
    }
}

/** 侧栏顶上 Mika 名字下面那行状态,和它是不是好的(绿点)还是要处理(橙点)。 */
fun readiness(check: Prerequisites.Check): Pair<String, Boolean> = when {
    !check.canStartTask -> tr(R.string.chat_cannot_start) to false
    !check.phoneReady -> tr(R.string.drawer_phone_not_ready) to false
    else -> tr(R.string.all_ready) to true
}

private const val MAX_RECENT = 50

/**
 * 侧栏:从左边滑出、右边圆角。顶部是标志和状态;四个入口;中间是进行中(段标题右边进「后台屏」页)和最近的对话;最底下设置和关于并排。
 * 打开和关上由外面的抽屉管,这里只摆内容,点了什么交给回调。
 */
@Composable
fun ChatDrawer(
    check: Prerequisites.Check,
    sections: DrawerSections,
    canSwitch: Boolean,
    /** 搜索框里的字;非空时侧栏进入搜索结果,入口区让位给结果。 */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    /** 当前查询的结果;还没搜完时 [DrawerSearch.searching] 为真。 */
    search: DrawerSearch? = null,
    /** 有对话可搜时才显示搜索框。 */
    canSearch: Boolean = false,
    onNew: () -> Unit,
    onPlans: () -> Unit,
    onMemory: () -> Unit,
    onBrowser: () -> Unit,
    onFiles: () -> Unit = {},
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onOpen: (DrawerConversation) -> Unit,
    onAbout: () -> Unit = {},
    /** 完全退出 Mika(先问一句)。 */
    onQuit: () -> Unit = {},
    /** 有新版本:「关于 Mika」带小红点。 */
    aboutDot: Boolean = false,
    /** 长按一条存过的对话:在它原地弹出操作菜单(置顶、改名、删除);带上那一行在窗口里的位置和大小。 */
    onActions: (DrawerConversation, Rect) -> Unit = { _, _ -> },
    onPersonal: () -> Unit = {},
    /** 多选模式:每行左边出现勾选框,点一行是勾选;底部换成「已选 N 条 / 全选 / 删除」。 */
    selecting: Boolean = false,
    selected: Set<String> = emptySet(),
    onStartSelect: () -> Unit = {},
    onExitSelect: () -> Unit = {},
    onToggleSelect: (DrawerConversation) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    /** 批量删除的进度和结果;在底部显示,删完过几秒由外面清掉。 */
    deleteBatch: DeleteBatch? = null,
    permanent: Boolean = false,
    onShowScreen: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val shape = if (permanent) RectangleShape else DrawerShape
    // 能勾的就是列在侧栏里、已经存过的对话(还没存过的当前草稿没有编号,删不了)
    val selectableIds = remember(sections) {
        (sections.running + sections.pinned + sections.recent).mapNotNull { it.id }.toSet()
    }
    val selectedCount = selected.count { it in selectableIds }
    val allSelected = selectableIds.isNotEmpty() && selectedCount == selectableIds.size
    // 搜索时把几个入口收起来，把高度让给结果;清空后原样回来。搜索框在入口上面，位置不跳
    val searchActive = canSearch && !selecting && query.isNotBlank()
    Column(
        Modifier.fillMaxHeight().then(if (permanent) Modifier else Modifier.widthIn(max = DrawerWidth)).fillMaxWidth()
            .clip(shape).background(c.bg, shape)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
            .testTag("drawer"),
    ) {
        val (state, ok) = readiness(check)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MikaLogo(36.dp, corner = 10.dp)
            Column(Modifier.weight(1f)) {
                Text("Mika", style = MikaType.Title, color = c.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (ok) c.ok else c.warnSolid, CircleShape))
                    Text(state, style = MikaType.Hint, fontWeight = FontWeight.Medium, color = c.ink3)
                }
            }
            // 多选从顶上这个按钮进;进去以后这里换成「取消」,返回键同样能退出
            when {
                selecting -> HeaderAction(stringResource(R.string.cancel), "drawer-select-cancel", c.ink2, onExitSelect)
                selectableIds.isNotEmpty() && !searchActive ->
                    HeaderAction(stringResource(R.string.drawer_select), "drawer-select", c.accent, onStartSelect)
            }
        }
        if (canSearch && !selecting) DrawerSearchField(query, onQueryChange)
        // 搜索、多选时把几个入口收起来，把高度让给列表;退出后原样回来
        if (!searchActive && !selecting) Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            onShowScreen?.let { NavEntry(MikaIcons.Screens, stringResource(R.string.screens_preview), onClick = it) }
            NavEntry(MikaIcons.Message, stringResource(R.string.drawer_personal), onClick = onPersonal)
            NavEntry(MikaIcons.New, stringResource(R.string.chat_new), enabled = canSwitch, onClick = onNew)
            NavEntry(MikaIcons.Clock, stringResource(R.string.drawer_plans), onClick = onPlans)
            NavEntry(MikaIcons.Heart, stringResource(R.string.memory_and_skills), onClick = onMemory)
            NavEntry(MikaIcons.Globe, stringResource(R.string.drawer_browser), onClick = onBrowser)
            NavEntry(MikaIcons.Paperclip, stringResource(R.string.file_library_title), onClick = onFiles)
            // 和上面几项隔开一点:这是把 Mika 整个停下来,不是换一页
            Box(Modifier.padding(top = 4.dp)) {
                NavEntry(MikaIcons.Power, stringResource(R.string.quit_title), onClick = onQuit)
            }
        }
        // 侧栏关着时列表只剩末尾那一项;打开时对话插在它前面,列表会按原来那一项停在最底下。最上面一项变了就回到顶上
        val list = rememberLazyListState()
        val first = when {
            searchActive -> "h-search"
            sections.running.isNotEmpty() -> "h-running"
            sections.pinned.isNotEmpty() -> "h-pinned"
            sections.recent.isNotEmpty() -> "h-recent"
            else -> "end"
        }
        // 双栏与抽屉切换会在布局阶段组合侧栏，延后到下一次测量再定位，避免重入测量。
        LaunchedEffect(first) { list.requestScrollToItem(0) }
        val terms = remember(query) { ConversationSearch.termsOf(query) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).lazyListScrollbar(list), state = list) {
            if (searchActive) {
                item("h-search") { SectionLabel(stringResource(R.string.drawer_search_results)) }
                if (!canSwitch) {
                    item("search-busy") {
                        Text(
                            stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                            style = MikaType.Hint, color = c.ink3,
                        )
                    }
                }
                // 扫正文期间一直提示;标题命中已经列出来时也提示,表示结果还会补全
                if (search?.searching != false) item("searching") { SearchHint(stringResource(R.string.drawer_searching)) }
                items(search?.hits.orEmpty(), key = { "s-${it.id}" }) { ConversationEntry(it, onOpen, onActions, terms) }
                if (search != null && !search.searching && search.hits.isEmpty()) {
                    item("search-empty") { SearchEmpty(query) }
                }
                if (search?.truncated == true) {
                    item("search-more") { SearchHint(stringResource(R.string.drawer_search_truncated, ConversationSearch.MAX_HITS)) }
                }
            } else {
                if (sections.running.isNotEmpty()) {
                    // 「进行中」这一段的右边进「后台屏」页:看每个在跑的任务正在用哪个 App、实时画面
                    item("h-running") { RunningLabel(onScreens) }
                    items(sections.running, key = { "r-${it.id}" }) {
                        ConversationEntry(it, onOpen, onActions, selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect)
                    }
                }
                // 换不了对话时的说明放在第一段存着的对话(置顶或最近)上面
                val busyNote: LazyListScope.() -> Unit = {
                    if (!canSwitch) {
                        item("busy") {
                            Text(
                                stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                                style = MikaType.Hint, color = c.ink3,
                            )
                        }
                    }
                }
                if (sections.pinned.isNotEmpty()) {
                    item("h-pinned") { SectionLabel(stringResource(R.string.drawer_pinned)) }
                    busyNote()
                    items(sections.pinned, key = { "p-${it.id}" }) {
                        ConversationEntry(it, onOpen, onActions, selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect)
                    }
                }
                if (sections.recent.isNotEmpty()) {
                    item("h-recent") { SectionLabel(stringResource(R.string.drawer_recent)) }
                    if (sections.pinned.isEmpty()) busyNote()
                    items(sections.recent, key = { "c-${it.id}" }) {
                        ConversationEntry(it, onOpen, onActions, selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect)
                    }
                }
            }
            item("end") { Box(Modifier.height(12.dp)) }
        }
        // 批量删除的进度 / 结果在底部停留几秒
        deleteBatch?.let { DeleteStatusBar(it) }
        if (selecting) {
            SelectBar(selectedCount, allSelected, onSelectAll, onDeleteSelected)
        } else {
            // 设置和关于并排固定在侧栏最底下,对话再多也不被挤走:设置靠左,关于靠右,中间留空
            Row(
                Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("drawer-footer"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NavEntry(MikaIcons.Gear, stringResource(R.string.settings_title), Modifier, onClick = onSettings)
                NavEntry(MikaIcons.Info, AboutCopy.TITLE, Modifier, dot = aboutDot, onClick = onAbout)
            }
        }
    }
}

/** 侧栏顶上的小按钮:平时的「多选」和多选中的「取消」。 */
@Composable
private fun HeaderAction(text: String, tag: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, style = MikaType.Label, color = color)
    }
}

/** 多选时的底部操作条:已选几条、全选 / 取消全选、删除。 */
@Composable
private fun SelectBar(selectedCount: Int, allSelected: Boolean, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-select-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            tr(R.string.drawer_selected_count, selectedCount), Modifier.weight(1f),
            style = MikaType.Label, color = c.ink2, maxLines = 1, softWrap = false,
        )
        TextButton(onClick = onSelectAll, modifier = Modifier.testTag("drawer-select-all")) {
            Text(stringResource(if (allSelected) R.string.drawer_select_none else R.string.drawer_select_all), style = MikaType.Label, color = c.accent)
        }
        TextButton(onClick = onDelete, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-delete-selected")) {
            Text(stringResource(R.string.confirm_delete), style = MikaType.Label, color = if (selectedCount > 0) c.bad else c.ink3)
        }
    }
}

/** 批量删除的进度和结果:删的时候是「正在删除 N 个对话…」,删完写成功几条、失败几条。 */
@Composable
private fun DeleteStatusBar(batch: DeleteBatch) {
    val c = MikaTheme.colors
    val deleted = batch.done - batch.failed
    val text = when {
        !batch.finished -> trPlural(R.plurals.drawer_delete_progress, batch.total, batch.total)
        batch.failed == 0 -> trPlural(R.plurals.drawer_delete_done, deleted, deleted)
        else -> trPlural(R.plurals.drawer_delete_partial, deleted, deleted, batch.failed)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-delete-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (batch.finished) {
            Icon(
                if (batch.failed == 0) MikaIcons.Check else MikaIcons.Exclaim, null, Modifier.size(14.dp),
                tint = if (batch.failed == 0) c.ok else c.bad,
            )
        }
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

@Composable
private fun NavEntry(
    icon: ImageVector, text: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        modifier.height(46.dp).clip(NavShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MikaType.Body, color = c.ink, maxLines = 1, softWrap = false)
            if (dot) UpdateDot()
        }
    }
}

/** 「进行中」的段标题,右边一个「后台屏 ›」进后台屏页(只有有任务在跑时才有这一段)。 */
@Composable
private fun RunningLabel(onScreens: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_running)) }
        Row(
            Modifier.clip(NavShape).clickable(role = Role.Button, onClick = onScreens)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-screens"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_screens), style = MikaType.Hint, color = c.ink2)
            DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        style = SectionText, color = MikaTheme.colors.ink3,
    )
}

/**
 * 一条对话:点开它;长按(带一下振动)在原地弹出它的操作菜单(置顶、改名、删除)。换不了对话时(任务进行中、没开并行)点不开,长按照样能用。
 * 还没存进对话列表的(没有编号)没东西可改,不响应长按。置顶了的标题前有个小图钉。
 * 多选模式([selecting])下点一行是勾选/取消,左边出现勾选框;没存过的草稿不参与。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationEntry(
    item: DrawerConversation,
    onOpen: (DrawerConversation) -> Unit,
    onActions: (DrawerConversation, Rect) -> Unit,
    /** 搜索结果里高亮的关键词;普通列表为空。 */
    terms: List<String> = emptyList(),
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (DrawerConversation) -> Unit = {},
) {
    val c = MikaTheme.colors
    val deletable = item.id != null
    val selectable = selecting && deletable
    val haptic = LocalHapticFeedback.current
    // 这一行在窗口里的位置和大小(不裁掉滚出列表的部分),菜单照它摆出抬起的那一行;只在回调里读,不引起重组
    val bounds = remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .then(if (item.current) Modifier.cardSurface(c, NavShape) else Modifier)
            .clip(NavShape)
            .then(
                when {
                    selectable -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggleSelect(item) })
                    // 多选模式里没存过的草稿不响应,免得点一下退出侧栏
                    selecting -> Modifier
                    deletable -> Modifier.combinedClickable(
                        onClickLabel = null,
                        onLongClickLabel = stringResource(R.string.drawer_actions),
                        // 和系统里长按一样振一下
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(item, bounds.value) },
                        onClick = { if (item.enabled) onOpen(item) },
                    )
                    else -> Modifier.clickable(enabled = item.enabled, onClick = { onOpen(item) })
                },
            )
            .testTag("conversation-row")
            .alpha(if (item.enabled || selectable) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectable) SelectMark(selected)
            Box(Modifier.weight(1f)) { ConversationRowContent(item, terms) }
        }
    }
}

/** 多选模式下每行左边的勾选框:没选是空心圆,选了是实心圆加一个白勾。 */
@Composable
private fun SelectMark(selected: Boolean) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier)
            .border(1.5.dp, if (selected) c.accent else c.edge, CircleShape)
            .testTag("conversation-check"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
    }
}

/** 一条对话里面的东西:进行中的小圆点、图钉、标题、时间,有副标题时下面一行。侧栏里那一行和长按抬起的那一张都用它。 */
@Composable
internal fun ConversationRowContent(item: DrawerConversation, terms: List<String> = emptyList()) {
    val c = MikaTheme.colors
    val title = remember(item.title, terms, c.accent) { highlighted(item.title, terms, c.accent) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (item.running) Box(Modifier.size(7.dp).background(c.accent, CircleShape))
            if (item.pinned) Icon(MikaIcons.Pin, stringResource(R.string.drawer_pinned_mark), Modifier.size(14.dp).testTag("conversation-pin"), tint = c.ink3)
            if (item.branch) Icon(MikaIcons.Route, stringResource(R.string.chat_branch_origin), Modifier.size(14.dp).testTag("conversation-branch"), tint = c.ink3)
            Text(title, Modifier.weight(1f, fill = item.site == null), style = ConversationTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.site != null) {
                Text(
                    PageAskCopy.WEB, Modifier.background(c.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp).testTag("conversation-web"),
                    style = ConversationTag, color = c.accent, maxLines = 1,
                )
                Box(Modifier.weight(1f))
            }
            if (item.time.isNotEmpty()) {
                Text(item.time, Modifier.padding(start = 5.dp).testTag("conversation-time"), style = ConversationTime, color = c.ink3, maxLines = 1, softWrap = false)
            }
        }
        val sub = item.subtitle.ifEmpty { item.site.orEmpty() }
        if (sub.isNotEmpty()) {
            val styled = remember(sub, terms, c.accent) { highlighted(sub, terms, c.accent) }
            Text(styled, Modifier.padding(top = 2.dp), style = ConversationTime, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 给对话改名:输入框里是现在的标题(整段选中,直接打字就替换);空的存不了,两头空白去掉。 */
@Composable
internal fun RenameConversationDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("rename-conversation-field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag("confirm-rename-conversation"), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 删对话前的确认:写明删的是哪个对话;有任务在跑会停掉,绑着定时任务则写明计划会保留。
 * 和别处删东西的确认一样:右边红字「删除」,左边「取消」。
 */
@Composable
internal fun DeleteConversationDialog(title: String, impact: DeleteImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_delete_body, title),
        if (impact.runningTask) stringResource(R.string.drawer_delete_running) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_schedules, impact.schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 批量删对话前的确认:写明将删除几个对话;其中有任务在跑的会停掉,绑着定时任务的写明计划会保留。
 * 和单条删除的确认一样:右边红字「删除」,左边「取消」。
 */
@Composable
internal fun DeleteManyConversationsDialog(
    count: Int, runningTasks: Int, schedules: Int,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val body = listOfNotNull(
        trPlural(R.plurals.drawer_delete_many_body, count, count),
        if (runningTasks > 0) trPlural(R.plurals.drawer_delete_many_running, runningTasks, runningTasks) else null,
        if (schedules > 0) trPlural(R.plurals.drawer_delete_many_schedules, schedules, schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_many_title)) },
        text = { Text(body, Modifier.testTag("delete-many-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-many-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, Modifier.testTag("dismiss-delete-many-conversation")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

internal const val MENU_IN_MS = 200
internal const val MENU_OUT_MS = 140
private val DrawerWidth = 304.dp
private val DrawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
internal val NavShape = RoundedCornerShape(12.dp)
private val SectionText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
private val ConversationTitle = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val ConversationTime = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val ConversationTag = TextStyle(fontSize = 10.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

/**
 * 对话页和它的侧栏:左上角菜单从左边滑出侧栏,外面一层遮罩;点遮罩、往左滑、按返回都关上。
 * 侧栏打开时才去读对话列表。进了别的页面再回来,侧栏是关着的。[content] 拿到「打开侧栏」放到菜单按钮上。
 */
@Composable
fun ChatWithDrawer(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    reduceMotion: Boolean,
    conversations: () -> List<ConversationSummary>,
    /** 存储里改过的对话(见 `ConversationStore.changes`);侧栏开着时别处存进来的对话也当场列出。 */
    conversationChanges: Flow<String> = emptyFlow(),
    /** 对话起好的标题;当前对话还没存进列表时(任务刚开始)也能叫出名字。 */
    conversationTitle: (String) -> String? = { null },
    /** 侧栏搜索用的索引;没有时侧栏不显示搜索框。 */
    search: ConversationSearch? = null,
    onPlans: () -> Unit,
    onMemory: () -> Unit,
    onBrowser: () -> Unit,
    onFiles: () -> Unit = {},
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit = {},
    /** 有新版本:侧栏「关于 Mika」带小红点。 */
    updateAvailable: Boolean = false,
    /** 从侧栏进去的页面刚退回来:当场打开侧栏,回到进去之前的样子。 */
    reopen: Boolean = false,
    onReopened: () -> Unit = {},
    /** 当前对话正在操作的实时画面；宽屏可固定在侧栏区域。 */
    frames: StateFlow<Bitmap?>? = null,
    operationRun: (String) -> com.wangzan101.mika.task.TaskRun? = { null },
    /** 聊天和个人助手页启用抽屉返回；画面停靠还需要传入 frames，侧栏功能页只复用布局。 */
    chatActive: Boolean = true,
    onConversationSelected: () -> Unit = {},
    onPersonal: () -> Unit = {},
    onRegularChat: () -> Unit = {},
    /** 外部跳回对话页时变化:若之前因返回键留在展开的侧栏,当场收起侧栏。 */
    resetKey: Int = 0,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val adaptive = LocalAdaptiveLayout.current
    val permanent = adaptive.hasNavigation
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsState()
    val runId = ui.exchanges.lastOrNull()?.runId
    var preferConversations by rememberSaveable(ui.conversationId, runId) { mutableStateOf(false) }
    val screenAvailable = chatActive && permanent && frames != null && ui.pip.visible
    val screenDocked = screenAvailable && !ui.pip.closed && !preferConversations
    LaunchedEffect(reopen, permanent) {
        if (permanent) drawer.snapTo(DrawerValue.Closed)
        if (reopen) {
            preferConversations = true
            if (!permanent) drawer.snapTo(DrawerValue.Open)
            onReopened()
        }
    }
    val others by vm.otherRunning.collectAsState()
    fun close(then: () -> Unit = {}) {
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
        then()
    }
    /** 去别的页面:先当场关上侧栏,回来时就是关着的。 */
    fun leave(go: () -> Unit) {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    val open = permanent || drawer.targetValue == DrawerValue.Open || drawer.isOpen
    /** 搜索框里的字和已经拿到的结果;侧栏关掉就清空。 */
    var query by remember { mutableStateOf("") }
    var searchOutcome by remember { mutableStateOf<SearchOutcome?>(null) }
    /** 多选模式:进去以后每行可勾选,底部换成批量删除的操作条;侧栏关上就退出。 */
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 等用户确认的批量删除(对话编号)。 */
    var confirmingMany by remember { mutableStateOf<List<String>?>(null) }
    val deleteBatch by vm.deleteBatch.collectAsState()
    // 删除结果在底部停留几秒;下一批开始前不动它
    LaunchedEffect(deleteBatch) {
        val batch = deleteBatch ?: return@LaunchedEffect
        if (batch.finished) {
            delay(if (batch.failed == 0) 2_500L else 5_000L)
            vm.clearDeleteBatch()
        }
    }
    LaunchedEffect(open, search) {
        if (!open) {
            query = ""
            searchOutcome = null
            selecting = false
            selected = emptySet()
            // 正文索引只在侧栏开着时有用,关掉就放掉,长时间使用不会一直占着全部对话的正文
            search?.clear()
        }
    }
    // 对话页上返回(侧滑)先打开侧栏;侧栏开着时再返回才离开 App(退到后台,和桌面返回一样)。
    // 搜索时先清掉搜索,再按才离开。对话页里面板、画中画等自己的返回在内层,先于这里处理
    val activity = LocalContext.current.findActivity()
    val context = LocalContext.current
    BackHandler(enabled = chatActive || selecting || query.isNotEmpty()) {
        when {
            selecting -> {
                selecting = false
                selected = emptySet()
            }
            query.isNotEmpty() -> query = ""
            open -> activity?.moveTaskToBack(true)
            else -> scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() }
        }
    }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(conversationChanges) { conversationChanges.collect { revision++ } }
    val deleted by vm.deleted.collectAsState()
    /** 长按了的那条和它在窗口里的位置;菜单开着(收起的动画放完才清掉)。 */
    var acting by remember { mutableStateOf<Pair<DrawerConversation, Rect>?>(null) }
    /** 菜单出现的进度:0 是没有,1 是完全出来;关了动画时直接到位。 */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(adaptive) {
        // 窗口变了，长按时记录的坐标已过期。
        acting = null
        lift.snapTo(0f)
    }
    LaunchedEffect(acting) {
        if (acting != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    /** 收起菜单:倒着放一遍出现的动画,放完再清掉;[then] 当场做(改名、删除的弹窗不用等菜单收完)。 */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            acting = null
        }
    }
    /** 正在改名的那条。 */
    var renaming by remember { mutableStateOf<DrawerConversation?>(null) }
    /** 要删的那条和删它会连带什么;确认弹窗开着。 */
    var confirming by remember { mutableStateOf<Pair<DrawerConversation, DeleteImpact>?>(null) }
    /** 「完全退出」的确认弹窗开着。 */
    var confirmingQuit by remember { mutableStateOf(false) }
    var lastReset by remember { mutableIntStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != lastReset) {
            lastReset = resetKey
            acting = null
            renaming = null
            confirming = null
            selecting = false
            selected = emptySet()
            confirmingMany = null
            confirmingQuit = false
            query = ""
            searchOutcome = null
            drawer.snapTo(DrawerValue.Closed)
        }
    }
    val menuOpen = acting != null
    val drawerContent: @Composable () -> Unit = {
        // 只在打开时读盘;每次打开、开着时存储里有对话改过都重读一次
        val history = remember(open, revision) { if (open) conversations() else emptyList() }
        val drafts by vm.draftConversations.collectAsState()
        val all = remember(open, history, drafts, revision) { if (open) vm.withDrafts(history) else emptyList() }
        val stored = remember(open, revision, ui.conversationId) { if (open) ui.conversationId?.let(conversationTitle) else null }
        val last = ui.exchanges.lastOrNull()
        val running = vm.busy()
        val current = CurrentConversation(
            ui.conversationId, running,
            stored ?: ChatCopy.title(ui.exchanges),
            if (running) ChatCopy.subtitle(last, last?.let(vm::livePlace))?.first.orEmpty() else "",
        )
        val canSwitch = vm.canSwitch()
        // 搜索候选:标题换成侧栏里显示的样子(去掉附件行、网页说明),和行上看到的一致
        val candidates = remember(all, deleted) {
            all.filter { it.id !in deleted }.map { it.copy(title = ChatCopy.plainTitle(it.title)) }
        }
        val engine = search
        LaunchedEffect(open, query, revision, candidates.size, engine) {
            val q = query.trim()
            if (engine == null || !open || q.isEmpty() || candidates.isEmpty()) {
                searchOutcome = null
                return@LaunchedEffect
            }
            // 标题命中不用等正文,先列出来;正文扫完再整体换掉
            searchOutcome = SearchOutcome(q, engine.titleMatches(q, candidates), searching = true)
            delay(180)
            val result = withContext(Dispatchers.IO) { engine.search(q, candidates) }
            searchOutcome = SearchOutcome(q, result.hits, searching = false, truncated = result.truncated)
        }
        val byId = remember(candidates) { candidates.associateBy { it.id } }
        val now = System.currentTimeMillis()
        val runningIds = remember(others, ui.conversationId, running) {
            (others + listOfNotNull(ui.conversationId?.takeIf { running })).toSet()
        }
        val searchView = searchOutcome?.takeIf { it.query == query.trim() }?.let { outcome ->
            DrawerSearch(
                outcome.hits.mapNotNull { hit ->
                    val s = byId[hit.id] ?: return@mapNotNull null
                    val time = if (s.updatedAt != 0L) conversationTime(s.updatedAt, now) else ""
                    drawerEntry(
                        s, ui.conversationId, canSwitch, running = hit.id in runningIds,
                        time = time, subtitle = hit.snippet.orEmpty(),
                    )
                },
                outcome.searching,
                outcome.truncated,
            )
        }
        // 删掉的对话当场不列,不等后台删完
        val sections = remember(all, current, others, canSwitch, deleted) {
            drawerSections(all.filter { it.id !in deleted }, current, others.filter { it !in deleted }, vm::runningGoal, canSwitch, System.currentTimeMillis())
        }
        // 能勾的就是此刻列在侧栏里的对话;别处删了、列表变了,勾选里已经不存在的当场去掉
        val selectableIds = remember(sections) {
            (sections.running + sections.pinned + sections.recent).mapNotNull { it.id }.toSet()
        }
        LaunchedEffect(selectableIds) { selected = selected intersect selectableIds }
        ChatDrawer(
            check, sections, canSwitch,
            query = query,
            onQueryChange = { query = it },
            search = searchView,
            canSearch = candidates.isNotEmpty(),
            onNew = { close { vm.newConversation(); onRegularChat(); onConversationSelected() } },
            onPersonal = { leave(onPersonal) },
            onPlans = { leave(onPlans) },
            onMemory = { leave(onMemory) },
            onSettings = { leave(onSettings) },
            onBrowser = { leave(onBrowser) },
            onFiles = { leave(onFiles) },
            onScreens = { leave(onScreens) },
            onAbout = { leave(onAbout) },
            onQuit = { leave { confirmingQuit = true } },
            aboutDot = updateAvailable,
            permanent = permanent,
            onShowScreen = if (screenAvailable) ({ preferConversations = false; vm.reopenPip() }) else null,
            onActions = { item, bounds -> if (item.id != null) acting = item to bounds },
            onOpen = { item ->
                close {
                    onRegularChat()
                    val id = item.id
                    when {
                        id == null || id == ui.conversationId -> Unit
                        item.running -> vm.switchTo(id)
                        else -> vm.openConversation(id)
                    }
                    onConversationSelected()
                }
            },
            selecting = selecting,
            selected = selected,
            onStartSelect = { selecting = true },
            onExitSelect = {
                selecting = false
                selected = emptySet()
            },
            onToggleSelect = { item ->
                item.id?.let { id -> selected = if (id in selected) selected - id else selected + id }
            },
            onSelectAll = {
                selected = if (selectableIds.isNotEmpty() && selectableIds.all { it in selected }) emptySet() else selectableIds
            },
            onDeleteSelected = {
                val ids = selectableIds.filter { it in selected }
                if (ids.isNotEmpty()) confirmingMany = ids
            },
            deleteBatch = deleteBatch,
        )
    }
    CompositionLocalProvider(LocalOperationScreenDocked provides screenDocked) {
        Box(Modifier.fillMaxSize()) {
            AdaptiveChatNavigation(
                layout = adaptive,
                // 菜单开着时后面整屏(对话页和侧栏)模糊(安卓 12 起;更早的只变暗),无障碍也只读菜单
                modifier = if (!menuOpen) Modifier else Modifier
                    .then(
                        if (GlassBlurSupported) Modifier.graphicsLayer {
                            val r = MenuBlur.toPx() * lift.value
                            renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                        } else Modifier,
                    )
                    .clearAndSetSemantics {},
                drawerContent = drawerContent,
                drawerState = drawer,
                drawerGesturesEnabled = chatActive,
                sideContent = if (screenDocked) ({
                    val run = ui.conversationId?.let(operationRun)
                    val liveFrame by (run?.frames ?: frames!!).collectAsState()
                    var lastFrame by remember(ui.conversationId, runId) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(liveFrame) { liveFrame?.let { lastFrame = it } }
                    val previewFrame = liveFrame ?: lastFrame.takeIf { ui.pip.mode == PipMode.Done }
                    val showConversations = { preferConversations = true }
                    val closeScreen = { preferConversations = true; vm.closePip() }
                    if (run != null) InteractiveOperationScreen(run, previewFrame, ui.pip.mode, showConversations, closeScreen)
                    else OperationScreenPane(previewFrame, ui.pip.mode, showConversations, closeScreen)
                }) else null,
            ) {
                content {
                    if (permanent) preferConversations = true
                    else scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() }
                }
            }
            acting?.let { (item, bounds) ->
                val id = item.id ?: return@let
                ConversationMenu(
                    item, bounds, progress = { lift.value },
                    onPin = { closeMenu { vm.setPinned(id, !item.pinned) } },
                    onRename = { closeMenu { renaming = item } },
                    onTerminal = { closeMenu { activity?.let { a -> com.wangzan101.mika.ui.terminal.TerminalLauncher.open(a, id, item.title) } } },
                    onDelete = { closeMenu { confirming = item to vm.deleteImpact(id) } },
                    onDismiss = { closeMenu() },
                )
            }
        }
    }
    renaming?.let { item ->
        RenameConversationDialog(
            item.title,
            onSave = { t ->
                renaming = null
                item.id?.let { vm.renameConversation(it, t) }
            },
            onDismiss = { renaming = null },
        )
    }
    confirming?.let { (item, impact) ->
        DeleteConversationDialog(
            item.title, impact,
            onConfirm = {
                confirming = null
                item.id?.let(vm::deleteConversation)
            },
            onDismiss = { confirming = null },
        )
    }
    confirmingMany?.let { ids ->
        // 弹窗里要写几条任务会停、几条定时任务保留;打开时算一次就够
        val impacts = remember(ids) { ids.map(vm::deleteImpact) }
        DeleteManyConversationsDialog(
            count = ids.size,
            runningTasks = impacts.count { it.runningTask },
            schedules = impacts.sumOf { it.schedules },
            onConfirm = {
                confirmingMany = null
                selecting = false
                selected = emptySet()
                vm.deleteConversations(ids)
            },
            onDismiss = { confirmingMany = null },
        )
    }
    if (confirmingQuit) {
        AlertDialog(
            onDismissRequest = { confirmingQuit = false },
            title = { Text(stringResource(R.string.quit_title)) },
            text = { Text(stringResource(R.string.quit_confirm_body)) },
            confirmButton = {
                TextButton({ confirmingQuit = false; com.wangzan101.mika.quit.MikaQuit.quit(context) }) {
                    Text(stringResource(R.string.quit_confirm_action), color = MikaTheme.colors.bad)
                }
            },
            dismissButton = { TextButton({ confirmingQuit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
