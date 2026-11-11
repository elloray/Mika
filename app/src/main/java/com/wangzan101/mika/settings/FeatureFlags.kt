package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.capsule.CapsuleDock
import com.wangzan101.mika.capsule.CardDocking
import com.wangzan101.mika.capsule.CapsuleIdleOpacity
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.DockSide
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.usage.TokenBudgetLimits
import com.wangzan101.mika.task.ParallelCapability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 功能开关。提速开关存成 [SpeedFlags.describe] 的格式,评测跑器可用参数覆盖。 */
class FeatureFlags(private val prefs: SharedPreferences) {
    /** 还没做过的默认值升级(见 [SPEED_UPGRADES])各做一次;升级之后用户手动关掉就保持关闭。 */
    fun speedFlags(): SpeedFlags {
        val flags = prefs.getString(SPEED_FLAGS, null)?.let { runCatching { SpeedFlags.parse(it) }.getOrNull() } ?: SpeedFlags.OFF
        val pending = SPEED_UPGRADES.filter { (key, _) -> !prefs.getBoolean(key, false) }
        if (pending.isEmpty()) return flags
        val upgraded = pending.fold(flags) { f, (_, upgrade) -> upgrade(f) }
        setSpeed(upgraded)
        return upgraded
    }

    fun setSpeed(flags: SpeedFlags) {
        val edit = prefs.edit().putString(SPEED_FLAGS, flags.describe())
        SPEED_UPGRADES.forEach { (key, _) -> edit.putBoolean(key, true) }
        edit.commit()
    }

    /** 并行任务时最多同时跑几个(用户设定,1–3);真正的上限还要看设备,见 ParallelCapability。 */
    fun parallelMax(): Int = prefs.getInt(PARALLEL_MAX, ParallelCapability.DEFAULT_MAX)

    fun setParallelMax(max: Int) {
        prefs.edit().putInt(PARALLEL_MAX, max).commit()
    }

    /** 每个任务最多走多少步;null 表示不限制。默认不限制,下次任务起生效。 */
    fun taskStepLimit(): Int? = prefs.getInt(TASK_STEP_LIMIT, 0).takeIf { it in 1..MAX_TASK_STEP_LIMIT }

    fun setTaskStepLimit(limit: Int?) {
        prefs.edit().putInt(TASK_STEP_LIMIT, (limit ?: 0).coerceIn(0, MAX_TASK_STEP_LIMIT)).commit()
    }

    /** 每个任务允许已报告的输入加输出 Token 总量;null 表示不限制,下次任务起生效。 */
    fun taskTokenBudget(): Long? = prefs.getLong(TASK_TOKEN_BUDGET, 0L).takeIf(TokenBudgetLimits::valid)

    fun setTaskTokenBudget(limit: Long?) {
        prefs.edit().putLong(TASK_TOKEN_BUDGET, limit?.takeIf(TokenBudgetLimits::valid) ?: 0L).commit()
    }

