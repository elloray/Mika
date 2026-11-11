package com.wangzan101.mika.device

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
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
    override suspend fun revealCredential(): Boolean = withContext(Dispatchers.IO) {
        runCatching { helper()?.revealDeviceCredential() == true }.getOrDefault(false)
    }

    /**
     * 上滑后仍是生物识别优先页、没有凭据框时,点 SystemUI 里最大的可点非数字目标一次(「用密码解锁」这类大按钮)。
     * 只认大面积目标,「取消」「紧急呼叫」等小文字链接被排除;没有符合条件的就不点。
     */
    override suspend fun revealCredentialFallback(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return@withContext false
        val service = ScreenAccessibility.instance.value ?: return@withContext false
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != SYSTEM_UI) continue
            val windowBounds = Rect().also(window::getBoundsInScreen)
            val candidates = mutableListOf<KeyguardReveal.Candidate>()
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40) return
                val sim = "sim" in n.viewIdResourceName.orEmpty().lowercase()
                if (n.isVisibleToUser && !sim && n.isEnabled && n.isClickable) {
                    val digit = KeyguardFields.digitKey(n.text?.toString(), n.contentDescription?.toString()) != null
                    val b = Rect().also(n::getBoundsInScreen)
                    candidates += KeyguardReveal.Candidate(
                        KeyguardReveal.Bounds(b.left, b.top, b.right, b.bottom), clickable = true, digit = digit)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
            val target = KeyguardReveal.fallbackTarget(candidates,
                KeyguardReveal.Bounds(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom),
                MIN_FALLBACK_AREA_FRACTION)
            if (target != null) {
                val (cx, cy) = KeyguardReveal.center(target)
                val ok = runCatching { helper()?.revealDeviceCredentialTap(cx, cy) == true }.getOrDefault(false)
                Log.i(TAG, "keyguard: credential fallback tap at ($cx,$cy) sent=$ok")
                return@withContext ok
            }
        }
        false
    }

    /**
     * 只认主屏 SystemUI 中唯一、类型匹配的密码框。SIM PIN/无法识别的页面不尝试。
     * 没有密码节点时,完整且布局一致的数字键盘可作为 PIN 输入目标;普通密码和图案页不降级。
     * 凭据按键要只投给 SystemUI 的窗口,靠安卓 13 起的定向注入;更早的系统在占位之前就报不支持,不留下暂停。
     * 输入框没拿到焦点时先请它聚焦,不行再点它一下;纯数字键盘不点击容器,只接受已聚焦的 SystemUI 窗口。
     * 认不出时在日志里记一句原因,不含屏上文字。
     */
    override suspend fun credentialReady(kind: CredentialKind): Boolean = withContext(Dispatchers.IO) {
        val fields = credentialFields().filter { it.kind == kind }
        val target = fields.singleOrNull() ?: return@withContext false.also {
            Log.i(TAG, "keyguard: ${fields.size} matching field(s) for $kind")
        }
        val (field, window) = target
        val ready = if (field == null) window.isFocused else field.isFocused ||
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK) || window.isFocused
        if (!ready) Log.i(TAG, "keyguard: target found but its SystemUI window is not focused")
        ready
    }

    /** 只读识别当前唯一的系统凭据输入目标,不聚焦、不输入,不按容器名字推测锁屏类型。 */
    override suspend fun detectedCredentialKind(): CredentialKind? = withContext(Dispatchers.IO) {
        scanCredentialFields().targets.singleOrNull()?.kind
    }

    /** MCP 可直接带回的受限诊断:只有 SystemUI 窗口、密码节点和数字键盘的技术属性,没有节点文字。 */
    override suspend fun credentialDiagnostics(): List<String> = withContext(Dispatchers.IO) {
        scanCredentialFields().diagnostics.also { Log.i(TAG, "keyguard diagnostics: ${it.joinToString()}") }
    }

    private data class CredentialTarget(val node: AccessibilityNodeInfo?, val window: AccessibilityWindowInfo, val kind: CredentialKind)
    private data class CredentialScan(val targets: List<CredentialTarget>, val diagnostics: List<String>)

    private fun credentialFields(): List<CredentialTarget> = scanCredentialFields().targets

    private fun scanCredentialFields(): CredentialScan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return CredentialScan(emptyList(), listOf("api_below_33"))
        }
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) {
            return CredentialScan(emptyList(), listOf("keyguard_not_active"))
        }
        val service = ScreenAccessibility.instance.value
            ?: return CredentialScan(emptyList(), listOf("accessibility_unavailable")).also {
                Log.i(TAG, "keyguard: accessibility off")
            }
        val fields = mutableListOf<CredentialTarget>()
        val pinPads = mutableListOf<CredentialTarget>()
        val diagnostics = mutableListOf<String>()
        var passwordNodes = 0
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
        diagnostics += "display_windows=${windows.size}"
        windows.take(MAX_WINDOW_DIAGNOSTICS).forEachIndexed { index, window ->
            diagnostics += KeyguardDiagnostics.windowInfo(index, window.type, window.root?.packageName,
                window.isFocused, window.isActive)
        }
        val systemUi = windows.mapNotNull { w ->
            w.root?.takeIf { it.packageName?.toString() == SYSTEM_UI }?.let { w to it }
        }
        diagnostics += "systemui_windows=${systemUi.size}"
        for ((windowIndex, pair) in systemUi.withIndex()) {
            val (window, root) = pair
            val digits = mutableSetOf<Char>()
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            val padKeys = mutableListOf<KeyguardPinPad.DigitKey>()
            fun visit(n: AccessibilityNodeInfo, depth: Int, sim: Boolean = false) {
                if (depth > 40) return
                val id = n.viewIdResourceName.orEmpty()
                val simChallenge = sim || "sim" in id.lowercase()
                if (n.isVisibleToUser && !simChallenge) {
                    if (n.isPassword) candidates += n
                    else KeyguardFields.digitKey(n.text?.toString(), n.contentDescription?.toString())?.let { digit ->
                        digits += digit
                        if (n.isEnabled && (n.isClickable || n.isFocusable)) {
                            val bounds = Rect().also(n::getBoundsInScreen)
                            padKeys += KeyguardPinPad.DigitKey(digit,
                                KeyguardPinPad.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1, simChallenge) }
            }
            visit(root, 0)
            passwordNodes += candidates.size
            val pad = KeyguardFields.isDigitPad(digits)
            val bounds = Rect().also(window::getBoundsInScreen)
            val pinLayout = KeyguardPinPad.matches(padKeys,
                KeyguardPinPad.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
            diagnostics += KeyguardDiagnostics.window(windowIndex, window.isFocused, candidates.size,
                digits.size, padKeys.size, pinLayout)
            for (n in candidates) {
                val cls = n.inputType and InputType.TYPE_MASK_CLASS
                val numeric = cls == InputType.TYPE_CLASS_NUMBER
                val untyped = n.inputType == InputType.TYPE_NULL
                val kind = CredentialKind.entries.singleOrNull { kind ->
                    KeyguardFields.matches(kind, n.viewIdResourceName.orEmpty(), n.className?.toString().orEmpty(), n.isPassword,
                        n.isEditable, numeric = numeric, untyped = untyped, digitPad = pad)
                }
                if (diagnostics.count { it.startsWith("field[") } < MAX_FIELD_DIAGNOSTICS) {
                    diagnostics += KeyguardDiagnostics.field(n.viewIdResourceName, n.className, n.isEditable,
                        numeric, untyped, pad, n.isFocused, kind)
                }
                if (kind != null) fields += CredentialTarget(n, window, kind)
            }
            if (pinLayout) pinPads += CredentialTarget(null, window, CredentialKind.Pin)
        }
        // 有密码节点时沿用原路径,即使它无法识别也不猜测;没有时才考虑纯数字 PIN 页。
        val targets = if (passwordNodes == 0) pinPads else fields
        return CredentialScan(targets, diagnostics.take(MAX_DIAGNOSTICS))
    }

    override suspend fun enter(credential: DeviceCredential): Boolean {
        if (!credentialReady(credential.kind)) return false
        return withContext(Dispatchers.IO) {
            val clear = residualClear(credential.kind)
            val bytes = ByteArray(credential.value.size) { credential.value[it].code.toByte() }
            try { runCatching { helper()?.enterDeviceCredential(credential.kind.ordinal, bytes, clear) == true }.getOrDefault(false) }
            finally { bytes.fill(0) }
        }
    }

    /** 凭据框已有输入时按已有长度回删;空框(最常见,也是生物识别优先页)不删,避免空框 DEL 关掉密码页。 */
    private fun residualClear(kind: CredentialKind): Int {
        val target = credentialFields().singleOrNull { it.kind == kind } ?: return 0
        val node = target.node ?: return 0
        val length = runCatching { node.text?.length ?: 0 }.getOrDefault(0)
        return length.coerceIn(0, MAX_CLEAR)
    }

    private companion object {
        const val TAG = "MikaDeviceAccess"
        const val SYSTEM_UI = "com.android.systemui"
        const val MAX_WINDOW_DIAGNOSTICS = 6
        const val MAX_FIELD_DIAGNOSTICS = 8
        const val MAX_DIAGNOSTICS = 20
        const val MAX_CLEAR = 16
        const val MIN_FALLBACK_AREA_FRACTION = 0.08f
    }
}
