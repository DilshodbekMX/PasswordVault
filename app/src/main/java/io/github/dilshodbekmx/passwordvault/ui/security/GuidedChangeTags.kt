package io.github.dilshodbekmx.passwordvault.ui.security

/**
 * The test tags of the guided change (ux.md §5.17), exposed as resource ids for the emulator script
 * `tools/emu/change/guided.sh` and the device tests, so that they find each part whatever the
 * language. The buttons whose words change with the state have a tag per state: [CREATE] before a
 * new password was made, [CREATE_ANOTHER] after.
 */
object GuidedChangeTags {
    /** The scrolling column of the steps. */
    const val STEPS = "pv.change.steps"

    /** The account's name above the steps. */
    const val ACCOUNT = "pv.change.account"

    /** "Last one! After this, your old password is not used anywhere." */
    const val LAST_ONE = "pv.change.lastOne"

    /** Step 1: "Open website" and "Copy old password". */
    const val OPEN_WEBSITE = "pv.change.openWebsite"
    const val COPY_OLD_FIRST = "pv.change.step1.copyOld"

    /** Step 3: "Create new password", "Create another password", "Saved. …" and "Password rules". */
    const val CREATE = "pv.change.create"
    const val CREATE_ANOTHER = "pv.change.createAnother"
    const val SAVED = "pv.change.saved"
    const val RULES = "pv.change.rules"

    /** Step 4: "Copy old password" and "Copy new password". */
    const val COPY_OLD = "pv.change.step4.copyOld"
    const val COPY_NEW = "pv.change.copyNew"

    /** Step 5: "Yes, it's changed", "No, put back the old password" and "I'll finish later". */
    const val YES = "pv.change.yes"
    const val NO = "pv.change.no"
    const val LATER = "pv.change.later"

    /** The end of the change: "Gmail now has its own password.", "Next: …", "Done for now". */
    const val DONE = "pv.change.done"
    const val NEXT = "pv.change.next"
    const val FINISH = "pv.change.finish"

    /** The backup line under the end and its "Back up now". */
    const val BACKUP_LINE = "pv.change.backupLine"
    const val BACK_UP_NOW = "pv.change.backUpNow"

    /** The account is not there: the sentence and its "Back". */
    const val GONE = "pv.change.gone"
    const val GONE_BACK = "pv.change.goneBack"

    /** The back arrow of the top bar. */
    const val BACK = "pv.change.back"

    /** The snackbar. */
    const val MESSAGE = "pv.change.message"

    /** The card of step [number] (1 to 5). */
    fun step(number: Int): String = "pv.change.step.$number"
}
