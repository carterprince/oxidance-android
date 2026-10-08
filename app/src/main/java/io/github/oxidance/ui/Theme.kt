package io.github.oxidance.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The app icon's rusted-iron oranges, used where the system offers no dynamic colors.
private val Rust = Color(0xFFC8622E)
private val RustLight = Color(0xFFEB9A62)
private val RustDark = Color(0xFF7E2F12)

private val Light = lightColorScheme(primary = Rust, onPrimary = Color.White, primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF351000), secondary = RustDark, tertiary = Color(0xFF6B5E2F))
private val Dark = darkColorScheme(primary = RustLight, onPrimary = Color(0xFF552000), primaryContainer = RustDark,
    onPrimaryContainer = Color(0xFFFFDBCB), secondary = Color(0xFFE7BDAA), tertiary = Color(0xFFD7C68D),
    background = Color(0xFF15110F), surface = Color(0xFF15110F))

@Composable
fun OxidanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
