package com.wangzan101.mika.cap.phone.shell

/** 同步键盘注入的结果和 ASCII 范围;控制字符可能触发编辑器的其他动作,不作为文字输入。 */
internal object AsciiTextKeys {
    const val NOT_SENT = 0
    const val DISPATCHED = 1
    const val UNCONFIRMED = 2

    fun supports(text: String): Boolean = text.length in 1..256 && text.all { it in ' '..'~' }
}
