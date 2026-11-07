package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

internal const val ROOT = "root"

/** 以 root 跑命令的端口:已 root 的手机上、用户打开了「允许 Mika 执行 root 命令」时由 App 给出,否则不给(工具也就不登记)。 */
interface RootPort {
    /** 以 root 跑 [command],最多 [timeoutMs]。会阻塞,实现自己换到 IO 线程。 */
    suspend fun run(command: String, timeoutMs: Long): RootRun
}

/** 一条 root 命令的结局。 */
sealed interface RootRun {
    /**
     * 跑了。[stdout]、[stderr] 各最多留 1 MB,多的丢掉并计入 [droppedBytes];[timedOut]:到时限被结束。
     */
    data class Done(
        val exitCode: Int, val timedOut: Boolean, val stdout: String, val stderr: String, val droppedBytes: Long, val durationMs: Long,
    ) : RootRun

    /** su 拒绝了(用户在 root 管理器里收回了授权)。 */
    data object Denied : RootRun

    /** su 没回话。 */
    data object NoAnswer : RootRun

    /** 手机上找不到 su 了。 */
    data object Unavailable : RootRun
}

/**
 * root 命令:一个工具 [RootRunCommand]。只有用户打开了、App 给了端口才装上;不动屏幕,不收起(只有一个工具)。
 * 会改动东西的命令询问时先问用户(工具按参数声明,见 [RootRunCommand.approvalRisk])。
 */
internal class RootCapability(val port: RootPort) : Capability {
    override val id = ROOT

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = RootGroup.tools

    override fun sections(env: PromptEnv): List<String> =
        if (RootGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(ROOT))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    companion object {
        val KEY = CapabilityKey<RootCapability>(ROOT)
    }
}

internal val ToolContext.rootPort: RootPort? get() = this[RootCapability.KEY]?.port
