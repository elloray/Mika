package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.core.record.ConversationTokens
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.record.RunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 对话页上的 token 用量:每轮结束那一行带上这一轮的,顶栏闲着时写这个对话合起来的。数字和「查看过程」顶上那一行同一个。 */
object ChatTokensCopy {
    /** 「1.2 万 Token」,写法和 Token 用量页同一个。 */
    fun amount(n: Long): String = "${UsageCopy.tokens(n)} ${UsageCopy.UNIT}"

    /** 顶栏的副标题:「本对话共 8.6 万 Token」;有轮次没算全时「本对话至少 …」。 */
    fun conversation(t: ConversationTokens): String =
        tr(if (t.partial) R.string.chat_conversation_tokens_partial else R.string.chat_conversation_tokens, amount(t.tokens))

    /**
     * 顶栏副标题里的对话合计;一个数也没有时为 null。顶栏只在这一处用它(`ChatScreen` 里 `sub` 的最后一个 `?:`),
     * 不要这个副标题时删掉那一行即可,每轮结束那一行的 token 不受影响。
     */
    fun conversationSubtitle(endedRuns: List<String>, metrics: Map<String, RunMetrics>): String? =
        ConversationTokens.of(endedRuns, metrics)?.let(::conversation)
}

/**
 * 读这些轮的运行小结(运行记录关上时由写线程记,一轮刚结束时可能还没写好:没读全就隔一会儿再读缺的那几轮)。
 * 按运行编号取,不把整份小结都解析一遍;读盘在 IO 线程。
 * 换了轮次时先留着上次读到的,读完再换,顶栏和每轮那一行不闪。
 */
@Composable
fun rememberRunMetrics(runIds: List<String>): Map<String, RunMetrics> {
    val context = LocalContext.current
    val store = remember { RunStore(context).metrics }
    var found by remember { mutableStateOf(emptyMap<String, RunMetrics>()) }
    LaunchedEffect(runIds) {
        val wanted = runIds.toSet()
        if (wanted.isEmpty()) {
            found = emptyMap()
            return@LaunchedEffect
        }
        // 上次读到的里面还要的先留着,只去读缺的
        var read = found.filterKeys { it in wanted }
        for (wait in RETRY_MS) {
            val missing = wanted - read.keys
            if (missing.isEmpty()) break
            read = read + withContext(Dispatchers.IO) { store.byIds(missing) }
            found = read
            if (read.keys.containsAll(wanted)) break
            delay(wait)
        }
        found = read
    }
    return found
}

/** 没读全时再读的间隔;读完这几次还缺的(旧到被清掉了)就按缺着算。 */
private val RETRY_MS = listOf(300L, 1_000L, 2_000L, 4_000L, 0L)
