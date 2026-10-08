package io.github.dilshodbekmx.passwordvault.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.dilshodbekmx.passwordvault.AppGraph
import java.io.File

/**
 * The activity hook that registers the file picker's launchers (`ACTION_CREATE_DOCUMENT`,
 * `ACTION_OPEN_DOCUMENT`) in `onCreate`, so that a result reaches a new, locked process and is
 * recorded first (ux.md §4 item 4; architecture.md §3.6).
 *
 * Every `MainActivity.onCreate` registers both, always, under fixed keys ([CREATE_KEY], [OPEN_KEY])
 * on the activity's `ActivityResultRegistry`. When the process died while a picker was open, the
 * new process recreates the activity from its saved state, which holds the request code of the
 * launch; the registration under the same key takes that code again, and the result is delivered to
 * it after `onStart`, before the lock has decided anything. The callbacks hand it to the process's
 * [FileTaskStore], which keeps and records it and hands it to the task's screen after the unlock.
 * The launchers are attached to the store until the activity is destroyed; a recreated activity
 * attaches its own.
 */
object DocumentLaunchers {
    /** The registry key of "Save as"; it must never change, or a result in a new process is lost. */
    const val CREATE_KEY = "io.github.dilshodbekmx.passwordvault.files.create"

    /** The registry key of the picker that opens a document; it must never change. */
    const val OPEN_KEY = "io.github.dilshodbekmx.passwordvault.files.open"

    fun onCreate(activity: ComponentActivity, graph: AppGraph) {
        val store = FileTaskStore.of(graph)
        val registry = activity.activityResultRegistry
        val create = registry.register(CREATE_KEY, activity, CreateDocumentContract) { uri ->
            store.returned(FileTaskStore.Action.CREATE, uri?.toString())
        }
        val open = registry.register(OPEN_KEY, activity, OpenDocumentContract) { uri ->
            store.returned(FileTaskStore.Action.OPEN, uri?.toString())
        }
        val launcher = ActivityLauncher(activity, create, open)
        store.attach(launcher)
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = store.detach(launcher)
            },
        )
    }

    /** The system screens, opened from [activity]. */
    private class ActivityLauncher(
        private val activity: Activity,
        private val create: ActivityResultLauncher<CreateRequest>,
        private val open: ActivityResultLauncher<List<String>>,
    ) : PickerLauncher {
        override fun create(name: String, mime: String) = create.launch(CreateRequest(name, mime))

        override fun open(mimeTypes: List<String>) = open.launch(mimeTypes)

        override fun share(file: File, title: CharSequence) = activity.startActivity(BackupSharing.chooser(activity, file, title))
    }
}

/** What "Save as" is opened with: the suggested name and the MIME type that matches its extension. */
class CreateRequest(val name: String, val mime: String)

/**
 * "Save as": `ACTION_CREATE_DOCUMENT` for an openable document of the request's type with the
 * suggested name (formats.md §2 rules 2 and 4). The result is the new document, or null when the
 * picker was closed.
 */
object CreateDocumentContract : ActivityResultContract<CreateRequest, Uri?>() {
    override fun createIntent(context: Context, input: CreateRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * `ACTION_OPEN_DOCUMENT` for one openable document of the given types: one type as the intent's
 * type, several as `EXTRA_MIME_TYPES` under the type that matches everything. The result is the
 * document, or null when the picker was closed.
 */
object OpenDocumentContract : ActivityResultContract<List<String>, Uri?>() {
    override fun createIntent(context: Context, input: List<String>): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        if (input.size == 1) {
            intent.type = input[0]
        } else {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, input.toTypedArray())
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
