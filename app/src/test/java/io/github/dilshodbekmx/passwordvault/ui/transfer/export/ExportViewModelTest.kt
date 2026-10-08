package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.fake.FakeListModelSource
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentTypes
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.ui.list.ListBanner
import io.github.dilshodbekmx.passwordvault.ui.list.PasswordsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportAction
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportCleanup
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportDeletion
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportEvent
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFailure
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFile
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFormat
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportPasswordError
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportUiState
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.ADDED_MS
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.DOC2
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.DRIVE_DOC
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.GENERATOR
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.LABELS
import io.github.dilshodbekmx.passwordvault.ui.transfer.export.ExportKit.Companion.MASTER
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Build plan 5.3, [JVM]: the export's ViewModel against the fakes of `APP/fake/` and the real
 * `FileTaskStore` (ux.md §5.20, §4; formats.md §2; lock.md F12). The four acceptance lines are the
 * first tests: the warning needs "I understand"; the master password is typed every time; the file is
 * noted in the vault and the banner stays until "Delete" or "I deleted it myself"; "Done" is the
 * primary button.
 */
class ExportViewModelTest {

    // ---- The acceptance lines ----------------------------------------------------------------

    @Test
    fun theWarningNeedsIUnderstand() = ExportKit.run {
        for (format in ExportFormat.entries) {
            val vm = screen()
            vm.choose(format.choice)
            vm.continueFromChoice()
            settle()
            val warning = vm.shows<ExportUiState.Warning>()
            assertEquals(format, warning.format)
            assertFalse("not ticked when the warning opens", warning.understood)

            // "Continue" without "I understand" does nothing: no master password, no picker.
            vm.continueFromWarning()
            settle()
            assertEquals(warning, vm.state.value)

            // Ticked, then unticked again: still nothing.
            vm.setUnderstood(true)
            vm.setUnderstood(false)
            vm.continueFromWarning()
            settle()
            vm.shows<ExportUiState.Warning>()

            vm.setUnderstood(true)
            vm.continueFromWarning()
            settle()
            assertEquals(ExportFile(format), vm.shows<ExportUiState.Password>().file)

            // Back to the warning and on again: the box is still ticked, as the user left it.
            assertTrue(vm.back())
            assertTrue(vm.shows<ExportUiState.Warning>().understood)
            leave(vm)
        }
        assertEquals("no master password was asked for", emptyList<MpPurpose>(), lock.purposes)
        assertEquals("no picker was opened", emptyList<Pair<String, String>>(), picker.creates)
    }

    @Test
    fun theMasterPasswordIsTypedForEveryExport() = ExportKit.run {
        // The unlock left a proof in the lock's state: the export never takes it for the master password.
        val unlockProof = MasterProof.forFakes(byteArrayOf(1, 2, 3), lock.vaultId)
        lock.state.value = LockUiState.Unlocked(emptyList(), unlockProof)
        settle()

        val vm = screen()
        toPassword(vm, ExportChoice.CSV)
        vm.shows<ExportUiState.Password>()
        assertEquals("nothing opens before the master password", emptyList<Pair<String, String>>(), picker.creates)

        // A right one: its proof is dropped at once, and "Save as" opens.
        val proof = MasterProof.forFakes(byteArrayOf(9, 9), lock.vaultId)
        lock.mpResults.addLast(MpResult.Right(proof))
        submit(vm)
        assertEquals(listOf(MpPurpose.EXPORT), lock.purposes)
        assertEquals("the field is emptied when it is read", "", vm.password.text.toString())
        assertDropped(proof)
        assertEquals(listOf("passwords-2026-10-06.csv" to DocumentTypes.CSV), picker.creates)
        pick()
        assertTrue(vm.state.value is ExportUiState.Saved)
        unlockProof.useBytes { } // never used up by the export

        // A second export, at once and on the same screen: the master password again.
        leave(vm)
        val again = screen()
        toPassword(again, ExportChoice.JSON)
        again.shows<ExportUiState.Password>()
        assertEquals(1, picker.creates.size)
        submit(again)
        assertEquals(listOf(MpPurpose.EXPORT, MpPurpose.EXPORT), lock.purposes)
        assertEquals("passwords-2026-10-06.json" to DocumentTypes.JSON, picker.creates.last())
        pick(DOC2)
        again.shows<ExportUiState.Saved>()
    }

