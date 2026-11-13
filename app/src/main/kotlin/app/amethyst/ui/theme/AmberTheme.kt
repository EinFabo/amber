package app.amethyst.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amethyst.R
import app.amethyst.core.privacy.TrustLevel

/**
 * Neutral greys and one colour. Amber marks what can be pressed or is happening; everything
 * else is text on a flat ground - near-black in the dark theme, warm paper in the light one.
 * No dynamic colour: a system accent would replace exactly the colour the app is named after.
 *
 * One set of colours per theme; [AmberPalette] hands out the one in effect. The bubble and its
 * quick menu, drawn over other apps, have colours of their own and follow neither.
 */
@Immutable
data class AmberColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val outline: Color,
    /** Amber as text, icon and button: it reads on every ground, and [background] reads on it. */
    val primary: Color,
    /** Amber at low strength, for a selected row. */
    val deep: Color,
    val text: Color,
    val muted: Color,
    val error: Color,
    val trustOnDevice: Color,
    val trustCloud: Color,
) {
    companion object {
        val Dark = AmberColors(
            dark = true,
            background = Color(0xFF0A0A0A),
            surface = Color(0xFF161616),
            elevated = Color(0xFF1F1F1F),
            outline = Color(0xFF262626),
            primary = Color(0xFFFFB020),
            deep = Color(0x33FFB020),
            text = Color(0xFFEDEDED),
            muted = Color(0xFF8A8A8A),
            error = Color(0xFFF07167),
            trustOnDevice = Color(0xFF6CCB8F),
            trustCloud = Color(0xFF8FA3BF),
        )

        /**
         * On paper the bright amber would not read as text, so the light theme's amber is the
         * same hue burnt darker; the bright one stays as the tint behind it ([deep]). Every
         * text colour holds WCAG AA on every ground (AmberColorsTest).
         */
        val Light = AmberColors(
            dark = false,
            background = Color(0xFFF6F3EE),
            surface = Color(0xFFFFFFFF),
            elevated = Color(0xFFEDE9E1),
            outline = Color(0xFFD9D3C8),
            primary = Color(0xFF9A4D00),
            deep = Color(0x33FFB020),
            text = Color(0xFF1C1A17),
            muted = Color(0xFF625C53),
            error = Color(0xFFB3261E),
            trustOnDevice = Color(0xFF1A7340),
            trustCloud = Color(0xFF4B617C),
        )

        fun of(dark: Boolean): AmberColors = if (dark) Dark else Light
    }
}

/** The colours in effect; [AmberTheme] sets them. Dark where no theme is set, as Amber always was. */
val LocalAmberColors = staticCompositionLocalOf { AmberColors.Dark }

/**
 * The colours of the theme in effect, by the names the screens use. Read in composition; a draw
 * lambda takes the colour into a value first.
 */
object AmberPalette {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.surface
    val Elevated: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.elevated
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.outline

    /** Amber itself. Carries [Background] as its text, never [Text]. */
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.primary

    /** Amber at low strength, for a selected row. */
    val Deep: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.deep

    /** Secondary emphasis: the same amber, so there is only ever one colour. */
    val Accent: Color @Composable @ReadOnlyComposable get() = Primary

    val Text: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.muted
    val Error: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.error

    val TrustOnDevice: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.trustOnDevice
    val TrustEnclave: Color @Composable @ReadOnlyComposable get() = Primary
    val TrustCloud: Color @Composable @ReadOnlyComposable get() = LocalAmberColors.current.trustCloud

    /** The bubble's own amber, the same in both themes: for a picture of the bubble, not for text. */
    val Flame = Color(0xFFFFB020)

    /** What dims the page behind a sheet, in both themes. */
    val Scrim = Color(0x99000000)
}

object AmberShapes {
    val Card = RoundedCornerShape(16.dp)
    val Inner = RoundedCornerShape(12.dp)
    val Button = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

/** Colour and wording for the badge shown on every transcript. */
@Composable
@ReadOnlyComposable
fun TrustLevel.badgeColor(): Color = when (this) {
    TrustLevel.ON_DEVICE -> AmberPalette.TrustOnDevice
    TrustLevel.ENCLAVE_VERIFIED -> AmberPalette.TrustEnclave
    TrustLevel.CLOUD -> AmberPalette.TrustCloud
}

@Composable
fun TrustLevel.badgeLabel(): String = stringResource(
    when (this) {
        TrustLevel.ON_DEVICE -> R.string.trust_on_device
        TrustLevel.ENCLAVE_VERIFIED -> R.string.trust_enclave_verified
        TrustLevel.CLOUD -> R.string.trust_cloud
    },
)

/** Where an engine of this level sends your voice - for settings, before anything is sent. */
@Composable
fun TrustLevel.placeExplanation(): String = stringResource(
    when (this) {
        TrustLevel.ON_DEVICE -> R.string.trust_place_on_device
        TrustLevel.ENCLAVE_VERIFIED -> R.string.trust_place_enclave
        TrustLevel.CLOUD -> R.string.trust_place_cloud
    },
)

@Composable
fun TrustLevel.badgeExplanation(): String = stringResource(
    when (this) {
        TrustLevel.ON_DEVICE -> R.string.trust_went_on_device
        TrustLevel.ENCLAVE_VERIFIED -> R.string.trust_went_enclave
        TrustLevel.CLOUD -> R.string.trust_went_cloud
    },
)

private fun colorScheme(colors: AmberColors): ColorScheme {
    val base = if (colors.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.primary,
        onPrimary = colors.background,
        primaryContainer = colors.deep,
        onPrimaryContainer = colors.text,
        secondary = colors.primary,
        onSecondary = colors.background,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.elevated,
        onSurfaceVariant = colors.muted,
        surfaceContainerHigh = colors.elevated,
        outline = colors.outline,
        error = colors.error,
        onError = colors.background,
    )
}

/**
 * The system sans throughout. Hierarchy comes from size and weight - a clear step from title
 * to body - not from letter-spaced capitals or a second typeface.
 */
private val AmberTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val AmberMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * Amber's colours, type and shapes. [dark] is the user's choice against the phone's theme
 * ([app.amethyst.core.settings.ThemeMode.dark]); what is drawn over another app passes true.
 */
@Composable
fun AmberTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = AmberColors.of(dark)
    CompositionLocalProvider(LocalAmberColors provides colors) {
        MaterialTheme(
            colorScheme = remember(colors) { colorScheme(colors) },
            typography = AmberTypography,
            shapes = AmberMaterialShapes,
            content = content,
        )
    }
}
