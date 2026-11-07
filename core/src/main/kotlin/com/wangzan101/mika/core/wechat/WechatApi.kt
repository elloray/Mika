package com.wangzan101.mika.core.wechat

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val jsonBody = "application/json; charset=utf-8".toMediaType()

private object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
    override fun deserialize(decoder: Decoder): String =
        (decoder.decodeSerializableValue(JsonPrimitive.serializer())).content
}

@Serializable
data class WechatBaseInfo(@SerialName("channel_version") val channelVersion: String)

@Serializable
data class WechatAccount(
    val token: String,
    @SerialName("baseurl") val baseUrl: String,
    @SerialName("ilink_bot_id") val botId: String,
    @SerialName("ilink_user_id") val ownerId: String,
)

@Serializable
data class WechatQrStart(
    val ret: Int = 0,
    val errmsg: String? = null,
    val qrcode: String? = null,
    @SerialName("qrcode_img_content") val qrContent: String? = null,
)

@Serializable
data class WechatQrStatus(
    val ret: Int = 0,
    val errmsg: String? = null,
    val status: String = "wait",
    @SerialName("bot_token") val token: String? = null,
    @SerialName("ilink_bot_id") val botId: String? = null,
    @SerialName("baseurl") val baseUrl: String? = null,
    @SerialName("ilink_user_id") val ownerId: String? = null,
) {
    fun account(): WechatAccount? {
        if (status != "confirmed" || token.isNullOrBlank() || ownerId.isNullOrBlank()) return null
        return WechatAccount(token, baseUrl?.ifBlank { null } ?: DEFAULT_BASE_URL,
            botId.orEmpty(), ownerId)
    }
}

@Serializable
data class WechatText(val text: String = "")

@Serializable
data class WechatItem(
    val type: Int = 0,
    @SerialName("text_item") val textItem: WechatText? = null,
)

@Serializable
data class WechatMessage(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("from_user_id") val fromUserId: String? = null,
    @SerialName("to_user_id") val toUserId: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("create_time_ms") val createTimeMs: Long = 0,
    @SerialName("message_type") val messageType: Int = 0,
    @SerialName("message_state") val messageState: Int = 0,
    @SerialName("item_list") val items: List<WechatItem> = emptyList(),
    @SerialName("context_token") val contextToken: String? = null,
) {
    /** 第一版只收用户私聊文本；其他消息不进入任务系统。 */
    fun userText(): String? {
        if (messageType != 1 || messageId.isNullOrBlank() || fromUserId.isNullOrBlank()) return null
        return items.firstOrNull { it.type == 1 }?.textItem?.text?.takeIf { it.isNotBlank() }
    }
}

@Serializable
private data class UpdatesRequest(
    @SerialName("get_updates_buf") val cursor: String,
    @SerialName("base_info") val baseInfo: WechatBaseInfo,
)

@Serializable
data class WechatUpdates(
    val ret: Int = 0,
    val errmsg: String? = null,
    val msgs: List<WechatMessage> = emptyList(),
    @SerialName("get_updates_buf") val cursor: String = "",
)

@Serializable
private data class SendRequest(
    val msg: WechatMessage,
    @SerialName("base_info") val baseInfo: WechatBaseInfo,
)

class WechatApiException(val code: Int, message: String) : IOException(message)
class WechatHttpException(val httpCode: Int) : IOException("WeChat HTTP $httpCode")

