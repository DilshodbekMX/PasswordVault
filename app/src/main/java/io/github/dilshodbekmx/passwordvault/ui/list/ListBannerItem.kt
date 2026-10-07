package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind

/** What the buttons of the list's banners do (ux.md §5.11). Made once per screen. */
internal class BannerActions(
    val cancelPending: () -> Unit,
    val finishPending: () -> Unit,
    val pendingLater: () -> Unit,
    val itWasntMe: () -> Unit,
    val replacedSeen: () -> Unit,
    val typeItNow: () -> Unit,
    val forgotIt: () -> Unit,
    val deleteExport: (uri: String) -> Unit,
    val exportDeletedMyself: (uri: String) -> Unit,
    val backUpNow: () -> Unit,
    val backupLater: () -> Unit,
    val answer: (entryId: String) -> Unit,
    val wallMs: () -> Long,
)

/**
 * One banner of the list (ux.md §5.11), with the texts of lock.md §13.4 for the lock's and of ux.md
 * §5.20, §5.11 and §5.17 for the others. Dates, times and "2 hours ago" are formatted here, in the
 * language of the screen. The main button is the last one, at the end of the row.
 */
@Composable
internal fun ListBannerItem(banner: ListBanner, actions: BannerActions) {
    val formatters = rememberFormatters()
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(ListTags.banner(banner))
    when (banner) {
        is ListBanner.PendingMasterPassword -> Banner(
            text = pluralStringResource(
                if (banner.by == Shortcut.PIN) R.plurals.list_banner_pending_pin else R.plurals.list_banner_pending_fingerprint,
                banner.hoursLeft,
                formatters.dateTime(banner.requestedAtMs),
                banner.hoursLeft,
            ),
            kind = BannerKind.WARNING,
            modifier = modifier,
            actions = { BannerButton(banner, "cancel", R.string.list_banner_cancel_it, actions.cancelPending) },
        )
        ListBanner.PendingReady -> Banner(
            text = stringResource(R.string.list_banner_pending_ready),
            modifier = modifier,
            actions = {
                BannerButton(banner, "cancel", R.string.list_banner_cancel_it, actions.cancelPending)
                BannerButton(banner, "later", R.string.common_later, actions.pendingLater)
                BannerButton(banner, "finish", R.string.list_banner_finish_now, actions.finishPending)
            },
        )
        is ListBanner.Replaced -> Banner(
            text = stringResource(
                if (banner.by == Shortcut.PIN) R.string.list_banner_replaced_pin else R.string.list_banner_replaced_fingerprint,
                formatters.date(banner.atMs),
            ),
            kind = BannerKind.WARNING,
            modifier = modifier,
            actions = {
                BannerButton(banner, "itWasntMe", R.string.list_banner_it_wasnt_me, actions.itWasntMe)
                BannerButton(banner, "ok", R.string.common_ok, actions.replacedSeen)
            },
        )
        is ListBanner.PracticeOverdue -> Banner(
            text = stringResource(R.string.list_banner_practice, formatters.date(banner.sinceMs)),
            kind = BannerKind.WARNING,
            modifier = modifier,
            actions = {
                BannerButton(banner, "forgotIt", R.string.list_banner_i_forgot_it, actions.forgotIt)
                BannerButton(banner, "typeItNow", R.string.list_banner_type_it_now, actions.typeItNow)
            },
        )
        is ListBanner.UnprotectedExport -> Banner(
            text = stringResource(R.string.list_banner_export, banner.name, formatters.relative(banner.savedAtMs, actions.wallMs())),
            kind = BannerKind.WARNING,
            modifier = modifier,
            actions = {
                BannerButton(banner, "deletedMyself", R.string.list_banner_deleted_it_myself) { actions.exportDeletedMyself(banner.uri) }
                BannerButton(banner, "delete", R.string.common_delete) { actions.deleteExport(banner.uri) }
            },
        )
        ListBanner.BackupDue -> Banner(
            text = stringResource(R.string.list_banner_backup),
            modifier = modifier,
            actions = {
                BannerButton(banner, "later", R.string.common_later, actions.backupLater)
                BannerButton(banner, "backUpNow", R.string.list_banner_back_up_now, actions.backUpNow)
            },
        )
        is ListBanner.ChangesPending -> Banner(
            text = pluralStringResource(R.plurals.list_banner_change, banner.count, banner.count, banner.firstTitle),
            modifier = modifier,
            actions = { BannerButton(banner, "answer", R.string.list_banner_answer_now) { actions.answer(banner.firstId) } },
        )
    }
}

/** A banner's button, tagged `pv.list.banner.<kind>.<name>`. */
@Composable
private fun RowScope.BannerButton(banner: ListBanner, name: String, @StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(ListTags.bannerButton(banner, name))) {
        Text(stringResource(label))
    }
}
