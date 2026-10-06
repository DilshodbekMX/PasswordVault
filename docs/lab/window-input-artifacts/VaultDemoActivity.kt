package com.example.pvlab.window

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** Secrets typed on the unlock / edit screens live here: survives rotation, never saved. */
class VaultDemoViewModel : ViewModel() {
    val username = TextFieldState()
    val title = TextFieldState()
    val masterPassword = TextFieldState()

    override fun onCleared() {
        masterPassword.clearText()
    }
}

/**
 * Exercises only the consolidated API in VaultSecurity.kt. Test tags are exposed as resource ids
 * purely so the lab scripts can drive it (not needed in the real app).
 */
class VaultDemoActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyVaultActivityPolicy()
        setContent {
            MaterialTheme {
                NoPersonalizedLearning {
                    VaultWindow { VaultDemoScreen(onFinish = { finish() }) }
                }
            }
        }
        Log.i(TAG, "VaultDemoActivity created; window flags=0x${Integer.toHexString(window.attributes.flags)}")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.i(TAG, "touch DOWN flags=0x${Integer.toHexString(ev.flags)} " +
                "WINDOW_IS_OBSCURED=${ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0}")
        }
        return super.dispatchTouchEvent(ev)
    }
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VaultDemoScreen(onFinish: () -> Unit) {
    val vm: VaultDemoViewModel = viewModel()
    var show by remember { mutableStateOf("none") }
    var clicks by remember { mutableIntStateOf(0) }
    Scaffold(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { padding ->
        Column(
            Modifier.padding(padding).imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("VAULT DEMO (consolidated VaultSecurity.kt)")
            Button(
                onClick = { clicks++; Log.i(TAG, "TARGET CLICKED count=$clicks") },
                modifier = Modifier.fillMaxWidth().testTag("btn_target"),
            ) { Text("TAP TARGET clicks=$clicks") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { show = "dialog" }, Modifier.testTag("btn_dialog")) { Text("Dialog") }
                OutlinedButton(onClick = { show = "sheet" }, Modifier.testTag("btn_sheet")) { Text("Sheet") }
                OutlinedButton(onClick = onFinish, Modifier.testTag("btn_finish")) { Text("Finish") }
            }
            OutlinedTextField(
                state = vm.username,
                label = { Text("Username") },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth().testTag("f_a").vaultTextField(),
            )
            OutlinedTextField(
                state = vm.title,
                label = { Text("Title") },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth().testTag("f_b").vaultTextField(),
            )
            OutlinedSecureTextField(
                state = vm.masterPassword,
                label = { Text("Master password") },
                modifier = Modifier.fillMaxWidth().testTag("f_e").vaultTextField(),
            )
        }
    }
    when (show) {
        "dialog" -> Dialog(onDismissRequest = { show = "none" }) {
            VaultWindow {
                val user = remember { TextFieldState() }
                val pass = remember { TextFieldState() }
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.padding(16.dp).semantics { testTagsAsResourceId = true },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("DIALOG SECRET 1234")
                        OutlinedTextField(
                            state = user,
                            label = { Text("Dialog username") },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.testTag("d_user").vaultTextField(),
                        )
                        OutlinedSecureTextField(
                            state = pass,
                            label = { Text("Dialog password") },
                            modifier = Modifier.testTag("d_pass").vaultTextField(),
                        )
                        Button(onClick = { show = "none" }, Modifier.testTag("d_close")) { Text("Close dialog") }
                    }
                }
            }
        }
        "sheet" -> ModalBottomSheet(onDismissRequest = { show = "none" }) {
            VaultWindow {
                val field = remember { TextFieldState() }
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SHEET SECRET 1234")
                        OutlinedTextField(
                            state = field,
                            label = { Text("Sheet field") },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.testTag("s_field").vaultTextField(),
                        )
                        Button(onClick = { show = "none" }, Modifier.testTag("s_close")) { Text("Close sheet") }
                    }
                }
            }
        }
    }
}
