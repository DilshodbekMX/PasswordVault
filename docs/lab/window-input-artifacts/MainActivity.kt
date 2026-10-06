package com.example.pvlab.window

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

class LabViewModel : ViewModel() {
    /** (c) a TextFieldState held by a ViewModel: survives configuration changes, never saved. */
    val vmState = TextFieldState()

    init {
        Log.i(TAG, "LabViewModel created")
    }

    override fun onCleared() {
        Log.i(TAG, "LabViewModel cleared")
    }
}

class MainActivity : FragmentActivity() {
    private lateinit var cfg: LabConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        cfg = readConfig(intent)
        Log.i(TAG, "onCreate sdk=${Build.VERSION.SDK_INT} restored=${savedInstanceState != null} cfg=$cfg")
        if (cfg.secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (cfg.recentsOff && Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(false)
            Log.i(TAG, "setRecentsScreenshotEnabled(false)")
        }
        hardenWindow(window, cfg, "activity")
        setContent {
            MaterialTheme {
                if (cfg.imeFlag) LabNoPersonalizedLearning { LabApp(cfg, this) } else LabApp(cfg, this)
            }
        }
        if (cfg.filter == "composeview") {
            val composeView = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            composeView.filterTouchesWhenObscured = true
            Log.i(TAG, "ComposeView ${composeView.javaClass.name} filterTouchesWhenObscured=true")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.i(
                TAG,
                "touch DOWN at ${ev.rawX.toInt()},${ev.rawY.toInt()} flags=0x${Integer.toHexString(ev.flags)} " +
                    "WINDOW_IS_OBSCURED=${ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0} " +
                    "WINDOW_IS_PARTIALLY_OBSCURED=${ev.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0}",
            )
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val parcel = Parcel.obtain()
        val size = try {
            outState.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
        Log.i(TAG, "onSaveInstanceState parcelSize=$size keys=${outState.keySet()}")
        dumpValue("outState", outState)
    }

    override fun onStart() { super.onStart(); Log.i(TAG, "onStart") }
    override fun onResume() { super.onResume(); Log.i(TAG, "onResume") }
    override fun onPause() { super.onPause(); Log.i(TAG, "onPause") }
    override fun onStop() { super.onStop(); Log.i(TAG, "onStop") }
    override fun onDestroy() {
        Log.i(TAG, "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }

    private fun readConfig(i: Intent) = LabConfig(
        secure = i.getBooleanExtra("secure", false),
        recentsOff = i.getBooleanExtra("recentsOff", false),
        afDecor = i.getStringExtra("afDecor") ?: "none",
        afCtNone = i.getBooleanExtra("afCtNone", false),
        afNoMenu = i.getBooleanExtra("afNoMenu", false),
        afCancel = i.getBooleanExtra("afCancel", false),
        imeFlag = i.getBooleanExtra("imeFlag", false),
        filter = i.getStringExtra("filter") ?: "none",
        hideOverlays = i.getBooleanExtra("hideOverlays", false),
        a11y = i.getBooleanExtra("a11y", false),
        a11yDecorOnly = i.getBooleanExtra("a11yDecorOnly", false),
        a11ySemOnly = i.getBooleanExtra("a11ySemOnly", false),
        show = i.getStringExtra("show") ?: "none",
    )
}

/** Recursively logs every leaf of a saved-state Bundle so typed text can be spotted. */
@Suppress("DEPRECATION")
fun dumpValue(path: String, v: Any?) {
    when (v) {
        is Bundle -> {
            if (v.isEmpty) Log.i(TAG, "$path = Bundle{}")
            for (k in v.keySet()) dumpValue("$path/$k", v.get(k))
        }
        is List<*> -> {
            if (v.isEmpty()) Log.i(TAG, "$path = ${v.javaClass.simpleName}[]")
            v.forEachIndexed { i, e -> dumpValue("$path[$i]", e) }
        }
        is Array<*> -> v.forEachIndexed { i, e -> dumpValue("$path[$i]", e) }
        is SparseArray<*> -> for (i in 0 until v.size()) dumpValue("$path{${v.keyAt(i)}}", v.valueAt(i))
        is IntArray -> Log.i(TAG, "$path = IntArray${v.contentToString()}")
        is LongArray -> Log.i(TAG, "$path = LongArray${v.contentToString()}")
        else -> Log.i(TAG, "$path = ${v?.javaClass?.simpleName}: ${v.toString().take(300)}")
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabApp(cfg: LabConfig, activity: FragmentActivity) {
    val context = LocalContext.current
    val autofillManager = LocalAutofillManager.current
    val vm: LabViewModel = viewModel()
    // Experiment 5 state holders
    val fieldA = rememberTextFieldState()          // (a) saved through rememberSaveable
    val fieldB = remember { TextFieldState() }     // (b) plain remember
    val fieldD = remember { TextFieldState() }
    val fieldE = rememberTextFieldState()          // secure field backed by rememberTextFieldState
    val fieldF = remember { TextFieldState() }

    var screen by rememberSaveable { mutableStateOf("form") }
    var show by remember { mutableStateOf(cfg.show) }
    var clicks by remember { mutableIntStateOf(0) }
    val snackbarHost = remember { SnackbarHostState() }
    val tooltipState = rememberTooltipState(isPersistent = true)

    LaunchedEffect(Unit) {
        Log.i(TAG, "composed: A='${fieldA.text}' B='${fieldB.text}' C(vm)='${vm.vmState.text}' E='${fieldE.text}'")
    }
    LaunchedEffect(show) {
        when (show) {
            "snackbar" -> snackbarHost.showSnackbar("SNACKBAR SECRET 1234", duration = SnackbarDuration.Indefinite)
            "tooltip" -> tooltipState.show()
            "toast" -> Toast.makeText(context, "TOAST SECRET 1234", Toast.LENGTH_LONG).show()
        }
    }

    fun leave(next: () -> Unit) {
        if (cfg.afCancel) {
            Log.i(TAG, "autofillManager.cancel() (manager=$autofillManager)")
            autofillManager?.cancel()
        }
        next()
    }
    BackHandler(enabled = cfg.afCancel && screen == "form") { leave { activity.finish() } }
    BackHandler(enabled = screen != "form") { screen = "form" }

    fun Modifier.field(tag: String): Modifier {
        var m = this.fillMaxWidth().testTag(tag)
        if (cfg.afCtNone) m = m.semantics { contentDataType = ContentDataType.None }
        if (cfg.a11y || cfg.a11ySemOnly) m = m.semantics { isSensitiveData = true }
        return m
    }

    WindowRoot(cfg, "activity") {
        Scaffold(
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            snackbarHost = { SnackbarHost(snackbarHost) },
            containerColor = Color(0xFFFFF3C4),
        ) { padding ->
            if (screen != "form") {
                Column(Modifier.padding(padding).padding(16.dp)) {
                    Text("NEXT SCREEN (fields left the composition)")
                    Button(onClick = { screen = "form" }, Modifier.testTag("btn_back")) { Text("Back to form") }
                }
            } else Column(
                Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("WINDOW LAB sdk=${Build.VERSION.SDK_INT} secure=${cfg.secure} recentsOff=${cfg.recentsOff}", style = MaterialTheme.typography.labelSmall)
                Text("af: decor=${cfg.afDecor} ctNone=${cfg.afCtNone} noMenu=${cfg.afNoMenu} cancel=${cfg.afCancel} | ime=${cfg.imeFlag} filter=${cfg.filter} hide=${cfg.hideOverlays} a11y=${cfg.a11y}", style = MaterialTheme.typography.labelSmall)
                Button(
                    onClick = { clicks++; Log.i(TAG, "TARGET CLICKED count=$clicks") },
                    modifier = Modifier.fillMaxWidth().testTag("btn_target"),
                ) { Text("TAP TARGET clicks=$clicks") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { show = "alert" }, Modifier.testTag("btn_alert")) { Text("Alert") }
                    OutlinedButton(onClick = { show = "dialog" }, Modifier.testTag("btn_dialog")) { Text("Dialog") }
                    OutlinedButton(onClick = { show = "sheet" }, Modifier.testTag("btn_sheet")) { Text("Sheet") }
                    Box {
                        OutlinedButton(onClick = { show = "menu" }, Modifier.testTag("btn_menu")) { Text("Menu") }
                        DropdownMenu(expanded = show == "menu", onDismissRequest = { show = "none" }) {
                            DropdownMenuItem(text = { Text("MENU SECRET 1") }, onClick = { show = "none" })
                            DropdownMenuItem(text = { Text("MENU SECRET 2") }, onClick = { show = "none" })
                        }
                    }
                    OutlinedButton(onClick = { show = "snackbar" }, Modifier.testTag("btn_snack")) { Text("Snack") }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text("TOOLTIP SECRET 1234") } },
                        state = tooltipState,
                    ) {
                        OutlinedButton(onClick = { show = "tooltip" }, Modifier.testTag("btn_tooltip")) { Text("Tooltip") }
                    }
                    OutlinedButton(onClick = { show = "toast" }, Modifier.testTag("btn_toast")) { Text("Toast") }
                    OutlinedButton(onClick = { leave { screen = "next" } }, Modifier.testTag("btn_next")) { Text("Next") }
                    OutlinedButton(onClick = { leave { activity.finish() } }, Modifier.testTag("btn_finish")) { Text("Finish") }
                }
                OutlinedTextField(
                    state = fieldA,
                    label = { Text("A rememberTextFieldState, default KeyboardOptions") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.field("f_a"),
                )
                OutlinedTextField(
                    state = fieldB,
                    label = { Text("B remember{TextFieldState()}, default KeyboardOptions") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.field("f_b"),
                )
                OutlinedTextField(
                    state = vm.vmState,
                    label = { Text("C ViewModel TextFieldState") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.field("f_c"),
                )
                OutlinedTextField(
                    state = fieldD,
                    label = { Text("D OutlinedTextField + Password KeyboardOptions") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.field("f_d"),
                )
                OutlinedSecureTextField(
                    state = fieldE,
                    label = { Text("E OutlinedSecureTextField default (rememberTextFieldState)") },
                    modifier = Modifier.field("f_e"),
                )
                SecureTextField(
                    state = fieldF,
                    label = { Text("F SecureTextField Hidden") },
                    textObfuscationMode = TextObfuscationMode.Hidden,
                    modifier = Modifier.field("f_f"),
                )
            }
        }

        when (show) {
            "alert" -> AlertDialog(
                onDismissRequest = { show = "none" },
                confirmButton = { TextButton(onClick = { show = "none" }) { Text("OK") } },
                title = { Text("ALERT SECRET 1234") },
                text = { WindowRoot(cfg, "alert") { Text("AlertDialog body text: ALERT SECRET 1234") } },
            )
            "dialog" -> Dialog(onDismissRequest = { show = "none" }) {
                WindowRoot(cfg, "dialog") {
                    val dUser = remember { TextFieldState() }
                    val dPass = remember { TextFieldState() }
                    Surface(shape = MaterialTheme.shapes.large, color = Color(0xFFCCE5FF)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DIALOG SECRET 1234 (Compose Dialog window)")
                            OutlinedTextField(
                                state = dUser,
                                label = { Text("Dialog username (default)") },
                                lineLimits = TextFieldLineLimits.SingleLine,
                                modifier = Modifier.field("d_user"),
                            )
                            OutlinedSecureTextField(
                                state = dPass,
                                label = { Text("Dialog password (secure)") },
                                modifier = Modifier.field("d_pass"),
                            )
                            Button(onClick = { show = "none" }, Modifier.testTag("d_close")) { Text("Close dialog") }
                        }
                    }
                }
            }
            "sheet" -> ModalBottomSheet(onDismissRequest = { show = "none" }, containerColor = Color(0xFFD7F5D0)) {
                WindowRoot(cfg, "sheet") {
                    val sField = remember { TextFieldState() }
                    Column(Modifier.padding(16.dp).background(Color(0xFFD7F5D0))) {
                        Text("SHEET SECRET 1234 (ModalBottomSheet window)")
                        OutlinedTextField(
                            state = sField,
                            label = { Text("Sheet field") },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.field("s_field"),
                        )
                        Button(onClick = { show = "none" }, Modifier.testTag("s_close")) { Text("Close sheet") }
                    }
                }
            }
        }
    }
}
