package com.example.pvlab.window

// Compiled WITHOUT any @OptIn on purpose: if one of these APIs were experimental, the build
// would fail (or warn) here. Used only to settle "which opt-in does this need?".

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics

fun probeModifiers(): Modifier =
    Modifier
        .semantics { contentDataType = ContentDataType.None }
        .semantics { isSensitiveData = true }
        .filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }

val probeInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(request)
}
