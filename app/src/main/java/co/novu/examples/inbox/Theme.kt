package co.novu.examples.inbox

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    background = Color(0xFFF4F5F7),
    surface = Color.White,
)

@Composable
fun NovuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
