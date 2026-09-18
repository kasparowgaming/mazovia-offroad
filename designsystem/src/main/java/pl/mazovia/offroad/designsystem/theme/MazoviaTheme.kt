package pl.mazovia.offroad.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// Semantic color tokens for Mazovia Offroad
object MazoviaColors {
    // Brand
    val ForestGreen = Color(0xFF2E7D32)
    val ForestGreenDark = Color(0xFF1B5E20)
    val ForestGreenLight = Color(0xFF4CAF50)

    // Surface types - semantic tokens
    val TerrainDirt = Color(0xFF8D6E63)
    val TerrainGravel = Color(0xFFA1887F)
    val TerrainSand = Color(0xFFD7CCC8)
    val TerrainGrass = Color(0xFF66BB6A)
    val TerrainMud = Color(0xFF5D4037)
    val Asphalt = Color(0xFF546E7A)
    val AsphaltConnector = Color(0xFFFF8F00)

    // Data confidence
    val ConfidenceHigh = Color(0xFF2E7D32)
    val ConfidenceMedium = Color(0xFFF9A825)
    val ConfidenceLow = Color(0xFFE65100)
    val ConfidenceUnknown = Color(0xFF9E9E9E)

    // Status
    val Recording = Color(0xFFD32F2F)
    val RecordingPulse = Color(0xFFFF5252)
    val NavigationActive = Color(0xFF1565C0)
    val Warning = Color(0xFFFF8F00)
    val Error = Color(0xFFD32F2F)
    val Success = Color(0xFF2E7D32)

    // Route
    val RoutePrimary = Color(0xFF1565C0)
    val RouteAlternative = Color(0xFF7986CB)
    val RouteOffRoad = Color(0xFF4CAF50)
    val RouteAsphalt = Color(0xFF78909C)

    // Selection
    val Selected = Color(0xFF1565C0)
    val Unselected = Color(0xFFBDBDBD)

    // Riding v7 UI
    val RidingBackground = Color(0xFF0A0E14)
    val RidingCardBackground = Color(0xFF141A22)
    val RidingBorder = Color(0xFF2A3040)
    val StatusGpsGreen = Color(0xFF00E676)
    val StatusRecRed = Color(0xFFFF1744)
    val ManeuverIconBg = Color(0xFF1A2030)
    val TerrainRadarGreen = Color(0xFF00E676)
    val TerrainRadarYellow = Color(0xFFFFD600)
    val TerrainRadarRed = Color(0xFFFF3D00)
    val StopButtonRed = Color(0xFFD50000)
}

private val DayColorScheme = lightColorScheme(
    primary = MazoviaColors.ForestGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = MazoviaColors.TerrainDirt,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7CCC8),
    onSecondaryContainer = Color(0xFF3E2723),
    tertiary = MazoviaColors.NavigationActive,
    onTertiary = Color.White,
    error = MazoviaColors.Error,
    onError = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1B1B1B),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF424242),
    outline = Color(0xFFBDBDBD)
)

private val NightColorScheme = darkColorScheme(
    primary = MazoviaColors.ForestGreenLight,
    onPrimary = Color(0xFF1B3A1B),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFBCAAA4),
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFF4E342E),
    onSecondaryContainer = Color(0xFFD7CCC8),
    tertiary = Color(0xFF64B5F6),
    onTertiary = Color(0xFF0D47A1),
    error = Color(0xFFEF5350),
    onError = Color(0xFF1B1B1B),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF616161)
)

// High Contrast scheme for sunlight riding
private val HighContrastColorScheme = lightColorScheme(
    primary = Color(0xFF004D00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00E600),
    onPrimaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color.Black,
    error = Color.Red,
    onError = Color.White,
    outline = Color.Black
)

enum class MazoviaThemeMode {
    DAY, NIGHT, HIGH_CONTRAST, SYSTEM
}

val LocalMazoviaThemeMode = staticCompositionLocalOf { MazoviaThemeMode.SYSTEM }

@Composable
fun MazoviaOffroadTheme(
    themeMode: MazoviaThemeMode = MazoviaThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        MazoviaThemeMode.DAY -> DayColorScheme
        MazoviaThemeMode.NIGHT -> NightColorScheme
        MazoviaThemeMode.HIGH_CONTRAST -> HighContrastColorScheme
        MazoviaThemeMode.SYSTEM -> if (isSystemInDarkTheme()) NightColorScheme else DayColorScheme
    }

    CompositionLocalProvider(LocalMazoviaThemeMode provides themeMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MazoviaTypography,
            content = content
        )
    }
}
