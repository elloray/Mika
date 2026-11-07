package com.wangzan101.mika.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ThemeMode

/** 动效:时长和曲线。系统关了动画时([MikaTheme.reduceMotion])直接到位。 */
object MikaMotion {
    const val PAGE_MS = 220
    const val SWITCH_MS = 200
    /** 减速曲线:起步快、落地慢。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    fun <T> page(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(PAGE_MS, easing = Decelerate)
    fun <T> switch(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(SWITCH_MS, easing = Decelerate)
}

/** 系统「移除动画」(动画时长缩放为 0)时为真。 */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

val LocalReduceMotion = staticCompositionLocalOf { false }

object MikaTheme {
    val colors: MikaColors
        @Composable @ReadOnlyComposable get() = LocalMikaColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * 设置里的外观:跟随系统时看系统,一直浅色/深色时照设置。设置一改(设置页或任务里改的)当场换,不重建界面,
 * 所以页面、输入、滚动位置都不丢。
 */
@Composable
fun appearanceDark(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val mode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** 状态栏、导航栏的图标深浅跟界面一致:界面强制浅色而系统是深色时,图标也得是深色的才看得见。 */
fun ComponentActivity.systemBarsFor(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    // 旧系统上导航栏不能全透明,垫一层半透明的底;取值与 enableEdgeToEdge 默认的一致
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
    )
}

/** 深浅色按设置里的外观([appearanceDark]);同时把 Material 组件(弹窗、输入框、底部面板)的颜色对上。 */
@Composable
fun MikaTheme(dark: Boolean = appearanceDark(), reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) DarkScheme else LightScheme
    val layoutDirection = if (LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalMikaColors provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

private fun scheme(c: MikaColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceTint = c.surface,
        outline = c.ink3, outlineVariant = c.hair,
        error = c.bad, onError = c.onAccent,
    )
}

private val LightScheme = scheme(LightColors)
private val DarkScheme = scheme(DarkColors)
