package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.FileLibrary
import com.wangzan101.mika.chat.FileLibrarySnapshot
import com.wangzan101.mika.chat.LibraryFile
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class LibraryRow(
    val name: String,
    val size: String,
    val location: String,
    val at: Long?,
    val conversations: List<String>,
    val input: Boolean,
    val handle: LibraryFileHandle,
)

private data class LibraryLoaded(val rows: List<LibraryRow>, val titles: Map<String, String>, val partial: Boolean, val failed: Boolean = false)

internal data class LibraryLoadResult(val snapshot: FileLibrarySnapshot?, val titles: Map<String, String>, val partial: Boolean)

/** 索引和会话标题分别读取，一边出错不能阻止另一边显示。取消仍交给协程处理。 */
internal fun loadLibraryData(
    snapshot: () -> FileLibrarySnapshot,
    titles: () -> Map<String, String>,
    draftTitles: Map<String, String>,
    fallbackTitle: (String) -> String?,
): LibraryLoadResult {
    var partial = false
    fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        partial = true
        null
    }
    val files = attempt(snapshot)
    val names = attempt(titles)?.toMutableMap() ?: draftTitles.toMutableMap()
    draftTitles.forEach { (id, title) -> names.putIfAbsent(id, title) }
    files?.files?.forEach { file ->
        val ids = when (file) {
            is LibraryFile.Input -> file.sources.map { it.source.conversationId }
            is LibraryFile.Output -> file.sources.map { it.source.conversationId }
        }
        ids.distinct().forEach { id ->
            if (id !in names) names[id] = attempt { fallbackTitle(id) } ?: id
        }
    }
    return LibraryLoadResult(files, names, partial || files?.hasReadFailures == true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileLibraryScreen(
    vm: ChatViewModel,
    initialConversation: String?,
    fromDrawer: Boolean,
    onBack: () -> Unit,
    onAdded: (String) -> Boolean,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { ConversationStore.shared(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(store) { store.changes.collect { revision++ } }
    val drafts by vm.draftConversations.collectAsState()
    val loaded by produceState<LibraryLoaded?>(null, revision, drafts) {
        value = try { withContext(Dispatchers.IO) {
            val result = loadLibraryData(
                snapshot = { FileLibrary(store).snapshot() },
                titles = { vm.withDrafts(store.list()).associate { it.id to ChatCopy.plainTitle(it.title) } },
                draftTitles = drafts.associate { it.id to ChatCopy.plainTitle(it.title) },
                fallbackTitle = { id -> store.title(id)?.let(ChatCopy::plainTitle) },
            )
            val rows = result.snapshot?.files.orEmpty().mapNotNull { file ->
                when (file) {
                    is LibraryFile.Input -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(file.name, latest.sizeLabel, file.path, file.latestAtMs, file.sources.map { it.source.conversationId }.distinct(), true,
                            LibraryFileHandle.Workspace(latest.source.conversationId, file.path))
                    }
                    is LibraryFile.Output -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(latest.file.name, ChatViewModel.sizeLabel(latest.file.size), latest.file.savedAs, file.latestAtMs,
                            file.sources.map { it.source.conversationId }.distinct(), false, LibraryFileHandle.Exported(latest.file))
                    }
                }
            }.sortedByDescending { it.at ?: Long.MIN_VALUE }
            LibraryLoaded(rows, result.titles, result.partial, failed = result.snapshot == null)
        } } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LibraryLoaded(emptyList(), emptyMap(), partial = false, failed = true)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var conversation by rememberSaveable { mutableStateOf(if (fromDrawer) null else initialConversation ?: "") }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var conversationMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<LibraryRow?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var savedElsewhere by remember { mutableStateOf(false) }
    val rows = loaded?.rows.orEmpty().filter {
        it.name.contains(query.trim(), ignoreCase = true) &&
            (conversation == null || conversation in it.conversations) &&
            (source == 0 || (source == 1) == it.input)
    }
    val titles = loaded?.titles.orEmpty()
    fun add(item: LibraryRow, id: String) {
        scope.launch {
            working = true
            val result = LibraryFileActions.addToDraft(context, vm, id, item.handle)
            working = false
            if (result == LibraryAttachmentResult.Added) savedElsewhere = !onAdded(id) else error = true
        }
    }
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.file_library_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.padding(horizontal = 16.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.file_library_search), Modifier.testTag("file-library-search"))
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        SelectChip(conversation?.let { titles[it] ?: stringResource(R.string.file_library_current) } ?: stringResource(R.string.file_library_all), conversation != null, { conversationMenu = true })
                        DropdownMenu(expanded = conversationMenu, onDismissRequest = { conversationMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.file_library_all)) }, onClick = { conversation = null; conversationMenu = false })
                            titles.forEach { (id, title) -> DropdownMenuItem(text = { Text(title, maxLines = 1) }, onClick = { conversation = id; conversationMenu = false }) }
                            if (initialConversation != null && initialConversation !in titles) DropdownMenuItem(text = { Text(stringResource(R.string.file_library_current)) }, onClick = { conversation = initialConversation; conversationMenu = false })
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        val label = when (source) { 1 -> R.string.file_library_input; 2 -> R.string.file_library_output; else -> R.string.file_library_sources }
                        SelectChip(stringResource(label), source != 0, { sourceMenu = true })
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            listOf(R.string.file_library_sources, R.string.file_library_input, R.string.file_library_output).forEachIndexed { index, text ->
                                DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { source = index; sourceMenu = false })
                            }
                        }
                    }
                }
            }
            if (loaded?.partial == true && loaded?.failed == false) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.file_library_partial), Modifier.weight(1f).padding(vertical = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.warn)
                    TextButton(onClick = { revision++ }) { Text(stringResource(R.string.file_library_retry)) }
                }
            }
            when {
                loaded == null -> EmptyNote(stringResource(R.string.file_library_loading))
                loaded?.failed == true -> {
                    Column {
                        EmptyNote(stringResource(R.string.file_library_load_failed))
                        TextButton(onClick = { revision++ }, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)) { Text(stringResource(R.string.file_library_retry)) }
                    }
                }
                rows.isEmpty() -> EmptyNote(stringResource(R.string.file_library_empty))
                else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().testTag("file-library-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(rows) { _, item ->
                        val available by produceState<Boolean?>(null, item.handle, revision) {
                            value = null
                            value = try {
                                LibraryFileActions.isAvailable(context, item.handle)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                        }
                        Column(Modifier.fillMaxWidth().background(MikaTheme.colors.fill, RoundedCornerShape(14.dp)).padding(14.dp)) {
                            Text(item.name, style = MikaType.Body, color = MikaTheme.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val date = item.at?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: stringResource(R.string.file_library_unknown_time)
                            Text(listOfNotNull(item.size.takeIf { it.isNotBlank() }, date).joinToString(" · "), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                            val location = item.location.takeIf { it.isNotBlank() && it != item.name }
                                ?: (item.handle as? LibraryFileHandle.Exported)?.file?.uri?.takeIf { uri -> rows.count { it.name == item.name } > 1 && uri.isNotBlank() }
                            if (location != null) Text(location, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.conversations.joinToString(" · ") { titles[it] ?: it }, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (item.input) R.string.file_library_input else R.string.file_library_output), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                            Text(stringResource(when (available) { true -> R.string.file_library_available; false -> R.string.file_library_missing; null -> R.string.file_library_loading }), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (available == false) TextButton(onClick = { revision++ }) { Text(stringResource(R.string.file_library_retry)) }
                                TextButton(enabled = !working && available == true, onClick = { scope.launch { working = true; error = !LibraryFileActions.preview(context, item.handle); working = false } }) { Text(stringResource(R.string.file_library_open)) }
                                TextButton(enabled = !working && available == true, onClick = { scope.launch { working = true; error = !LibraryFileActions.share(context, item.handle); working = false } }) { Text(stringResource(R.string.file_library_share)) }
                                TextButton(enabled = !working && available == true, onClick = {
                                    if (fromDrawer) choosing = item
                                    else if (initialConversation != null) add(item, initialConversation)
                                    else if (vm.ui.value.conversationId == null) add(item, vm.libraryTargetConversation())
                                    else error = true
                                }) { Text(stringResource(R.string.file_library_add)) }
                            }
                        }
                    }
                }
            }
        }
    }
    choosing?.let { item ->
        AlertDialog(onDismissRequest = { choosing = null }, title = { Text(stringResource(R.string.file_library_choose)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (vm.canSwitch()) Text(stringResource(R.string.file_library_new), Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, store.newConversation())
                }.padding(vertical = 12.dp))
                titles.filter { (id, _) -> vm.canSwitch() || id == vm.ui.value.conversationId }.forEach { (id, title) -> Text(title, Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, id)
                }.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (vm.ui.value.conversationId == null) Text(stringResource(R.string.file_library_current), Modifier.fillMaxWidth().clickable {
                    choosing = null
                    add(item, vm.libraryTargetConversation())
                }.padding(vertical = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (error) AlertDialog(onDismissRequest = { error = false }, text = { Text(stringResource(R.string.file_library_error)) }, confirmButton = { TextButton(onClick = { error = false }) { Text(stringResource(R.string.done)) } })
    if (savedElsewhere) AlertDialog(onDismissRequest = { savedElsewhere = false }, text = { Text(stringResource(R.string.file_library_draft_saved)) }, confirmButton = { TextButton(onClick = { savedElsewhere = false }) { Text(stringResource(R.string.done)) } })
}
