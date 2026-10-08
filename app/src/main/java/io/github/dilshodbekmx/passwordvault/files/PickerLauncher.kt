package io.github.dilshodbekmx.passwordvault.files

import java.io.File

/**
 * The activity's side of the file picker and the share sheet: [DocumentLaunchers] makes one in each
 * `MainActivity.onCreate` and attaches it to the [FileTaskStore] until the activity is destroyed.
 * Every call is made on the main thread, and throws [android.content.ActivityNotFoundException] when
 * no app on the phone shows that screen.
 */
interface PickerLauncher {
    /** Opens `ACTION_CREATE_DOCUMENT` ("Save as") with the suggested [name] and its [mime] type. */
    fun create(name: String, mime: String)

    /** Opens `ACTION_OPEN_DOCUMENT` for documents of [mimeTypes] (`*` wildcards allowed). */
    fun open(mimeTypes: List<String>)

    /** Opens the share sheet for [file], a copy under the `FileProvider`'s `cache/shared/`, titled [title]. */
    fun share(file: File, title: CharSequence)
}
