package com.wangzan101.mika.core.tool

/**
 * 工具在自己的 execute 里等用户回答一句话(自身操作的同一次同意)。
 * 没有可问的人(无人值守、超时、渠道无法回答)时返回 null;调用方必须按拒绝处理,绝不自动同意。
 */
internal interface AskPort {
    suspend fun awaitAnswer(prompt: String, options: List<String>?): String?
}
