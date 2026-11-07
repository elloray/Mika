package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.shell.AppAudioKey

/**
 * 任务期间让 Mika 屏上的 App 不出声。PLAY_AUDIO 按「包 + 用户」设([AppAudioKey]),本来那份和另一份(分身、工作资料)分开静。
 *
 * - [beforeOpen]:Mika 要启动某个 App 时,发出启动之前先静上,开头几秒就没声;这一步失败先不报,等它真开出来再试。
 * - [opened]:Mika 屏上冒出一个 App(启动的目标,或从别的 App 里点开、另一份顶上来的):没静过就静,静不了记成「可能出声」。
 * - [notOpened]:启动被拒(没装、用户正用着…),提前静上又没开出来的那个马上还原,不让它在任务期间白白没声。
 * - [release]:用户接管时把这个 App 的声音还回去;之后再 [opened] 又会静上。
 * - [unmute]:用户明确恢复声音;这次任务里之后不再自动静它,即使接管交回也保持有声。
 *
 * 不碰的:Mika 自己;没有桌面入口的系统包(系统界面、选择框、权限框这一类,静了会连带系统的声音);
 * 用户此刻在主屏上看得见的(静音按包作用,静了用户手上的也没声)。设置里关掉静音时一个也不静。
 * 收尾时由 [AudioMute.restoreAll] 一起还原,包括任务中途静上的。
 */
class AppMuter(
    private val audio: AudioMute,
    /** 设置 › 任务运行的「打开的 App 静音」;每次现读,任务中途关掉也算数。 */
    private val enabled: () -> Boolean,
    /** 这个包能不能静(见类说明的「不碰的」前两条)。 */
    private val mutable: (packageName: String) -> Boolean,
    /** 这个包([user] 那份,null 为本来那份)此刻在主屏上看得见。 */
    private val onMainScreen: (packageName: String, user: Int?) -> Boolean,
    /** 应该静却没静成:任务进度上提示「该 App 可能会出声」。 */
    private val failed: (packageName: String) -> Unit,
) {
    /** 这次任务里已静上(还没还原)的键。 */
    private val muted = HashSet<String>()

    /** 启动前静上、还没见它开出来的键:启动被拒时据此还原。 */
    private val early = HashSet<String>()

    /** 用户明确恢复过声音的键;这次任务里不再自动静它。 */
    private val unmuted = HashSet<String>()

    @Synchronized
    fun beforeOpen(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (key in muted || key in unmuted || !wanted(packageName) || onMainScreen(packageName, user)) return
        if (audio.mute(key)) {
            muted += key
            early += key
        }
    }

    @Synchronized
    fun opened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (key in muted || key in unmuted || !wanted(packageName)) return
        // 用户在主屏上正用着它:不静,照实说它可能出声
        if (onMainScreen(packageName, user) || !audio.mute(key)) {
            failed(packageName)
            return
        }
        muted += key
    }

    @Synchronized
    fun notOpened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (!early.remove(key)) return
        if (audio.restore(key)) muted -= key
    }

    /** 还原一个 App 的声音(用户接管);还原不了返回 false,留在记录里收尾时再试。 */
    @Synchronized
    fun release(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (!audio.restore(key)) return false
        muted -= key
        return true
    }

    /**
     * 用户明确恢复声音。成功后本次任务不再自动静它;失败时保留静音与持久化记录,
     * 重复点击可以重试,任务收尾的 restoreAll 也仍能恢复。
     */
    @Synchronized
    fun unmute(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        if (!audio.restore(key)) return false
        early -= key
        muted -= key
        unmuted += key
        return true
    }

    private fun wanted(packageName: String) = enabled() && mutable(packageName)
}
