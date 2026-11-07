package com.wangzan101.mika.core.remote

import kotlinx.serialization.json.JsonObject

/** Read-only projections of the phone's saved conversations and runs, separately enabled by its owner. */
interface RemoteHistory {
    val enabled: Boolean
    fun conversations(offset: Int, limit: Int): JsonObject
    fun turns(conversation: String, offset: Int, limit: Int): JsonObject?
    fun run(conversation: String, runId: String, offset: Int, limit: Int): JsonObject?
    fun body(conversation: String, runId: String, spanId: String, offset: Int, limit: Int): JsonObject?
}
