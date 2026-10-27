package io.github.zrpc.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.zrpc.data.ThemeMode

val Blurple = Color(0xFF5865F2)
val OnlineGreen = Color(0xFF23A55A)
val IdleYellow = Color(0xFFF0B232)
val DndRed = Color(0xFFF23F43)

private val Light = lightColorScheme(
    primary = Color(0xFF4752C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFF1F7A4D),
    tertiaryContainer = Color(0xFFA6F3C6),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE3E1EC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2B3BA6),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFF8BD6AB),
    tertiaryContainer = Color(0xFF005234),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF46464F),
)

@Composable
fun ZrpcTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
