package dev.carplaymate.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real FamilyScreen/ViewModel recovery paths, with synthetic encrypted stores and silent output.
 * The existing Activity is only a Compose host; no real family data is edited or deleted.
 */
@RunWith(AndroidJUnit4::class)
class SystemRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun requireDedicatedEmulatorOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Enable systemRecoveryUiTests=true (or familyUiTests=true) on a dedicated emulator.",
            args.getString("systemRecoveryUiTests").equals("true", true) ||
                args.getString("familyUiTests").equals("true", true),
        )
    }

    @Test
    fun retryStaysPrivateWhileBusyAndRestoresTheSameFamilyWithoutPlayback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockRetry = false
        withHarness(
            beforeLocalLoad = {
                if (blockRetry) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        ) { h ->
            try {
                assertRecoveryPrivate()
                compose.onNodeWithText("기록을 확인할 수 없어요").assertIsDisplayed()
                capture("storage-error")
                h.restoreIntactStore()
                compose.runOnIdle { blockRetry = true }
                compose
                    .onNodeWithTag("storage-retry")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsEnabled()
                    .assertHeightIsAtLeast(80.dp)
                    .performClick()
                withTimeout(15_000) { entered.await() }
                h.await { it.busy && it.localStorageProblem }
                assertRecoveryPrivate()
                compose.onNodeWithTag("storage-retry").assertIsNotEnabled()
                compose.onNodeWithTag("storage-delete").assertIsNotEnabled()
                assertFalse(FamilyDeletionJournal(h.app).pending)
                capture("storage-retry-busy")

                release.complete(Unit)
                h.await { !it.busy && !it.localStorageProblem && it.onboarded }
                compose.onNodeWithTag("storage-recovery").assertDoesNotExist()
                compose.onNodeWithTag("family.home").assertIsDisplayed()
                compose.onNodeWithTag("family.onboarding").assertDoesNotExist()
                assertEquals(h.profileIds, h.model.ui.value.children.map { it.id })
                assertEquals(listOf(FIRST, SECOND), h.model.ui.value.children.map { it.nickname })
                assertEquals("PAUSED", h.model.ui.value.snapshot?.status)
                assertTrue(h.model.ui.value.quiet)
                assertFalse(h.model.ui.value.microphoneListening || h.model.ui.value.voiceInput)
                assertEquals(0, h.output.speaks)
                assertFalse(FamilyDeletionJournal(h.app).pending)
                capture("storage-recovered")
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun deletingRequiresExplicitConfirmationAndCancelPreservesEveryRecord() = runBlocking {
        withHarness { h ->
            val damagedBefore = h.readGameBytes()
            val profileBefore = FamilyPreferences(h.app).profile
            assertRecoveryPrivate()
            compose
                .onNodeWithTag("storage-delete")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHeightIsAtLeast(80.dp)
                .performClick()
            compose.onNodeWithText("가족 기록을 모두 지울까요?").assertIsDisplayed()
            compose
                .onNodeWithText("이 기기의 별명, 관심사, 진행 중인 모험과 기억을 모두 지워요. 지운 기록은 되돌릴 수 없어요.")
                .assertIsDisplayed()
            assertNoFamilyDetails()
            assertFalse(
                "Opening the dialog must not start deletion.",
                FamilyDeletionJournal(h.app).pending,
            )
            assertArrayEquals(damagedBefore, h.readGameBytes())
            capture("storage-delete-confirm")

            compose
                .onNodeWithText("기록 보존하기")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(72.dp)
                .performClick()
            compose.onNodeWithText("가족 기록을 모두 지울까요?").assertDoesNotExist()
            assertRecoveryPrivate()
            assertFalse(FamilyDeletionJournal(h.app).pending)
            assertEquals(profileBefore, FamilyPreferences(h.app).profile)
            assertArrayEquals(
                "Cancelling must preserve even the damaged encrypted bytes.",
                damagedBefore,
                h.readGameBytes(),
            )

            compose.onNodeWithTag("storage-delete").performScrollTo().performClick()
            compose
                .onNodeWithText("모두 삭제하기")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(72.dp)
                .performClick()
            h.await {
                !it.busy && !it.familyDeletionPending && !it.localStorageProblem && !it.onboarded
            }
            compose.onNodeWithTag("storage-recovery").assertDoesNotExist()
            compose.onNodeWithTag("family-deletion").assertDoesNotExist()
            compose.onNodeWithTag("family.onboarding").assertIsDisplayed()
            assertNoFamilyDetails()
            assertNull(FamilyPreferences(h.app).profile)
            assertNull(h.backend.restore())
            assertTrue(h.backend.journal().isEmpty())
            assertTrue(h.backend.feedback().isEmpty())
            assertFalse(FamilyDeletionJournal(h.app).pending)
            assertTrue(h.model.ui.value.quiet)
            assertEquals(0, h.output.speaks)
            capture("storage-deleted")
        }
    }

    @Test
    fun vehicleUnknownAndRestrictedHideRecoveryAndCancelAnOpenDeletionDialog() = runBlocking {
        withHarness { h ->
            val damagedBefore = h.readGameBytes()
            assertRecoveryPrivate()
            compose.onNodeWithTag("storage-delete").performScrollTo().performClick()
            compose.onNodeWithText("가족 기록을 모두 지울까요?").assertIsDisplayed()

            h.emit(VehicleAccessState.UNKNOWN)
            assertVehiclePrivate()
            compose.onNodeWithText("차량 상태를 확인하고 있어요").assertIsDisplayed()
            compose.onNodeWithText("확인이 끝날 때까지 화면과 소리를 잠시 쉬어요.").assertIsDisplayed()
            compose.onNodeWithText("안전하게 멈춘 뒤에 만나요").assertDoesNotExist()
            capture("vehicle-unknown")

            h.emit(VehicleAccessState.RESTRICTED)
            assertVehiclePrivate()
            compose.onNodeWithText("안전하게 멈춘 뒤에 만나요").assertIsDisplayed()
            compose
                .onNodeWithText("차량에서 이용할 수 있는 상태가 되면 다시 함께해요. 이야기는 이어서 할 수 있어요.")
                .assertIsDisplayed()
            compose.onNodeWithText("차량 상태를 확인하고 있어요").assertDoesNotExist()
            capture("vehicle-restricted")

            h.emit(VehicleAccessState.UNRESTRICTED)
            assertRecoveryPrivate()
            compose.onNodeWithTag("vehicle-restricted").assertDoesNotExist()
            compose.onNodeWithText("가족 기록을 모두 지울까요?").assertDoesNotExist()
            assertFalse(
                "A restriction must never confirm a pending UI deletion.",
                FamilyDeletionJournal(h.app).pending,
            )
            assertNotNull(FamilyPreferences(h.app).profile)
            assertArrayEquals(damagedBefore, h.readGameBytes())
            assertTrue(h.model.ui.value.quiet)
            assertFalse(h.model.ui.value.microphoneListening)
            assertEquals(0, h.output.speaks)
        }
    }

    private fun assertRecoveryPrivate() {
        compose.onNodeWithTag("storage-recovery").assertIsDisplayed()
        listOf(
                "family.home",
                "family.journal",
                "family.settings",
                "family.onboarding",
                "family.memoryDetail",
                "family.play",
                "family.libraryLink",
                "settings.memory",
                "settings.interests",
                "family.ending.feedbackToggle",
                "family.settingsNav",
            )
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        assertNoFamilyDetails()
    }

    private fun assertVehiclePrivate() {
        compose.onNodeWithTag("vehicle-restricted").assertIsDisplayed()
        listOf(
                "storage-recovery",
                "storage-retry",
                "storage-delete",
                "family.home",
                "family.settings",
                "family.onboarding",
                "family-deletion",
                "retry-family-deletion",
            )
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("가족 기록을 모두 지울까요?").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        assertNoFamilyDetails()
    }

    private fun assertNoFamilyDetails() {
        listOf(FIRST, SECOND).forEach {
            compose
                .onAllNodesWithText(it, substring = true, useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }

    private suspend fun withHarness(
        beforeLocalLoad: suspend () -> Unit = {},
        block: suspend (Harness) -> Unit,
    ) {
        val h = Harness()
        try {
            h.prepare(beforeLocalLoad)
            block(h)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            h.close()
        }
    }

    private inner class Harness {
        val app = ScopedApplication(instrumentation.targetContext)
        val backend = LocalGameClient(app)
        val output = SilentOutput()
        private val provider = FakeVehicle()
        private val store = ViewModelStore()
        lateinit var model: DeveloperViewModel
        lateinit var profileIds: List<String>
        private lateinit var intact: ByteArray
        private val file
            get() = File(app.noBackupFilesDir, "local_game_v1.json")

        suspend fun prepare(beforeLocalLoad: suspend () -> Unit) {
            val family = FamilyPreferences(app)
            assertTrue(family.save(FamilyProfile(FIRST, SECOND)))
            assertTrue(family.saveInterests(setOf("space"), setOf("animals")))
            // Storage recovery for a returning family whose mode was already chosen.
            assertTrue(InteractionPreferences(app).save(InteractionMode.LISTEN_AND_SELECT))
            val children = requireNotNull(family.profile).children
            profileIds = children.map { it.id }
            backend.createFamilyAdventure(
                playersBody(FIRST, SECOND),
                DEFAULT_ADVENTURE_ID,
                mapOf("p1" to profileIds[0], "p2" to profileIds[1]),
            )
            withContext(Dispatchers.IO) {
                intact = file.readBytes()
                assertTrue(PrivateRecordCipher.hasEnvelope(intact))
                val damaged = intact.copyOf()
                damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
                file.writeBytes(damaged)
            }
            model = main {
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DeveloperViewModel(
                                app,
                                null,
                                beforeLocalLoad = beforeLocalLoad,
                                outputFactory = { output },
                                vehicleAccessProvider = provider,
                            )
                                as T
                    },
                )[DeveloperViewModel::class.java]
            }
            await { !it.busy && it.localStorageProblem }
            assertTrue(model.ui.value.onboarded)
            assertTrue(model.ui.value.quiet)
            main { model.foreground(compose.activity) }
            compose.runOnUiThread {
                compose.activity.setContent { PlaymateTheme { FamilyScreen(model, onHide = {}) } }
            }
            compose.waitForIdle()
        }

        suspend fun await(predicate: (DeveloperUiState) -> Boolean) {
            withTimeout(15_000) { model.ui.first(predicate) }
            main { /* Flush the publishing main-thread action. */ }
            compose.waitForIdle()
        }

        suspend fun emit(state: VehicleAccessState) {
            main { provider.emit(state) }
            await { it.vehicleAccess == state }
        }

        suspend fun restoreIntactStore() = withContext(Dispatchers.IO) { file.writeBytes(intact) }

        suspend fun readGameBytes(): ByteArray = withContext(Dispatchers.IO) { file.readBytes() }

        suspend fun close() {
            main { store.clear() }
            // Wait for any store transaction that was already running before cancellation.
            backend.deleteAll()
            withContext(Dispatchers.IO) { app.clean() }
        }
    }

    private class FakeVehicle : VehicleAccessProvider {
        override val initialState = VehicleAccessState.UNRESTRICTED
        private var callback: ((VehicleAccessState) -> Unit)? = null

        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) {
            callback = changed
            changed(initialState)
        }

        override fun close() {
            callback = null
        }

        fun emit(state: VehicleAccessState) {
            requireNotNull(callback)(state)
        }
    }

    private class SilentOutput : SpeechOutput {
        override val status = MutableStateFlow("시험 음성")
        override val readyState = MutableStateFlow(false)
        var speaks = 0

        override fun refresh() = Unit

        override fun stop() = Unit

        override fun shutdown() = Unit

        override fun speak(
            plan: SpeechPlan,
            generation: Long,
            complete: () -> Unit,
            failure: (SpeechOutputFailure) -> Unit,
            started: () -> Unit,
        ) {
            speaks++
            error("Recovery UI must never start playback.")
        }
    }

    private class ScopedApplication(context: Context) : Application() {
        private val prefix = "system_recovery_ui_" + UUID.randomUUID()
        private val base = context.noBackupFilesDir.canonicalFile
        private val directory = File(base, prefix).also { check(it.mkdir()) }
        private val preferences = mutableSetOf<String>()
        private val keyAlias =
            "onu.records.v1." +
                MessageDigest.getInstance("SHA-256")
                    .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    .take(32)

        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "${prefix}_$name"
            synchronized(preferences) { preferences += scoped }
            return super.getSharedPreferences(scoped, mode)
        }

        fun clean() {
            check(directory.canonicalFile.parentFile == base && directory.name == prefix)
            synchronized(preferences) {
                preferences.forEach { check(super.deleteSharedPreferences(it)) }
            }
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(keyAlias)
            }
            check(directory.deleteRecursively())
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Compose idleness does not include the platform Dialog window's entrance animation.
        // Capture the settled confirmation, not a translucent intermediate frame.
        if (name == "storage-delete-confirm") android.os.SystemClock.sleep(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.filesDir, "system-recovery-screenshots")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun <T> main(action: () -> T): T {
        val task = FutureTask(Callable { action() })
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    companion object {
        private const val FIRST = "시험별"
        private const val SECOND = "시험달"
    }
}