/** 微信 Bot 的 HTTPS JSON 客户端。只负责协议，不持有任务或安卓生命周期。 */
class WechatApi(
    private val client: OkHttpClient,
    private val channelVersion: String,
    private val defaultBaseUrl: String = DEFAULT_BASE_URL,
) {
    fun startLogin(): WechatQrStart {
        val url = endpoint(defaultBaseUrl, "get_bot_qrcode").newBuilder()
            .addQueryParameter("bot_type", "3").build()
        val request = Request.Builder().url(url).headers(headers()).post(ByteArray(0).toRequestBody(null)).build()
        return execute(request, WechatQrStart.serializer())
    }

    fun pollLogin(qrcode: String): WechatQrStatus {
        val url = endpoint(defaultBaseUrl, "get_qrcode_status").newBuilder()
            .addQueryParameter("qrcode", qrcode).build()
        return execute(Request.Builder().url(url).headers(headers()).get().build(), WechatQrStatus.serializer())
    }

    fun updates(account: WechatAccount, cursor: String): WechatUpdates =
        execute(updatesRequest(account, cursor), WechatUpdates.serializer())

    /** 长轮询随调用协程取消，避免停止或换账号后旧请求继续与新请求并行。 */
    suspend fun updatesCancellable(account: WechatAccount, cursor: String): WechatUpdates =
        executeCancellable(updatesRequest(account, cursor), WechatUpdates.serializer())

    private fun updatesRequest(account: WechatAccount, cursor: String): Request {
        val body = UpdatesRequest(cursor, WechatBaseInfo(channelVersion))
        val payload = json.encodeToString(UpdatesRequest.serializer(), body).toRequestBody(jsonBody)
        return Request.Builder().url(endpoint(account.baseUrl, "getupdates")).headers(headers(account.token)).post(payload).build()
    }

    fun sendText(account: WechatAccount, peerId: String, clientId: String, text: String, contextToken: String?) {
        val message = WechatMessage(fromUserId = "", toUserId = peerId, clientId = clientId,
            messageType = 2, messageState = 2, items = listOf(WechatItem(type = 1, textItem = WechatText(text))),
            contextToken = contextToken)
        val body = SendRequest(message, WechatBaseInfo(channelVersion))
        post(account, "sendmessage", body, SendRequest.serializer(), WechatQrStart.serializer())
    }

    private inline fun <reified T, reified R> post(account: WechatAccount, path: String, body: T,
        bodySerializer: kotlinx.serialization.SerializationStrategy<T>, responseSerializer: kotlinx.serialization.DeserializationStrategy<R>): R {
        val payload = json.encodeToString(bodySerializer, body).toRequestBody(jsonBody)
        val request = Request.Builder().url(endpoint(account.baseUrl, path)).headers(headers(account.token)).post(payload).build()
        return execute(request, responseSerializer)
    }

    private fun <T> execute(request: Request, serializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        client.newCall(request).execute().use { response -> decode(response, serializer) }

    private suspend fun <T> executeCancellable(request: Request,
        serializer: kotlinx.serialization.DeserializationStrategy<T>): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val result = decode(it, serializer)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun <T> decode(response: Response, serializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw WechatHttpException(response.code)
        if (text.isBlank()) throw IOException("WeChat returned an empty response")
        val result = json.decodeFromString(serializer, text)
        val (code, message) = when (result) {
            is WechatQrStart -> result.ret to result.errmsg
            is WechatQrStatus -> result.ret to result.errmsg
            is WechatUpdates -> result.ret to result.errmsg
            else -> 0 to null
        }
        if (code != 0) throw WechatApiException(code, message ?: "WeChat API error $code")
        return result
    }

    private fun endpoint(base: String, path: String) =
        base.trimEnd('/').toHttpUrl().newBuilder().addPathSegments("ilink/bot/$path").build()

    private fun headers(token: String? = null) = okhttp3.Headers.Builder().apply {
        add("iLink-App-Id", "bot")
        add("iLink-App-ClientVersion", encodeVersion(channelVersion))
        if (token != null) {
            add("AuthorizationType", "ilink_bot_token")
            add("Authorization", "Bearer $token")
            add("X-WECHAT-UIN", randomUin())
        }
    }.build()

    private fun encodeVersion(version: String) =
        String.format("%08x", version.filter { it.isDigit() }.take(8).ifEmpty { "1" }.toLong().toInt())

    private fun randomUin(): String = Base64.getEncoder()
        .encodeToString(ByteArray(4).also { Random.nextBytes(it) })
}

