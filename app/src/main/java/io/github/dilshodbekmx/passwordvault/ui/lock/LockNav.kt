package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.shell.RoutePlaceholder
import io.github.dilshodbekmx.passwordvault.ui.shell.openAction
import io.github.dilshodbekmx.passwordvault.ui.shell.placeholderAction
import kotlinx.coroutines.launch

/**
 * The lock's one route, [Route.Locked], whose screen `LockUiState` decides (lock.md §13.1, §13.2):
 * the master-password screen (build plan 3.4), then every lock screen (4.2). A placeholder until
 * then, which shows the state and unlocks through `submitMasterPassword`.
 */
fun EntryProviderScope<Route>.lockNav(graph: AppGraph, nav: Navigator) {
    entry<Route.Locked> { route -> LockedPlaceholder(graph, nav, route) }
}

/**
 * The notices after unlock (lock.md §13.4; ux.md §5.9), one at a time over the unlocked screens: the
 * shell calls this while the app is unlocked (`UnlockedScaffold`), and the lock screens package
 * (build plan 4.2) fills it. The placeholder shows the first notice's kind and its answers; "Choose
 * a PIN" also opens the PIN setup (lock.md F1 step 6).
 */
@Composable
fun LockNotices(graph: AppGraph, nav: Navigator) {
    val state by graph.lock.state.collectAsStateWithLifecycle()
    val notice = (state as? LockUiState.Unlocked)?.notices?.firstOrNull() ?: return
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("pv.notice"),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shell_placeholder_notice, notice.toString()), style = MaterialTheme.typography.titleMedium)
                for (answer in answersOf(notice)) {
                    OutlinedButton(
                        onClick = {
                            graph.lock.send(LockIntent.NoticeAnswered(notice.id, answer))
                            if (answer == NoticeAnswer.CHOOSE_PIN) nav.go(Route.CreatePin)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("pv.answer.${answer.name}"),
                    ) {
                        Text(stringResource(R.string.shell_placeholder_answer, answer.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedPlaceholder(graph: AppGraph, nav: Navigator, route: Route) {
    val state by graph.lock.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RoutePlaceholder(
        route,
        nav,
        listOf(
            placeholderAction("unlock", R.string.shell_placeholder_unlock) {
                scope.launch { graph.lock.submitMasterPassword(CharArray(0), MpPurpose.UNLOCK) }
            },
            openAction(nav, Route.Restore),
        ),
        detail = state.toString(),
    )
}

/** The answers each notice offers (`Notice`'s KDoc). */
private fun answersOf(notice: Notice): List<NoticeAnswer> = when (notice) {
    is Notice.OlderCopy -> listOf(NoticeAnswer.BACK_UP_NOW, NoticeAnswer.OK)
    is Notice.OlderThanLastTime -> listOf(NoticeAnswer.OK)
    is Notice.Pending -> listOf(NoticeAnswer.CANCEL_IT, NoticeAnswer.OK)
    is Notice.PendingReady -> listOf(NoticeAnswer.FINISH_NOW, NoticeAnswer.LATER, NoticeAnswer.CANCEL_IT)
    is Notice.PendingCancelled -> listOf(NoticeAnswer.OK)
    is Notice.Replaced -> listOf(NoticeAnswer.OK, NoticeAnswer.IT_WASNT_ME)
    is Notice.WrongTries -> buildList {
        add(NoticeAnswer.CHANGE_PIN)
        add(NoticeAnswer.OK)
        if (notice.pinsBeforeOff != null) add(NoticeAnswer.TYPE_MASTER_PASSWORD)
        if (notice.offerNewMasterPassword) add(NoticeAnswer.NEW_MASTER_PASSWORD)
    }
    is Notice.ChoosePin -> listOf(NoticeAnswer.CHOOSE_PIN, NoticeAnswer.LATER)
    is Notice.FingerprintAgain -> listOf(NoticeAnswer.TURN_ON_FINGERPRINT, NoticeAnswer.LATER)
    is Notice.StorageUnreadable -> listOf(NoticeAnswer.SET_UP_AGAIN, NoticeAnswer.BACK_UP_NOW, NoticeAnswer.LATER)
    is Notice.BackupNow -> listOf(NoticeAnswer.BACK_UP_NOW, NoticeAnswer.LATER)
}
