package app.gcaltolotus.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Палитра по умолчанию: серо-синяя (navy) с холодными нейтральными серыми поверхностями.
 * Используется, когда платформа не даёт динамических цветов (Android 10–11, iOS).
 *
 * Семантика в UI: secondary*Container — информационные плашки, tertiary — акцент «Вопрос» и
 * предупреждения (приглушённый янтарный, чтобы вопрос выделялся на синем фоне), error* — ошибки,
 * primaryContainer — успех. Все пары «цвет / on-цвет» проверены на контраст WCAG AA (≥ 4.5:1,
 * фактически ≥ 6.4:1 в светлой и ≥ 5.5:1 в тёмной схеме); значения тех же цветов для окна до
 * запуска Compose лежат в colors.xml ресурсов androidApp (values, values-night).
 */

/** Светлая схема по умолчанию. */
val GcalLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2F4166),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2F7),
    onPrimaryContainer = Color(0xFF16213A),
    inversePrimary = Color(0xFFB3C5EC),
    secondary = Color(0xFF545E74),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3EF),
    onSecondaryContainer = Color(0xFF1A2131),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E2B4),
    onTertiaryContainer = Color(0xFF2B1F00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE0E3EA),
    onSurfaceVariant = Color(0xFF454952),
    surfaceTint = Color(0xFF2F4166),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFEFF0F4),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC4C7CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F8FA),
    surfaceDim = Color(0xFFD8DADF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F6),
    surfaceContainer = Color(0xFFEBEDF1),
    surfaceContainerHigh = Color(0xFFE5E8EC),
    surfaceContainerHighest = Color(0xFFDFE2E7),
)

/** Тёмная схема по умолчанию: светлые сине-стальные акценты на почти чёрном холодном сером. */
val GcalDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFB3C5EC),
    onPrimary = Color(0xFF1A2B4B),
    primaryContainer = Color(0xFF33466B),
    onPrimaryContainer = Color(0xFFD9E2F7),
    inversePrimary = Color(0xFF2F4166),
    secondary = Color(0xFFBDC6DA),
    onSecondary = Color(0xFF273041),
    secondaryContainer = Color(0xFF3D4658),
    onSecondaryContainer = Color(0xFFDDE3EF),
    tertiary = Color(0xFFE8C26C),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFE09A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E3E8),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E3E8),
    surfaceVariant = Color(0xFF43474F),
    onSurfaceVariant = Color(0xFFC4C7CF),
    surfaceTint = Color(0xFFB3C5EC),
    inverseSurface = Color(0xFFE2E3E8),
    inverseOnSurface = Color(0xFF2E3036),
    outline = Color(0xFF8E919A),
    outlineVariant = Color(0xFF43474F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/**
 * Тема приложения. Светлая или тёмная — по настройке системы.
 *
 * @param dynamicColorScheme схема от платформы (Material You на Android 12+), уже выбранная под
 *   текущий светлый или тёмный режим; `null` — палитра по умолчанию [GcalLightColors]/[GcalDarkColors].
 */
@Composable
fun GcalTheme(dynamicColorScheme: ColorScheme? = null, content: @Composable () -> Unit) {
    val colors = dynamicColorScheme ?: if (isSystemInDarkTheme()) GcalDarkColors else GcalLightColors
    MaterialTheme(colorScheme = colors, content = content)
}
