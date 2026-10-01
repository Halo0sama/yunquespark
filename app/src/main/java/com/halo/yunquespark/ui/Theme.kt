package com.halo.yunquespark.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 云雀青蓝
val SkylarkBlue = Color(0xFF5B6C9E)
val SkylarkBlueDark = Color(0xFF8FA3D9)
val SparkAmber = Color(0xFFD99A2B)

// 浅色主题（全 token 显式，杜绝组件回退到错误默认值）
private val LightColors = lightColorScheme(
    primary = SkylarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3F5),
    onPrimaryContainer = Color(0xFF1B2440),
    secondary = SparkAmber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4E3C4),
    onSecondaryContainer = Color(0xFF3E2E10),
    tertiary = Color(0xFF5B6C9E),
    onTertiary = Color.White,
    background = Color(0xFFF3F1EC),
    onBackground = Color(0xFF1B1C20),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFFE9E6DF),
    onSurfaceVariant = Color(0xFF46464C),
    surfaceDim = Color(0xFFDBD9D2),
    surfaceBright = Color(0xFFFBFAF7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3EE),
    surfaceContainer = Color(0xFFEFEDE8),
    surfaceContainerHigh = Color(0xFFE9E7E1),
    surfaceContainerHighest = Color(0xFFE3E1DB),
    outline = Color(0xFF77767E),
    outlineVariant = Color(0xFFC8C6CE),
    inverseSurface = Color(0xFF303035),
    inverseOnSurface = Color(0xFFF2F0F8),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
)

// 深色主题（全 token 显式）
private val DarkColors = darkColorScheme(
    primary = SkylarkBlueDark,
    onPrimary = Color(0xFF101426),
    primaryContainer = Color(0xFF384468),
    onPrimaryContainer = Color(0xFFDDE3F5),
    secondary = SparkAmber,
    onSecondary = Color(0xFF241A04),
    secondaryContainer = Color(0xFF3E2E10),
    onSecondaryContainer = Color(0xFFF4E3C4),
    tertiary = Color(0xFF8FA3D9),
    onTertiary = Color(0xFF101426),
    background = Color(0xFF15161B),
    onBackground = Color(0xFFE4E2E9),
    surface = Color(0xFF1C1E25),
    onSurface = Color(0xFFE4E2E9),
    surfaceVariant = Color(0xFF46464C),
    onSurfaceVariant = Color(0xFFC7C5CE),
    surfaceDim = Color(0xFF141519),
    surfaceBright = Color(0xFF3A3A40),
    surfaceContainerLowest = Color(0xFF0F1014),
    surfaceContainerLow = Color(0xFF1C1E25),
    surfaceContainer = Color(0xFF20222A),
    surfaceContainerHigh = Color(0xFF2A2B33),
    surfaceContainerHighest = Color(0xFF35363E),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464C),
    inverseSurface = Color(0xFFE4E2E9),
    inverseOnSurface = Color(0xFF303035),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

/** 主题感知的链接色：深色下用更亮的蓝，保证黑底可读。 */
val LinkColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9DB8F5) else Color(0xFF3D6FD9)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable
fun YunqueTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
