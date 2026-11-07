package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** 一次调用是为哪个用途发的。存储里记 [key];认不出的 key(以后的版本写的)读成 [Other]。 */
enum class UsagePurpose(val key: String) {
    Main("main"), Vision("vision"), Light("light"), Other("other");

    companion object {
        fun of(role: ModelRole): UsagePurpose = when (role) {
            ModelRole.Main -> Main
            ModelRole.Vision -> Vision
            ModelRole.Light -> Light
        }

        fun of(key: String): UsagePurpose = entries.firstOrNull { it.key == key } ?: Other
    }
}

/** 一个模型是谁的、为哪个用途建的:记用量时原样带上。[providerName] 是建模型时服务商的名字(服务商删了也留着)。 */
data class UsageSource(val providerId: String, val providerName: String, val model: String, val purpose: UsagePurpose)

/**
 * 一次逻辑调用的结果:[usage] 为 null 表示服务商没报用量(或调用失败),不估 token。
 * [httpAttempts] 是这次调用实际发出的推理请求数;两参数形式保留给不关心请求数的观察者。
 */
fun interface UsageSink {
    fun record(source: UsageSource, usage: Usage?)

    fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) = record(source, usage)
}

/**
 * 给模型套上计量:每次 [ChatModel.complete](及逐段文字的那一路)结束都记一笔,成功且带用量的记 token。
 * 适配器为兼容服务而重发的每个 HTTP 请求都计入次数,最终回复的 token 只记一次;没发请求的本地失败不算 HTTP 请求。
 * 对调用方透明:名字、能力照旧,异常原样抛出,逐段文字照常回调;原模型支持逐段文字,套上的也支持。
 * 记账本身出错不影响调用。
 */
fun ChatModel.metered(source: UsageSource, sink: UsageSink): ChatModel =
    if (this is StreamingChatModel) MeteredStreamingModel(this, source, sink) else MeteredModel(this, source, sink)

private open class MeteredModel(private val inner: ChatModel, protected val source: UsageSource, protected val sink: UsageSink) : ChatModel {
    override val capabilities get() = inner.capabilities
    override val name: String get() = inner.name

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        counted { inner.complete(system, messages, tools) }

    protected suspend fun counted(call: suspend () -> ModelReply): ModelReply {
        // 在一次运行里调用的,也记到这次运行名下(见 RunUsageTally)
        val tally = currentCoroutineContext()[RunUsageTally]
        val attempts = ModelHttpAttempts()
        val reply = try {
            withContext(attempts) { call() }
        } catch (e: Throwable) {
            note(null, tally, attempts.started)
            throw e
        }
        note(reply.usage, tally, attempts.started)
        return reply
    }

    private fun note(usage: Usage?, tally: RunUsageTally?, httpAttempts: Int) {
        runCatching { sink.record(source, usage, httpAttempts) }
        tally?.add(usage, httpAttempts)
    }
}

private class MeteredStreamingModel(private val inner: StreamingChatModel, source: UsageSource, sink: UsageSink) :
    MeteredModel(inner, source, sink), StreamingChatModel {
    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = counted { inner.completeStreaming(system, messages, tools, onText) }
}
