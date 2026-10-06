package io.github.dilshodbekmx.passwordvault

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.dilshodbekmx.passwordvault.ui.PlaceholderScreen
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Release builds keep the app out of screenshots, screen recordings and the recents
        // preview. Debug builds allow capture so that screens can be checked on an emulator.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            PasswordVaultTheme {
                PlaceholderScreen()
            }
        }
    }
}