    @Test
    fun aLockWhileThePickerIsOpenAsksForTheMasterPasswordAgain() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.XLSX)
        submit(vm)
        vm.shows<ExportUiState.Picking>()

        // The screen turns off while "Save as" is open: the app locks. The user then chooses a place.
        lockNow()
        pick(name = "passwords-2026-10-06 (1).xlsx")
        assertEquals("nothing is written while locked", 0, documents.files.getValue(DOC).size)

        // After the unlock (with the PIN), the screen is made again; it asks before it writes.
        leave(vm)
        unlock()
        val after = screen()
        val paused = after.shows<ExportUiState.Password>()
        assertTrue("This was paused when the app locked.", paused.paused)
        assertEquals("passwords-2026-10-06 (1).xlsx", paused.name)
        assertEquals(0, documents.files.getValue(DOC).size)
        assertEquals(listOf(MpPurpose.EXPORT), lock.purposes)

        submit(after)
        assertEquals(listOf(MpPurpose.EXPORT, MpPurpose.EXPORT), lock.purposes)
        assertEquals("no second \"Save as\": the task's document is written", 1, picker.creates.size)
        val saved = after.shows<ExportUiState.Saved>()
        assertEquals(DocRef(DOC), saved.doc)
        assertTrue(documents.files.getValue(DOC).isNotEmpty())
        assertNull("the task is done", settings.fileTask.value)
    }

    @Test
    fun theFileIsNotedInTheVaultAndTheBannerStaysUntilDeleteOrIDeletedItMyself() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.CSV)
        submit(vm)
        pick(DOC, name = "passwords-2026-10-06.csv")
        vm.shows<ExportUiState.Saved>()

        // Noted with the name the place shows and the time it was saved.
        assertEquals(listOf(FileRef(DOC, "passwords-2026-10-06.csv", clock.wallMs())), repository.stored.plainExports)
        val banner = ListBanner.UnprotectedExport(DOC, "passwords-2026-10-06.csv", clock.wallMs())

        val passwords = listScreen()
        assertTrue(banner in passwords.state.value.banners)

        // It stays: through other changes, a lock and an unlock, and a new process.
        repository.edit(VaultEdits.setFavourite(ExportKit.BANK, true))
        lockNow()
        unlock()
        settle()
        assertTrue(banner in passwords.state.value.banners)
        newProcess()
        unlock()
        assertEquals(listOf(DOC), repository.stored.plainExports.map { it.uri })

        // "I deleted it myself" on the banner: the note goes, with the permission.
        passwords.exportDeletedMyself(DOC)
        settle()
        assertEquals(emptyList<FileRef>(), repository.stored.plainExports)
        assertFalse(banner in passwords.state.value.banners)
        assertTrue(DOC in documents.released)

        // A second export, at a place that does not let apps delete: "Delete it now" says so, and the banner stays.
        val second = screen()
        toPassword(second, ExportChoice.JSON)
        submit(second)
        documents.notDeletable += DOC2
        pick(DOC2, name = "passwords-2026-10-06.json")
        second.deleteNow()
        settle()
        assertEquals(ExportDeletion.NOT_ALLOWED, second.shows<ExportUiState.Saved>().deletion)
        assertEquals(listOf(DOC2), repository.stored.plainExports.map { it.uri })
        assertTrue(passwords.state.value.banners.any { it is ListBanner.UnprotectedExport && it.uri == DOC2 })

        // Its "Delete" on the banner, once the place allows it: the file and the note go.
        documents.notDeletable -= DOC2
        passwords.deleteExport(DOC2)
        settle()
        assertFalse(DOC2 in documents.files)
        assertEquals(emptyList<FileRef>(), repository.stored.plainExports)

        // A third: "Delete it now" where the place allows it removes the file, the note and the permission.
        leave(second)
        val third = screen()
        toPassword(third, ExportChoice.XLSX)
        submit(third)
        pick(DOC, name = "passwords-2026-10-06.xlsx")
        assertEquals(listOf(DOC), repository.stored.plainExports.map { it.uri })
        documents.released.clear()
        third.deleteNow()
        settle()
        assertEquals(ExportDeletion.DELETED, third.shows<ExportUiState.Saved>().deletion)
        assertFalse(DOC in documents.files)
        assertEquals(emptyList<FileRef>(), repository.stored.plainExports)
        assertEquals(listOf(DOC), documents.released)
        assertFalse(passwords.state.value.banners.any { it is ListBanner.UnprotectedExport })
    }

    @Test
    fun doneIsThePrimaryButton() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.JSON)
        submit(vm)
        pick(name = "passwords-2026-10-06.json")
        val saved = vm.shows<ExportUiState.Saved>()
        assertEquals("the main button is Done", ExportAction.DONE, saved.primary)
        assertEquals("Delete it now is the second", ExportAction.DELETE_NOW, saved.secondary)

        // After "Delete it now" Done is still the main button, and the only one.
        vm.deleteNow()
        settle()
        val deleted = vm.shows<ExportUiState.Saved>()
        assertEquals(ExportAction.DONE, deleted.primary)
        assertNull(deleted.secondary)

        // Between the files of a CSV export cut into parts the main button saves the next one; on the last, Done.
        assertEquals(ExportAction.NEXT_PART, saved.copy(file = ExportFile(ExportFormat.CSV, 1), parts = 2).primary)
        assertEquals(ExportAction.DONE, saved.copy(file = ExportFile(ExportFormat.CSV, 2), parts = 2).primary)
    }

    // ---- The master password --------------------------------------------------------------------

    @Test
    fun aWrongPasswordAWaitOrTooLittleMemoryNeverOpenThePicker() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.CSV)

        lock.mpResults.addLast(MpResult.Wrong)
        submit(vm, "wrong")
        assertEquals(ExportPasswordError.Wrong, vm.shows<ExportUiState.Password>().error)

        lock.mpResults.addLast(MpResult.Wait(clock.elapsedMs() + 30_000))
        submit(vm, "wrong again")
        var waiting = vm.shows<ExportUiState.Password>()
        assertEquals(ExportPasswordError.Wait, waiting.error)
        assertEquals(30, waiting.waitSecondsLeft)
        assertFalse("the field waits", waiting.enabled)
        type(vm, "typed during the wait")
        vm.submitPassword()
        settle()
        assertEquals("nothing is checked during a wait", 2, lock.purposes.size)
        pass(1_000)
        assertEquals(29, vm.shows<ExportUiState.Password>().waitSecondsLeft)
        pass(29_000)
        waiting = vm.shows()
        assertNull(waiting.waitSecondsLeft)
        assertNull(waiting.error)
        assertTrue(waiting.enabled)

        lock.mpResults.addLast(MpResult.NoMemory)
        submit(vm, "x")
        assertEquals(ExportPasswordError.NoMemory, vm.shows<ExportUiState.Password>().error)

        lock.mpResults.addLast(MpResult.OlderPassword(ADDED_MS, unfinishedChange = false))
        submit(vm, "old")
        assertEquals(ExportPasswordError.Older(ADDED_MS), vm.shows<ExportUiState.Password>().error)

        // The app locked while the lock checked: nothing opens, and the screen waits for a new try.
        lock.mpResults.addLast(MpResult.TooLate)
        submit(vm, "late")
        val late = vm.shows<ExportUiState.Password>()
        assertFalse(late.checking)
        assertNull(late.error)

        assertEquals(emptyList<Pair<String, String>>(), picker.creates)
        assertTrue(lock.purposes.all { it == MpPurpose.EXPORT })
        assertEquals("", vm.password.text.toString())
    }

    @Test
    fun aClosedPickerAsksForTheMasterPasswordAgain() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.CSV)
        submit(vm)
        vm.shows<ExportUiState.Picking>()
        closePicker()
        val back = vm.shows<ExportUiState.Password>()
        assertFalse("no message: nothing was made", back.paused)
        assertNull(back.error)
        assertNull(settings.fileTask.value)

        // Nothing opens without typing it again.
        vm.submitPassword()
        settle()
        assertEquals(1, picker.creates.size)
        submit(vm)
        assertEquals(2, picker.creates.size)
        assertEquals(listOf(MpPurpose.EXPORT, MpPurpose.EXPORT), lock.purposes)
    }

    @Test
    fun aNewProcessContinuesTheTaskAfterTheUnlockWithTheMasterPassword() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.CSV)
        submit(vm)
        // The process dies while "Save as" is open; the picker's answer reaches the new one, locked.
        newProcess()
        pick(name = "passwords-2026-10-06.csv")
        assertEquals(FileTask(FileTask.Kind.EXPORT, ExportFile(ExportFormat.CSV).step, DocRef(DOC)), settings.fileTask.value)

        unlock()
        val resumed = screen()
        assertTrue(resumed.shows<ExportUiState.Password>().paused)
        submit(resumed)
        resumed.shows<ExportUiState.Saved>()
        assertArrayEquals(GoogleCsv.export(sampleEntries()).parts.single(), documents.files.getValue(DOC))
        assertNull(settings.fileTask.value)
    }

    @Test
    fun cancelOnAPausedTaskRemovesTheEmptyFileOrSaysWhereItIs() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.JSON)
        submit(vm)
        lockNow()
        pick()
        leave(vm)
        unlock()
        val paused = screen()
        paused.cancelPaused()
        settle()
        assertEquals(ExportUiState.Ended(ExportCleanup.Removed), paused.state.value)
        assertFalse(DOC in documents.files)
        assertNull(settings.fileTask.value)
        assertTrue(DOC in documents.released)

        // The same at Google Drive, which does not let the app delete it: where it is, by name.
        val second = screen()
        toPassword(second, ExportChoice.JSON)
        submit(second)
        lockNow()
        documents.places[DRIVE_DOC] = Place.GoogleDrive
        documents.notDeletable += DRIVE_DOC
        pick(DRIVE_DOC, name = "passwords-2026-10-06.json")
        leave(second)
        unlock()
        val again = screen()
        assertTrue("Back gives the paused task up as Cancel does", again.back())
        settle()
        assertEquals(ExportUiState.Ended(ExportCleanup.Left("passwords-2026-10-06.json", Place.GoogleDrive)), again.state.value)
        assertEquals("never written", 0, documents.files.getValue(DRIVE_DOC).size)
        assertEquals("no master password for a task that is given up", listOf(MpPurpose.EXPORT, MpPurpose.EXPORT), lock.purposes)
    }

    // ---- The note and the write -------------------------------------------------------------------

    @Test
    fun aFailedWriteRemovesTheFileAndItsNoteOrKeepsTheNoteOfAFileThatStays() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.CSV)
        submit(vm)
        documents.writeResults.addLast(false)
        pick(name = "passwords-2026-10-06.csv")
        val failed = vm.shows<ExportUiState.Failed>()
        assertEquals(ExportFailure.WRITE, failed.why)
        assertEquals(ExportCleanup.Removed, failed.cleanup)
        assertFalse(DOC in documents.files)
        assertEquals("the note of a file that is gone goes too", emptyList<FileRef>(), repository.stored.plainExports)

        // "Try again": "Save as" again, without the master password (the same export, no lock).
        vm.tryAgain()
        settle()
        assertEquals(2, picker.creates.size)
        assertEquals(1, lock.purposes.size)

        // This time the write fails at a place that keeps the file: it may hold part of the export, so its note stays.
        documents.writeResults.addLast(false)
        documents.notDeletable += DOC2
        pick(DOC2, name = "passwords-2026-10-06 (1).csv")
        val left = vm.shows<ExportUiState.Failed>()
        assertEquals(ExportCleanup.Left("passwords-2026-10-06 (1).csv", Place.PhoneStorage), left.cleanup)
        assertEquals(listOf(DOC2), repository.stored.plainExports.map { it.uri })
    }

    @Test
    fun theNoteIsMadeBeforeTheWriteAndWithoutItNothingIsWritten() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.XLSX)
        submit(vm)
        repository.failures.addLast(SaveProblem.FAILED)
        pick()
        val failed = vm.shows<ExportUiState.Failed>()
        assertEquals(ExportFailure.NOT_NOTED, failed.why)
        assertEquals("the empty file was removed", ExportCleanup.Removed, failed.cleanup)
        assertTrue(failed.canRetry)
        assertFalse(DOC in documents.files)

        vm.tryAgain()
        settle()
        repository.failures.addLast(SaveProblem.TOO_LARGE)
        pick(DOC2)
        val tooLarge = vm.shows<ExportUiState.Failed>()
        assertEquals(ExportFailure.TOO_LARGE, tooLarge.why)
        assertFalse("another try cannot help", tooLarge.canRetry)
        vm.tryAgain()
        settle()
        assertEquals(2, picker.creates.size)
        assertEquals(emptyList<FileRef>(), repository.stored.plainExports)
    }

    @Test
    fun theFilesHoldWhatTheWritersWriteWithoutDeletedAccounts() = ExportKit.run {
        for ((choice, uri) in listOf(ExportChoice.CSV to DOC, ExportChoice.JSON to DOC2, ExportChoice.XLSX to DRIVE_DOC)) {
            val vm = screen()
            vm.useLabels(LABELS)
            toPassword(vm, choice)
            submit(vm)
            pick(uri)
            vm.shows<ExportUiState.Saved>()
            leave(vm)
        }
        val entries = sampleEntries()
        val csv = GoogleCsv.export(entries).parts.single()
        val json = BitwardenJson.export(vault(), clock.wallMs(), GENERATOR).bytes
        val xlsx = VaultXlsx.export(entries, zone, LABELS).bytes
        assertArrayEquals(csv, documents.files.getValue(DOC))
        assertArrayEquals(json, documents.files.getValue(DOC2))
        assertArrayEquals(xlsx, documents.files.getValue(DRIVE_DOC))

        // Deleted accounts are in none of them; a pending change has its new password and keeps the old one.
        for (uri in listOf(DOC, DOC2)) {
            val text = String(documents.files.getValue(uri), Charsets.UTF_8)
            assertFalse(uri, "gone-pass" in text)
            assertTrue(uri, "N3w!pass" in text)
            assertTrue(uri, "Old#2" in text)
            assertTrue(uri, "+998901234567" in text)
            assertTrue(uri, "=1+2" in text)
        }
        assertEquals(
            listOf("passwords-2026-10-06.csv" to "text/csv", "passwords-2026-10-06.json" to "application/json",
                "passwords-2026-10-06.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            picker.creates,
        )
    }

    @Test
    fun aLargeCsvExportIsSavedInPartsWithOneSaveAsEachAndOneMasterPassword() = ExportKit.run(vault = Vault(entries = manyAccounts(3_001))) {
        val vm = screen()
        vm.choose(ExportChoice.CSV)
        vm.continueFromChoice()
        settle()
        assertEquals(2, vm.shows<ExportUiState.Warning>().csv?.parts)
        vm.setUnderstood(true)
        vm.continueFromWarning()
        submit(vm)
        assertEquals("passwords-2026-10-06-part1.csv", picker.creates.single().first)
        pick(DOC)
        val first = vm.shows<ExportUiState.Saved>()
        assertEquals(1, first.file.part)
        assertEquals(2, first.parts)
        assertEquals(ExportAction.NEXT_PART, first.primary)

        vm.nextPart()
        settle()
        assertEquals("passwords-2026-10-06-part2.csv", picker.creates.last().first)
        pick(DOC2)
        val second = vm.shows<ExportUiState.Saved>()
        assertEquals(2, second.file.part)
        assertEquals(ExportAction.DONE, second.primary)
        assertEquals("one master password for the whole export", listOf(MpPurpose.EXPORT), lock.purposes)

        val parts = GoogleCsv.export(vault().entries).parts
        assertArrayEquals(parts[0], documents.files.getValue(DOC))
        assertArrayEquals(parts[1], documents.files.getValue(DOC2))
        assertEquals("each file is noted", listOf(DOC, DOC2), repository.stored.plainExports.map { it.uri })
    }

    // ---- Around the export -------------------------------------------------------------------------

    @Test
    fun nothingIsExportedWhileANewMasterPasswordIsPending() = ExportKit.run {
        val pending = PendingMasterPassword(clock.wallMs(), Shortcut.PIN, 0L, 24 * 3_600_000L, ready = false)
        lock.status.value = LockStatus.NONE.copy(pending = pending)
        val vm = screen()
        assertEquals(ExportUiState.Pending(), vm.state.value)
        vm.continueFromChoice()
        vm.continueFromWarning()
        settle()
        assertEquals(ExportUiState.Pending(), vm.state.value)

        // It begins while the master password is asked: the screen says so and nothing is checked.
        lock.status.value = LockStatus.NONE
        val other = screen()
        toPassword(other, ExportChoice.CSV)
        type(other, MASTER)
        lock.status.value = LockStatus.NONE.copy(pending = pending)
        settle()
        assertEquals(ExportUiState.Pending(), other.state.value)
        assertEquals("", other.password.text.toString())
        assertEquals(emptyList<MpPurpose>(), lock.purposes)
    }

    @Test
    fun theBackupRowOpensTheBackupScreen() = ExportKit.run {
        val vm = screen()
        val events = eventsOf(vm)
        assertEquals("the recommended row is chosen first", ExportChoice.BACKUP, vm.shows<ExportUiState.Choose>().choice)
        vm.continueFromChoice()
        settle()
        assertEquals(listOf<ExportEvent>(ExportEvent.OpenBackup), events)
        vm.shows<ExportUiState.Choose>()
    }

    @Test
    fun theWarningAddsTheLinesOfItsFormatAndOfOldAndroid() = ExportKit.run {
        val old = screen(oldAndroid = true)
        old.choose(ExportChoice.JSON)
        old.continueFromChoice()
        settle()
        val json = old.shows<ExportUiState.Warning>()
        assertTrue(json.oldAndroid)
        assertNull("JSON adds nothing", json.csv)

        val new = screen(oldAndroid = false)
        new.choose(ExportChoice.CSV)
        new.continueFromChoice()
        settle()
        val csv = new.shows<ExportUiState.Warning>()
        assertFalse(csv.oldAndroid)
        val card = csv.csv ?: throw AssertionError("the CSV card is counted")
        assertEquals(1, card.parts)
        // Google will skip the two accounts without a website (the deleted one is not in the file).
        val noWebsite = card.skipped.single { it.reason == GoogleCsv.Refusal.NO_WEBSITE }
        assertEquals(listOf("Ипотека банк", "Uy telefoni"), noWebsite.titles)
        assertFalse(csv.showWhich)
        new.toggleWhich()
        assertTrue(new.shows<ExportUiState.Warning>().showWhich)
        new.toggleWhich()
        assertFalse(new.shows<ExportUiState.Warning>().showWhich)
    }

    @Test
    fun aPlaceThatMayUploadLaterIsSaidSo() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.JSON)
        submit(vm)
        pick(DOC)
        assertFalse(vm.shows<ExportUiState.Saved>().mayUploadLater)
        leave(vm)

        for (place in listOf(Place.GoogleDrive, Place.Other("USB drive"))) {
            documents.places[DRIVE_DOC] = place
            val other = screen()
            toPassword(other, ExportChoice.JSON)
            submit(other)
            pick(DRIVE_DOC)
            val saved = other.shows<ExportUiState.Saved>()
            assertEquals(place, saved.place)
            assertTrue(saved.mayUploadLater)
            leave(other)
        }
    }

    @Test
    fun backGoesOneStepBackAndIsTakenWhileTheFileIsChosenOrSaved() = ExportKit.run {
        val vm = screen()
        assertFalse("from the first step Back leaves", vm.back())
        toPassword(vm, ExportChoice.XLSX)
        type(vm, "half typed")
        assertTrue(vm.back())
        assertEquals("the field is emptied", "", vm.password.text.toString())
        vm.shows<ExportUiState.Warning>()
        assertTrue(vm.back())
        assertEquals(ExportChoice.XLSX, vm.shows<ExportUiState.Choose>().choice)
        assertFalse(vm.back())

        toPassword(vm, ExportChoice.XLSX)
        submit(vm)
        vm.shows<ExportUiState.Picking>()
        assertTrue(vm.takesBack(vm.state.value))

        // The screen shows while "Save as" should be open: Back stops waiting for it, and its task goes.
        assertTrue(vm.back())
        settle()
        assertFalse(vm.shows<ExportUiState.Password>().paused)
        assertNull(settings.fileTask.value)
        pick() // an answer that still comes finds no task: its empty file is removed
        assertFalse(DOC in documents.files)
        vm.shows<ExportUiState.Password>()

        // The master password again, then the file; while it is saved Back is taken and does nothing.
        submit(vm)
        holdWrites = true
        pick()
        vm.shows<ExportUiState.Saving>()
        assertTrue(vm.takesBack(vm.state.value))
        assertTrue(vm.back())
        vm.shows<ExportUiState.Saving>()
        holdWrites = false
        settle()
        vm.shows<ExportUiState.Saved>()
        assertFalse("from the result Back leaves", vm.back())
        assertFalse(vm.takesBack(vm.state.value))
    }

    @Test
    fun aLockEmptiesTheFieldAndEndsWhatTheMasterPasswordAllowed() = ExportKit.run {
        val vm = screen()
        toPassword(vm, ExportChoice.CSV)
        type(vm, "half typed")
        lockNow()
        assertEquals("", vm.password.text.toString())
        unlock()

        // Typed right, then the app locks before the picker answers: the answer needs the password again.
        submit(vm)
        lockNow()
        unlock()
        pick()
        val paused = vm.shows<ExportUiState.Password>()
        assertTrue(paused.paused)
        assertEquals(0, documents.files.getValue(DOC).size)
        submit(vm)
        vm.shows<ExportUiState.Saved>()
        assertEquals(listOf(MpPurpose.EXPORT, MpPurpose.EXPORT), lock.purposes)
    }

    @Test
    fun aTaskWhosePickerNeverAnsweredIsGivenUpWhenTheScreenOpens() = ExportKit.run {
        settings.fileTask.value = FileTask(FileTask.Kind.EXPORT, ExportFile(ExportFormat.CSV).step, null)
        newProcess()
        unlock()
        val vm = screen()
        vm.shows<ExportUiState.Choose>()
        assertNull(settings.fileTask.value)
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private fun assertDropped(proof: MasterProof) {
        try {
            proof.useBytes { }
            fail("the proof of the export's master password was kept")
        } catch (expected: IllegalStateException) {
            // dropped
        }
    }

    private fun sampleEntries(): List<Entry> = ExportKit.sampleVault().entries

    private fun manyAccounts(count: Int): List<Entry> = (1..count).map { n ->
        val hex = "%012x".format(n)
        Entry(
            id = "00000000-0000-4000-8000-$hex",
            title = "Account $n",
            username = "user$n",
            password = "pw-$n",
            urls = listOf("https://site$n.example"),
            createdAt = ADDED_MS,
            updatedAt = ADDED_MS,
        )
    }

    /** The app's Passwords screen over the same vault, lock and documents: its banners. */
    private fun ExportKit.listScreen(): PasswordsViewModel {
        val lists = FakeListModelSource()
        lists.show(repository.stored.entries)
        return other {
            PasswordsViewModel(
                repository = repository,
                lists = lists,
                lock = lock,
                settings = settings,
                clipboard = FakeSecretClipboard(),
                session = FakeSession(work, lock, FakeSecretClipboard(), repository),
                files = documents,
                clock = clock,
                dispatchers = dispatchers,
            )
        }
    }
}
