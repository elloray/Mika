package com.wangzan101.mika.self

import com.wangzan101.mika.core.self.SelfScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** 要打开的 Workspace 文件:对话编号 + 沙箱路径。 */
data class SelfFileRequest(val conversationId: String, val path: String, val name: String?)

/** 一次自身 UI 请求:打开一页,或打开一个文件。 */
data class SelfUiRequest(
    val id: Long,
    val screen: SelfScreen? = null,
    val file: SelfFileRequest? = null,
    val url: String? = null,
    val loginHost: String? = null,
    val newTab: Boolean = false,
    val show: Boolean = true,
    val note: String? = null,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * 进程内的自身 UI 请求。任务在服务里跑,界面在 MainActivity 里;两边同一进程,
 * 用 StateFlow 留一份请求,界面回来时照样能处理。仿 [com.wangzan101.mika.memory.MemoryRuntime.openRequest]。
 */
class SelfUi {
    private val _request = MutableStateFlow<SelfUiRequest?>(null)
    val request: StateFlow<SelfUiRequest?> = _request.asStateFlow()
    private val serial = AtomicLong()

    fun requestOpen(
        screen: SelfScreen? = null,
        file: SelfFileRequest? = null,
        url: String? = null,
        loginHost: String? = null,
        newTab: Boolean = false,
        show: Boolean = true,
        note: String? = null,
    ): Long {
        val id = serial.incrementAndGet()
        _request.value = SelfUiRequest(id, screen, file, url, loginHost, newTab, show, note)
        return id
    }

    /** 界面处理完了;只清掉同一条,不误删后来的请求。 */
    fun consumed(id: Long) {
        if (_request.value?.id == id) _request.value = null
    }
}

/** 后台通知点开后带回来的 extra。 */
object SelfIntents {
    const val EXTRA_SCREEN = "mika_self_screen"
    const val EXTRA_FILE = "mika_self_file"
    const val EXTRA_FILE_CONVERSATION = "mika_self_file_conversation"
    const val EXTRA_FILE_NAME = "mika_self_file_name"
    const val EXTRA_URL = "mika_self_url"
    const val EXTRA_LOGIN_HOST = "mika_self_login_host"
    const val EXTRA_NEW_TAB = "mika_self_new_tab"
    const val EXTRA_SHOW = "mika_self_show"
}
