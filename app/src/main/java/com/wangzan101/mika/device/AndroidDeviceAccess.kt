package com.wangzan101.mika.device

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.DeviceAccessPort
import com.wangzan101.mika.core.device.DeviceCredential
import com.wangzan101.mika.core.device.DeviceState
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** App 级唯一实例:设置页与经过认证的 MCP 共用,不经过模型、屏幕工具或运行记录。 */
class AndroidDeviceAccess(context: Context, helper: () -> IMikaShell?) {
    val settings = DeviceAccessSettings(File(context.filesDir, "settings"), KeystoreKeyCipher("mika-device-access"))
    val access = DeviceAccess(AndroidDeviceAccessPort(context, helper), settings)
}

internal class AndroidDeviceAccessPort(context: Context, private val helper: () -> IMikaShell?) : DeviceAccessPort {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val user = context.getSystemService(UserManager::class.java)

    override suspend fun state() = withContext(Dispatchers.IO) {
        DeviceState(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked,
            keyguard.isDeviceSecure, helper() != null, user.isUserUnlocked)
    }

    override suspend fun wake(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.wakeDevice() == true }.getOrDefault(false) }
    override suspend fun dismiss(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.dismissDeviceKeyguard() == true }.getOrDefault(false) }

    /**
     * 只认主屏 SystemUI 中唯一、类型匹配的密码框。SIM PIN/无法识别的厂商页不尝试。
     * 凭据按键要只投给 SystemUI 的窗口,靠安卓 13 起的定向注入;更早的系统在占位之前就报不支持,不留下暂停。
     * 输入框没拿到焦点时先请它聚焦,不行再点它一下;都不行但它所在的 SystemUI 窗口就是系统的输入焦点窗口,
     * 也算准备好(按键本来就只投给那个窗口,由锁屏页自己接)。认不出时在日志里记一句原因,不含屏上文字。
     */
    override suspend fun credentialReady(kind: CredentialKind): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return@withContext false
        val service = ScreenAccessibility.instance.value ?: return@withContext false.also { Log.i(TAG, "keyguard: accessibility off") }
        val fields = mutableListOf<Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>>()
        val systemUi = service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            w.root?.takeIf { it.packageName?.toString() == SYSTEM_UI }?.let { w to it }
        }
        for ((window, root) in systemUi) {
            val digits = mutableSetOf<Char>()
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo, depth: Int, sim: Boolean = false) {
                if (depth > 40) return
                val id = n.viewIdResourceName.orEmpty()
                val simChallenge = sim || "sim" in id.lowercase()
                if (n.isVisibleToUser && !simChallenge) {
                    if (n.isPassword) candidates += n
                    else KeyguardFields.digitKey(n.text?.toString(), n.contentDescription?.toString())?.let { digits += it }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1, simChallenge) }
            }
            visit(root, 0)
            val pad = KeyguardFields.isDigitPad(digits)
            candidates.filterTo(mutableListOf()) { n ->
                val cls = n.inputType and InputType.TYPE_MASK_CLASS
                KeyguardFields.matches(kind, n.viewIdResourceName.orEmpty(), n.className?.toString().orEmpty(), n.isPassword,
                    n.isEditable, numeric = cls == InputType.TYPE_CLASS_NUMBER, untyped = n.inputType == InputType.TYPE_NULL, digitPad = pad)
            }.mapTo(fields) { it to window }
        }
        val (field, window) = fields.singleOrNull() ?: return@withContext false.also {
            Log.i(TAG, "keyguard: ${fields.size} matching field(s) for $kind in ${systemUi.size} SystemUI window(s)")
        }
        val ready = field.isFocused || field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK) || window.isFocused
        if (!ready) Log.i(TAG, "keyguard: field found but neither it nor its window has focus")
        ready
    }

    override suspend fun enter(credential: DeviceCredential): Boolean {
        if (!credentialReady(credential.kind)) return false
        return withContext(Dispatchers.IO) {
            val bytes = ByteArray(credential.value.size) { credential.value[it].code.toByte() }
            try { runCatching { helper()?.enterDeviceCredential(credential.kind.ordinal, bytes) == true }.getOrDefault(false) }
            finally { bytes.fill(0) }
        }
    }

    private companion object {
        const val TAG = "MikaDeviceAccess"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
