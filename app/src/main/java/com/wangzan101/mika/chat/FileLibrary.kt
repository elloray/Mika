package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.cap.web.AttachedPage

/** 已保存消息里的文件记录。只读对话，不检查文件是否仍可打开。 */
class FileLibrary(private val conversations: ConversationStore) {
    /** 每次重新读取；调用方可结合 ConversationStore.changes 在后台刷新。 */
    fun list(): List<LibraryFile> = snapshot().files

    /** 返回已读出的文件，并标记目录或任一对话是否读取失败；可再次调用重试。 */
    fun snapshot(): FileLibrarySnapshot {
        val recordsSnapshot = conversations.allRecords()
        val result = mutableListOf<LibraryFile>()
        val outputsByUri = mutableMapOf<String, Int>()
        val inputsByPath = mutableMapOf<Pair<String, String>, Int>()
        for ((conversationId, records) in recordsSnapshot.conversations) {
            records.forEachIndexed { turnIndex, (turn, files) ->
                val source = FileSource(conversationId, turnIndex, turn.runId, turn.atMs)
                val messages = listOf(turn.userText) + turn.steers
                messages.forEachIndexed { messageIndex, text ->
                    val messageSource = source.copy(steerIndex = if (messageIndex == 0) null else messageIndex - 1)
                    AttachmentLines.parse(QuoteMessage.words(AttachedPage.words(text))).files.distinct().forEach { (path, size) ->
                        val occurrence = InputFileSource(messageSource, size)
                        val key = conversationId to path
                        val index = inputsByPath[key]
                        if (index == null) {
                            inputsByPath[key] = result.size
                            result += LibraryFile.Input(path, listOf(occurrence))
                        } else {
                            val existing = result[index] as LibraryFile.Input
                            result[index] = existing.copy(sources = existing.sources + occurrence)
                        }
                    }
                }
                files.forEach { file ->
                    val occurrence = SharedFileSource(source, file)
                    // 空 URI 无法证明是同一个文件；文件名、路径和摘要也不用于推断身份。
                    val uri = file.uri.takeIf { it.isNotBlank() }
                    val index = uri?.let { outputsByUri[it] }
                    if (index == null) {
                        if (uri != null) outputsByUri[uri] = result.size
                        result += LibraryFile.Output(listOf(occurrence))
                    } else {
                        val existing = result[index] as LibraryFile.Output
                        result[index] = existing.copy(sources = existing.sources + occurrence)
                    }
                }
            }
        }
        return FileLibrarySnapshot(result, recordsSnapshot.hasReadFailures)
    }
}

data class FileLibrarySnapshot(val files: List<LibraryFile>, val hasReadFailures: Boolean)

/** turnIndex 和 steerIndex 从零开始；steerIndex 为 null 表示该轮的首条用户消息。 */
data class FileSource(
    val conversationId: String,
    val turnIndex: Int,
    val runId: String?,
    val atMs: Long?,
    val steerIndex: Int? = null,
)

data class InputFileSource(val source: FileSource, val sizeLabel: String)
data class SharedFileSource(val source: FileSource, val file: SharedFile)

sealed interface LibraryFile {
    /** 同一对话的相同路径聚合；路径可能被覆盖，不表示各次内容相同。 */
    data class Input(val path: String, val sources: List<InputFileSource>) : LibraryFile {
        val name: String get() = path.substringAfterLast('/')
        val latestAtMs: Long? get() = sources.mapNotNull { it.source.atMs }.maxOrNull()
    }

    /** 相同非空 URI 的产物合并；每条来源保留当时记录的完整元数据。 */
    data class Output(val sources: List<SharedFileSource>) : LibraryFile {
        val latestAtMs: Long? get() = sources.mapNotNull { it.source.atMs }.maxOrNull()
    }
}
