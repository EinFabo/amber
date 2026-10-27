package app.nocturne.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.nocturne.core.privacy.TrustLevel

/**
 * Nocturne is dark-only. There is no light scheme and no dynamic colour: the identity is
 * near-black plus violet, and a system accent overriding it would dissolve exactly that.
 */
object NocturnePalette {
    val Background = Color(0xFF07060B)
    val Surface = Color(0xFF0F0D16)
    val Elevated = Color(0xFF171324)
    val Primary = Color(0xFFA855F7)
    val Accent = Color(0xFFC084FC)
    val Deep = Color(0xFF6D28D9)
    val Text = Color(0xFFECE7F5)
    val Muted = Color(0xFF9A92AD)
    val Outline = Color(0xFF3A2F52)
    val Error = Color(0xFFF87171)

    val TrustOnDevice = Color(0xFF34D399)
    val TrustEnclave = Primary
    val TrustCloud = Color(0xFFFBBF24)
}

/** Colour and wording for the badge shown on every transcript. */
fun TrustLevel.badgeColor(): Color = when (this) {
    TrustLevel.ON_DEVICE -> NocturnePalette.TrustOnDevice
    TrustLevel.ENCLAVE_VERIFIED -> NocturnePalette.TrustEnclave
    TrustLevel.CLOUD -> NocturnePalette.TrustCloud
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

private val NocturneColorScheme = darkColorScheme(
    primary = NocturnePalette.Primary,
    onPrimary = NocturnePalette.Background,
    primaryContainer = NocturnePalette.Deep,
    onPrimaryContainer = NocturnePalette.Text,
    secondary = NocturnePalette.Accent,
    onSecondary = NocturnePalette.Background,
    background = NocturnePalette.Background,
    onBackground = NocturnePalette.Text,
    surface = NocturnePalette.Surface,
    onSurface = NocturnePalette.Text,
    surfaceVariant = NocturnePalette.Elevated,
    onSurfaceVariant = NocturnePalette.Muted,
    outline = NocturnePalette.Outline,
    error = NocturnePalette.Error,
    onError = NocturnePalette.Background,
)

private val NocturneTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun NocturneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NocturneColorScheme,
        typography = NocturneTypography,
        content = content,
    )
}
