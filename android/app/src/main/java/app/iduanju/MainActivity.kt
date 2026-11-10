package app.iduanju

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.iduanju.ui.RootScreen
import app.iduanju.ui.theme.IdjTheme
import app.iduanju.ui.theme.IdjThemeColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IdjTheme {
                Surface(Modifier.fillMaxSize(), color = IdjThemeColors.bg) {
                    RootScreen(initialRoute = intent?.getStringExtra("route"))
                }
            }
        }
    }
}