    /** 干活时屏幕保持常亮;默认开,用户可以关。 */
    fun keepScreenOn(): Boolean = prefs.getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).commit()
    }

    /** 干活时把被操作的 App 静音;默认开,用户可以关。 */
    fun muteTarget(): Boolean = prefs.getBoolean(MUTE_TARGET, true)

    fun setMuteTarget(on: Boolean) {
        prefs.edit().putBoolean(MUTE_TARGET, on).commit()
    }

    /** 在 App 里操作的节奏:快、标准(默认)、像人(见 [OperatingPace])。 */
    fun operatingPace(): OperatingPace = OperatingPace.parse(prefs.getString(OPERATING_PACE, null)) ?: OperatingPace.DEFAULT

    fun setOperatingPace(pace: OperatingPace) {
        prefs.edit().putString(OPERATING_PACE, pace.name).commit()
    }

    /** 界面配色:跟随系统、一直浅色、一直深色(见 [ThemeMode])。界面跟着 [themeModeChanges] 当场换,不用重建。 */
    fun themeMode(): ThemeMode = ThemeMode.parse(prefs.getString(THEME_MODE, null))

    /** 外观的当前值,之后每次改动再发一次(设置页改的、任务里改的都一样)。 */
    fun themeModeChanges(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_MODE) trySend(themeMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE, mode.name).commit()
    }

    /**
     * 允许 Mika 改自己的设置(见 core 的 SelfConfig):模型与 Key、删数据不在允许之列。
     * 默认关;开着的时候任务里才有 settings_list、settings_set 这两件工具。
     */
    fun selfConfig(): Boolean = prefs.getBoolean(SELF_CONFIG, false)

    fun setSelfConfig(on: Boolean) {
        prefs.edit().putBoolean(SELF_CONFIG, on).commit()
    }

    /** 悬浮胶囊:关闭、有任务时显示(默认)、一直显示。 */
    fun capsuleMode(): CapsuleMode = CapsuleMode.parse(prefs.getString(CAPSULE_MODE, null))

    fun setCapsuleMode(mode: CapsuleMode) {
        prefs.edit().putString(CAPSULE_MODE, mode.name).commit()
    }

    /** 胶囊开关的当前值,之后每次改动再发一次。 */
    fun capsuleModeChanges(): Flow<CapsuleMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 清空全部设置时 key 为 null
            if (key == null || key == CAPSULE_MODE) trySend(capsuleMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 胶囊静置后的不透明度;只认 20/40/70/100,默认 40。 */
    fun capsuleIdleOpacity(): CapsuleIdleOpacity = CapsuleIdleOpacity.parse(prefs.getInt(CAPSULE_IDLE_OPACITY, CapsuleIdleOpacity.DEFAULT.percent))

    fun setCapsuleIdleOpacity(opacity: CapsuleIdleOpacity) {
        prefs.edit().putInt(CAPSULE_IDLE_OPACITY, opacity.percent).commit()
    }

    fun capsuleIdleOpacityChanges(): Flow<CapsuleIdleOpacity> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CAPSULE_IDLE_OPACITY) trySend(capsuleIdleOpacity())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleIdleOpacity())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /**
     * 用户点了「完全退出」,要到下次打开 Mika 的界面为止([com.wangzan101.mika.quit.MikaQuit])。
     * 在这期间进程可能被无障碍服务重新拉起:胶囊不画、后台服务不补起、自动恢复也不做,直到用户自己打开 App。
     */
    fun quitRequested(): Boolean = prefs.getBoolean(QUIT_REQUESTED, false)

    fun setQuitRequested(on: Boolean) {
        prefs.edit().putBoolean(QUIT_REQUESTED, on).commit()
    }

    /** 退出标记的当前值,之后每次改动再发一次。 */
    fun quitChanges(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == QUIT_REQUESTED) trySend(quitRequested())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(quitRequested())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 胶囊停在哪:左边还是右边、高度占可摆范围的比例;没拖过时在右上角。 */
    fun capsuleDock(): CapsuleDock = CapsuleDock(
        if (prefs.getString(CAPSULE_SIDE, null) == DockSide.Left.name) DockSide.Left else DockSide.Right,
        prefs.getFloat(CAPSULE_HEIGHT, 0f).coerceIn(0f, 1f),
    )

    fun setCapsuleDock(dock: CapsuleDock) {
        prefs.edit().putString(CAPSULE_SIDE, dock.side.name).putFloat(CAPSULE_HEIGHT, dock.fraction).apply()
    }

    /**
     * 展开的卡片停在哪:上边在可摆范围里的比例(见 [com.wangzan101.mika.capsule.CardDocking.release]);没记过时最高的卡片在正中。
     * 以前按固定高的卡片记的比例正好就是它的上边比例,照样读。
     */
    fun capsuleCardFraction(): Float = prefs.getFloat(CAPSULE_CARD_HEIGHT, CardDocking.DEFAULT_FRACTION).coerceIn(0f, 1f)

    fun setCapsuleCardFraction(fraction: Float) {
        prefs.edit().putFloat(CAPSULE_CARD_HEIGHT, fraction.coerceIn(0f, 1f)).apply()
    }

    companion object {
        const val PREFS = "feature_flags"
        /** 每个任务步数上限能设的最大值;0/null 表示不限制。 */
        const val MAX_TASK_STEP_LIMIT = 500
        private const val SPEED_FLAGS = "speed_flags"
        /**
         * 提速开关默认值的升级,按先后各做一次(键记下做过没有):v2 打开分级读屏、连续操作、按需看图;
         * v3 打开经验回放(学习改看结局判定之后路线才记得下来)。
         */
        private val SPEED_UPGRADES: List<Pair<String, (SpeedFlags) -> SpeedFlags>> = listOf(
            "speed_defaults_applied_v2" to { f -> f.copy(inlineScreen = true, batchSteps = true, adaptiveVision = true) },
            "speed_defaults_applied_v3" to { f -> f.copy(replay = true) },
        )
        private const val PARALLEL_MAX = "parallel_max"
        private const val TASK_STEP_LIMIT = "task_step_limit"
        private const val TASK_TOKEN_BUDGET = "task_token_budget"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val MUTE_TARGET = "mute_target"
        private const val OPERATING_PACE = "operating_pace"
        private const val SELF_CONFIG = "self_config"
        private const val THEME_MODE = "theme_mode"
        private const val CAPSULE_MODE = "capsule_mode"
        private const val CAPSULE_IDLE_OPACITY = "capsule_idle_opacity"
        private const val QUIT_REQUESTED = "quit_requested"
        private const val CAPSULE_SIDE = "capsule_side"
        private const val CAPSULE_HEIGHT = "capsule_height"
        private const val CAPSULE_CARD_HEIGHT = "capsule_card_height"

        fun get(context: Context) = FeatureFlags(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
