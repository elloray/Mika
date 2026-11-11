package com.wangzan101.mika.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.RecommendedTaskError
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置里的推荐任务:和首页是同一份列表,标题用来辨认,提示词留到点按后填进输入框。 */
@Composable
fun RecommendedTasksScreen(store: RecommendedTaskStore, onBack: () -> Unit) {
    remember(store) { store.ensureLoaded { ChatCopy.recommendationSeeds() } }
    val tasks by store.tasks.collectAsState()
    val unreadable by store.unreadable.collectAsState()
    var editing by remember { mutableStateOf<RecommendedTask?>(null) }
    var deleting by remember { mutableStateOf<RecommendedTask?>(null) }
    var deleteError by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    val heights = remember { mutableStateMapOf<String, Int>() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var pointerY by remember { mutableStateOf(0f) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragDistance by remember { mutableStateOf(0f) }
    var startScroll by remember { mutableStateOf(0) }
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    var reorderError by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var savingOrder by remember { mutableStateOf(false) }
    val ids = tasks.map { it.id }

    fun updatePreview() {
        val id = draggedId ?: return
        val originalIndex = ids.indexOf(id)
        if (originalIndex < 0) return
        val center = ids.take(originalIndex).sumOf { heights[it] ?: 0 } +
            (heights[id] ?: 0) / 2f + dragDistance + scroll.value - startScroll
        val current = previewOrder ?: ids
        var top = 0f
        var target = current.lastIndex
        for ((index, item) in current.withIndex()) {
            val height = (heights[item] ?: 0).toFloat()
            if (center < top + height / 2f) { target = index; break }
            top += height
        }
        val from = current.indexOf(id)
        if (from >= 0 && target != from) {
            previewOrder = current.toMutableList().apply { add(target, removeAt(from)) }
        }
    }

    fun saveOrder(order: List<String>) {
        savingOrder = true
        reorderError = null
        previewOrder = order
        draggedId = null
        scope.launch {
            delay(180) // 先让松手的任务平滑归位，再切换到底层保存的顺序。
            reorderError = withContext(Dispatchers.IO) { store.reorder(order) }
            previewOrder = null
            savingOrder = false
        }
    }

    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val edge = 72f * density
            val amount = when {
                pointerY < viewport.top + edge -> -12f * density
                pointerY > viewport.bottom - edge -> 12f * density
                else -> 0f
            }
            if (amount != 0f) { scroll.scrollTo((scroll.value + amount).toInt()); updatePreview() }
            delay(16)
        }
    }

    val c = MikaTheme.colors
    PageScaffold(stringResource(R.string.recommendations_title), onBack, scrollState = scroll,
        viewportModifier = Modifier.onGloballyPositioned { viewport = it.boundsInWindow() }) {
        if (unreadable) {
            FooterNote(stringResource(R.string.recommendations_unreadable), color = c.bad)
            if (resetError) FooterNote(stringResource(R.string.recommendations_save_failed), color = c.bad)
            MikaButton(
                stringResource(R.string.recommendations_reset),
                { resetting = true },
                modifier = Modifier.padding(top = 4.dp),
                kind = ButtonKind.Secondary,
                size = ButtonSize.Medium,
            )
        } else {
            GroupCard {
                if (tasks.isEmpty()) {
                    ListRow(
                        MikaIcons.Message,
                        stringResource(R.string.recommendations_empty),
                        stringResource(R.string.recommendations_empty_sub),
                        first = true,
                    )
                } else {
                    tasks.forEachIndexed { index, task ->
                        key(task.id) {
                            var handleTop by remember { mutableStateOf(0f) }
                            val order = previewOrder ?: ids
                            val destination = order.indexOf(task.id).coerceAtLeast(0)
                            val oldTop = ids.take(index).sumOf { heights[it] ?: 0 }
                            val newTop = order.take(destination).sumOf { heights[it] ?: 0 }
                            val moving = draggedId == task.id
                            val animatedOffset by animateFloatAsState(
                                targetValue = if (moving) dragDistance + scroll.value - startScroll else (newTop - oldTop).toFloat(),
                                animationSpec = tween(if (moving) 0 else 180), label = "recommendation position",
                            )
                            val moveUp = stringResource(R.string.recommendations_move_up)
                            val moveDown = stringResource(R.string.recommendations_move_down)
                            Box(Modifier.fillMaxWidth().zIndex(if (moving) 1f else 0f)
                                .graphicsLayer { translationY = animatedOffset; shadowElevation = if (moving) 8.dp.toPx() else 0f }
                                .background(c.surface)
                                .onSizeChanged { heights[task.id] = it.height }
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction(moveUp) {
                                            if (!savingOrder && draggedId == null) saveOrder(ids.toMutableList().apply { add(index - 1, removeAt(index)) })
                                            true
                                        })
                                        if (index < ids.lastIndex) add(CustomAccessibilityAction(moveDown) {
                                            if (!savingOrder && draggedId == null) saveOrder(ids.toMutableList().apply { add(index + 1, removeAt(index)) })
                                            true
                                        })
                                    }
                                }) {
                                val preview = promptPreview(task.prompt)
                                ListRow(MikaIcons.Message, task.title, preview.takeIf { it != task.title },
                                    first = destination == 0, onClick = { if (!savingOrder && draggedId == null) editing = task }) {
                                    DeleteTaskButton { if (!savingOrder && draggedId == null) { deleteError = null; deleting = task } }
                                    Box(Modifier.size(MikaSize.IconButton)
                                        .onGloballyPositioned { if (moving || draggedId == null) handleTop = it.boundsInWindow().top }
                                        .testTag("recommendations-drag-${task.id}")
                                        .pointerInput(task.id, ids, savingOrder) {
                                            detectDragGestures(
                                                onDragStart = { position ->
                                                    if (!savingOrder) {
                                                        draggedId = task.id; dragDistance = 0f; startScroll = scroll.value
                                                        pointerY = handleTop + position.y; previewOrder = ids; reorderError = null
                                                    }
                                                },
                                                onDragEnd = { if (draggedId == task.id) saveOrder(previewOrder ?: ids) },
                                                onDragCancel = { if (draggedId == task.id) { draggedId = null; previewOrder = null } },
                                            ) { change, amount ->
                                                if (draggedId == task.id) {
                                                    change.consume(); dragDistance += amount.y
                                                    pointerY = handleTop + change.position.y
                                                    updatePreview()
                                                }
                                            }
                                        }, contentAlignment = Alignment.Center) {
                                        Icon(MikaIcons.Menu, stringResource(R.string.recommendations_drag), Modifier.size(MikaSize.Icon), tint = c.ink3)
                                    }
                                }
                            }
                        }
                    }
                }
                if (tasks.size < RecommendedTaskStore.MAX_TASKS) {
                    ListRow(MikaIcons.Plus, stringResource(R.string.recommendations_add), first = false, onClick = { if (!savingOrder && draggedId == null) adding = true })
                }
            }
            if (tasks.size >= RecommendedTaskStore.MAX_TASKS) {
                FooterNote(stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS))
            }
            reorderError?.let { FooterNote(recommendationError(it), color = c.bad) }
            if (tasks.size > 1) FooterNote(stringResource(R.string.recommendations_drag_hint))
            FooterNote(stringResource(R.string.recommendations_footer))
        }
    }

    if (adding || editing != null) {
        RecommendedTaskDialog(
            store = store,
            initial = editing,
            onDismiss = { adding = false; editing = null },
        )
    }
    deleting?.let { task ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recommendations_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.recommendations_delete_body))
                    deleteError?.let { Text(recommendationError(it), Modifier.padding(top = 8.dp), color = c.bad) }
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { store.delete(task.id) }
                        if (result == null) deleting = null else deleteError = result
                    }
                }, Modifier.testTag("recommendations-delete")) { Text(stringResource(R.string.recommendations_delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (resetting) {
        MikaDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.recommendations_reset)) },
            text = { Text(stringResource(R.string.recommendations_reset_body)) },
            confirmButton = {
                TextButton({
                    resetting = false
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { store.discardUnreadable() }
                        resetError = error != null
                    }
                }, Modifier.testTag("recommendations-reset")) { Text(stringResource(R.string.recommendations_reset)) }
            },
            dismissButton = { TextButton({ resetting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RecommendedTaskDialog(store: RecommendedTaskStore, initial: RecommendedTask?, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var error by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (saving) return
        val titleToSave = title
        val promptToSave = prompt
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (initial == null) store.add(titleToSave, promptToSave) else store.update(initial.id, titleToSave, promptToSave)
            }
            saving = false
            if (result == null) onDismiss() else error = result
        }
    }

    MikaDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.recommendations_add else R.string.recommendations_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    title, { title = it; error = null },
                    label = { Text(stringResource(R.string.recommendations_title_label)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    prompt, { prompt = it; error = null },
                    label = { Text(stringResource(R.string.recommendations_prompt_label)) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let {
                    Text(recommendationError(it), Modifier.padding(top = 8.dp), color = MikaTheme.colors.bad)
                }
            }
        },
        confirmButton = {
            TextButton({ save() }, Modifier.testTag("recommendations-save"), enabled = !saving) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton({ if (!saving) onDismiss() }) { Text(stringResource(R.string.cancel)) } },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** 行尾的删除。单独可点,不会连着打开编辑。 */
@Composable
private fun DeleteTaskButton(onClick: () -> Unit) {
    val label = stringResource(R.string.recommendations_delete)
    Box(
        Modifier.size(MikaSize.IconButton).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Trash, label, Modifier.size(MikaSize.Icon), tint = MikaTheme.colors.ink3)
    }
}

@Composable
private fun recommendationError(error: RecommendedTaskError): String = when (error) {
    RecommendedTaskError.BlankTitle -> stringResource(R.string.recommendations_title_required)
    RecommendedTaskError.BlankPrompt -> stringResource(R.string.recommendations_prompt_required)
    RecommendedTaskError.TooLong -> stringResource(R.string.recommendations_too_long)
    RecommendedTaskError.TooMany -> stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS)
    RecommendedTaskError.SaveFailed -> stringResource(R.string.recommendations_save_failed)
    RecommendedTaskError.Unreadable -> stringResource(R.string.recommendations_unreadable)
}

/** 设置列表里只露一行,避免一条长提示词把整页撑开。 */
private fun promptPreview(prompt: String): String {
    val flat = prompt.replace(Regex("\\s+"), " ").trim()
    val max = 60
    if (flat.codePointCount(0, flat.length) <= max) return flat
    var end = 0
    var taken = 0
    while (end < flat.length && taken < max) {
        end += Character.charCount(flat.codePointAt(end))
        taken++
    }
    return flat.substring(0, end) + "…"
}
