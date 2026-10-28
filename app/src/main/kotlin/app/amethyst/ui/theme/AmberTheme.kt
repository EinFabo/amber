package app.amethyst.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amethyst.core.privacy.TrustLevel

/**
 * Neutral greys and one colour. Amber marks what can be pressed or is happening; everything
 * else is text on a flat black. Dark-only and no dynamic colour - a system accent would
 * replace exactly the colour the app is named after.
 */
object AmberPalette {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF161616)
    val Elevated = Color(0xFF1F1F1F)
    val Outline = Color(0xFF262626)

    /** Amber itself. Carries dark text, never white. */
    val Primary = Color(0xFFFFB020)

    /** Amber at low strength, for a selected row. */
    val Deep = Color(0x33FFB020)

    /** Secondary emphasis: the same amber, so there is only ever one colour. */
    val Accent = Primary

    val Text = Color(0xFFEDEDED)
    val Muted = Color(0xFF8A8A8A)
    val Error = Color(0xFFF07167)

    val TrustOnDevice = Color(0xFF6CCB8F)
    val TrustEnclave = Primary
    val TrustCloud = Color(0xFF8FA3BF)
}

object AmberShapes {
    val Card = RoundedCornerShape(16.dp)
    val Inner = RoundedCornerShape(12.dp)
    val Button = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

/** Colour and wording for the badge shown on every transcript. */
fun TrustLevel.badgeColor(): Color = when (this) {
    TrustLevel.ON_DEVICE -> AmberPalette.TrustOnDevice
    TrustLevel.ENCLAVE_VERIFIED -> AmberPalette.TrustEnclave
    TrustLevel.CLOUD -> AmberPalette.TrustCloud
}

fun TrustLevel.badgeLabel(): String = when (this) {
    TrustLevel.ON_DEVICE -> "Auf dem Gerät"
    TrustLevel.ENCLAVE_VERIFIED -> "Enclave geprüft"
    TrustLevel.CLOUD -> "Cloud"
}

fun TrustLevel.badgeExplanation(): String = when (this) {
    TrustLevel.ON_DEVICE -> "Die Aufnahme hat das Gerät nicht verlassen."
    TrustLevel.ENCLAVE_VERIFIED ->
        "Die Aufnahme ging in eine Enclave, deren Attestierung geprüft wurde."
    TrustLevel.CLOUD -> "Die Aufnahme ging an einen Server. Nur TLS, keine weitere Garantie."
}

private val AmberColorScheme = darkColorScheme(
    primary = AmberPalette.Primary,
    onPrimary = AmberPalette.Background,
    primaryContainer = AmberPalette.Deep,
    onPrimaryContainer = AmberPalette.Text,
    secondary = AmberPalette.Accent,
    onSecondary = AmberPalette.Background,
    background = AmberPalette.Background,
    onBackground = AmberPalette.Text,
    surface = AmberPalette.Surface,
    onSurface = AmberPalette.Text,
    surfaceVariant = AmberPalette.Elevated,
    onSurfaceVariant = AmberPalette.Muted,
    surfaceContainerHigh = AmberPalette.Elevated,
    outline = AmberPalette.Outline,
    error = AmberPalette.Error,
    onError = AmberPalette.Background,
)

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

@Composable
fun AmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmberColorScheme,
        typography = AmberTypography,
        shapes = AmberMaterialShapes,
        content = content,
    )
}
