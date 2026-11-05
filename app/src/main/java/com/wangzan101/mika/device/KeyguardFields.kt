package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/**
 * 通用规则:只认 SystemUI 里带密码标志的输入框;编辑框按输入类型区分 PIN(数字)和密码(非数字)。
 * 安卓原版锁屏的 PIN 框是自己画的(`pinEntry` / `PasswordTextView`),不声明 editable,只按原版的这组名字放行。
 * 编辑框没声明输入类型(TYPE_NULL,键盘由页面自己画)时看同一窗口里有没有 0–9 齐全的数字键:有就是 PIN 页,没有按密码页算。
 * 其余不可编辑的控件、SIM PIN 一律不认,认不准就不输入。
 */
internal object KeyguardFields {
    fun matches(kind: CredentialKind, id: String, className: String, password: Boolean, editable: Boolean, numeric: Boolean,
        untyped: Boolean = false, digitPad: Boolean = false): Boolean {
        if (!password || !id.startsWith("com.android.systemui:id/") || "sim" in id.lowercase()) return false
        val pinView = id == "com.android.systemui:id/pinEntry" && className == "com.android.keyguard.PasswordTextView"
        val pinField = numeric || untyped && digitPad
        return when (kind) {
            CredentialKind.Pin -> pinView || editable && pinField
            CredentialKind.Password -> editable && !pinField
        }
    }

    /** 控件的文字或描述是一位数字(可带字母注音,如「2 ABC」)时返回这位数字,用来认数字键盘的键。 */
    fun digitKey(text: String?, description: String?): Char? =
        listOfNotNull(text, description).firstNotNullOfOrNull { s ->
            s.trim().takeIf { it.isNotEmpty() && it[0] in '0'..'9' && (it.length == 1 || !it[1].isLetterOrDigit()) }?.get(0)
        }

    /** 0–9 都有键才算数字键盘。 */
    fun isDigitPad(keys: Set<Char>): Boolean = ('0'..'9').all { it in keys }
}
