package dev.carplaymate.demo

import android.Manifest
import android.app.Application
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity and foreground Service; all media, capture and focus ports are silent controlled fixtures. */
@RunWith(AndroidJUnit4::class)
class LetterHostServiceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var activeFixture: LetterHostHarness? = null
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main { done = predicate() }
            if (done) return
            SystemClock.sleep(25)
        }
        var detail=""
        main { detail=activeFixture?.diagnostic().orEmpty() }
        fail("$label; $detail")
    }

    /** Bring only this fixture's task forward, then perform one explicit gesture under real focus. */
    @Suppress("DEPRECATION")
    private fun focusedAction(h: LetterHostHarness, label: String, action: () -> Unit) {
        val until=SystemClock.elapsedRealtime()+30_000L
        var attempts=0
        var lastFront=0L
        var taps=0
        var steady=0
        while (SystemClock.elapsedRealtime()<until) {
            var focused=false
            var activatePane=false
            main {
                h.scenario.onActivity { activity ->
                    focused=activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus() &&
                        !activity.isFinishing && !activity.isDestroyed
                    h.focusDiagnostic="task=${activity.taskId}, lifecycle=${activity.lifecycle.currentState}, focus=${activity.hasWindowFocus()}, finishing=${activity.isFinishing}"
                    if (!focused && attempts<3 && SystemClock.elapsedRealtime()-lastFront>=1_000L) {
                        val task=h.app.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                            it.taskInfo.id==activity.taskId && it.taskInfo.baseIntent.component?.className==MainActivity::class.java.name
                        }
                        checkNotNull(task) { "Fixture MainActivity task not found: ${h.focusDiagnostic}" }.moveToFront()
                        attempts++
                        lastFront=SystemClock.elapsedRealtime()
                        activatePane=true
                    }
                }
            }
            if (activatePane) {
                // Pleos can leave a visible RESUMED app pane without input focus after task launch.
                // Read its current on-screen bounds again, then tap only its empty top-centre inset.
                var point: Pair<Float,Float>?=null
                main {
                    h.scenario.onActivity { activity ->
                        val decor=activity.window.decorView
                        val local=Rect()
                        val location=IntArray(2)
                        val display=(activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !activity.hasWindowFocus() &&
                            !activity.isFinishing && !activity.isDestroyed && decor.isAttachedToWindow &&
                            decor.isShown && decor.windowVisibility==View.VISIBLE &&
                            display.displayId==Display.DEFAULT_DISPLAY && decor.getLocalVisibleRect(local) &&
                            local.width()>2 && local.height()>2) {
                            decor.getLocationOnScreen(location)
                            val padding=24f*activity.resources.displayMetrics.density
                            val topInset=decor.rootWindowInsets?.systemWindowInsetTop ?: 0
                            val x=(local.left+local.right)/2f
                            val y=maxOf(local.top+padding,topInset+padding)
                                .coerceIn(local.top+1f,local.bottom-1f)
                            point=Pair(location[0]+x,location[1]+y)
                            h.paneDiagnostic="tapBounds=${local.toShortString()}@${location.toList()}, point=$point"
                        } else h.paneDiagnostic="pane not safely tappable or already focused; display=${display.displayId}"
                    }
                }
                point?.let { (x,y) ->
                    val downAt=SystemClock.uptimeMillis()
                    fun inject(kind:Int):Boolean {
                        val event=MotionEvent.obtain(downAt,SystemClock.uptimeMillis(),kind,x,y,0)
                        event.source=InputDevice.SOURCE_TOUCHSCREEN
                        return try { inst.uiAutomation.injectInputEvent(event,true) } finally { event.recycle() }
                    }
                    val down=inject(MotionEvent.ACTION_DOWN)
                    val up=inject(MotionEvent.ACTION_UP)
                    taps++
                    h.paneDiagnostic+="; injected=$down/$up"
                }
            }
            steady=if(focused) steady+1 else 0
            if (steady>=3) {
                var invoked=false
                main {
                    h.scenario.onActivity { activity ->
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus() &&
                            !activity.isFinishing && !activity.isDestroyed) {
                            action() // A rejected request is never retried by this helper.
                            invoked=true
                        }
                    }
                }
                if (invoked) return
                steady=0
            }
            SystemClock.sleep(100)
        }
        var detail=""
        main { detail=h.diagnostic() }
        fail("$label: could not obtain a stable focused MainActivity; attempts=$attempts; taps=$taps; $detail")
    }

    private fun owner(host: LetterSessionHost) = LetterSessionHost::class.java.getDeclaredField("service")
        .apply { isAccessible = true }.get(host) as? LetterSessionService
    private fun fixture(case: String="single", block: (LetterHostHarness, ActivityScenario<MainActivity>) -> Unit) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("letterHostTests") == "true")
        val app = inst.targetContext.applicationContext as LetterHostTestApplication
        inst.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            inst.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        lateinit var h: LetterHostHarness
        main { h = LetterHostHarness(app); h.caseName=case; app.fixture = h; activeFixture=h }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        h.scenario=activity
        try {
            focusedAction(h,"Prepare $case") {}
            main { h.session = h.host.bind(JumpEpisode, listOf("letter-test"), null) }
            block(h,activity)
        } finally {
            main { h.host.close() }
            activity.close()
            waitFor("Service destroyed before fixture release") {
                LetterSessionHost::class.java.getDeclaredField("stoppingService").apply { isAccessible=true }.get(h.host)==null
            }
            // The real framework MBS must release its collector of this fixture's host before
            // the test Application can expose a different fixture. Never force-stop another host.
            waitFor("Framework media browser service destroyed before fixture release") {
                @Suppress("DEPRECATION")
                val services = app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                services.none { it.service.packageName == app.packageName &&
                    it.service.className == LetterMediaBrowserService::class.java.name }
            }
            main { app.fixture = null; activeFixture=null }
        }
    }
    private fun start(h: LetterHostHarness) {
        focusedAction(h,"Open ${h.caseName}") { h.host.runExplicit(h.session) { h.session.choose("open") } }
        waitFor("Actual foreground promotion before output") { owner(h.host) != null && h.player.parts.size == 1 }
    }

    /** Real framework browser/controller using this test app's UID, not the external Pleos host. */
    private fun withBrowser(h: LetterHostHarness, block: (MediaBrowser, MediaController) -> Unit) {
        org.junit.Assume.assumeTrue("Only optional media probe enables browser", BuildConfig.LETTER_MEDIA_BROWSER_ENABLED)
        lateinit var browser: MediaBrowser
        var connected = false
        var failed = false
        var catalog: MutableList<MediaBrowser.MediaItem>? = null
        main {
            browser = MediaBrowser(h.app, ComponentName(h.app, LetterMediaBrowserService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected = true }
                    override fun onConnectionFailed() { failed = true }
                    override fun onConnectionSuspended() { failed = true }
                }, null)
            browser.connect()
        }
        try {
            waitFor("Own-UID framework MediaBrowser connects") { connected || failed }
            main {
                assertFalse("Framework media browser connection failed", failed)
                assertEquals(LetterMediaBrowserService.ROOT, browser.root)
                browser.subscribe(browser.root, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        catalog = children
                    }
                    override fun onError(parentId: String) { failed = true }
                })
            }
            waitFor("Fixed public episode catalog is delivered") { catalog != null || failed }
            lateinit var controller: MediaController
            main {
                assertFalse("Framework media browser subscription failed", failed)
                val items = checkNotNull(catalog)
                assertEquals(2, items.size)
                assertEquals(setOf("first_jump", "rain_music"), items.map { it.mediaId }.toSet())
                assertTrue("Catalog entries must not advertise an unavailable PLAY command",
                    items.all { !it.isPlayable && !it.isBrowsable })
                assertTrue(items.all { it.description.extras == null && it.description.mediaUri == null })
                assertEquals(listOf("바다에서 온 소리 편지", "펭귄에게서 온 답장"),
                    items.map { it.description.title.toString() })
                controller = MediaController(h.app, browser.sessionToken)
            }
            block(browser, controller)
        } finally {
            main { if (browser.isConnected) browser.unsubscribe(browser.root); browser.disconnect() }
            inst.waitForIdleSync()
        }
    }

    private fun playback(controller: MediaController): PlaybackState = checkNotNull(controller.playbackState)

    /** A bounded negative observation; subsequent positive transport transitions are checked separately. */
    private fun settleMediaDispatch() {
        inst.waitForIdleSync()
        SystemClock.sleep(150)
        inst.waitForIdleSync()
    }

    @Test fun ownUidBrowserBrowseAndStandardCommandsCannotStartAColdLetter() = fixture { h, _ ->
        withBrowser(h) { _, controller ->
            main {
                assertEquals(PlaybackState.STATE_STOPPED, playback(controller).state)
                assertEquals(0L, playback(controller).actions)
                controller.transportControls.prepare()
                controller.transportControls.prepareFromMediaId("first_jump", null)
                controller.transportControls.prepareFromSearch("펭귄", null)
                controller.transportControls.play()
                controller.transportControls.playFromMediaId("first_jump", null)
                controller.transportControls.playFromSearch("펭귄", null)
                controller.transportControls.sendCustomAction(LetterMediaBrowserService.RESUME_PREFIX + "invented", null)
            }
            settleMediaDispatch()
            main {
                assertNull(owner(h.host))
                assertFalse(h.host.mediaState.value.owned)
                assertNull(h.host.mediaState.value.resumeToken)
                assertEquals(PlaybackState.STATE_STOPPED, playback(controller).state)
                assertTrue(h.player.parts.isEmpty())
                assertTrue(h.mic.asks.isEmpty())
                h.assertSilentPorts()
            }
        }
    }

    @Test fun ownUidMediaControllerRequiresCurrentNonceAndReportsInputWithoutPlayback() = fixture { h, activity ->
        withBrowser(h) { browser, controller ->
            lateinit var stableToken: MediaSession.Token
            main { stableToken = browser.sessionToken }
            start(h)
            activity.moveToState(Lifecycle.State.CREATED)
            waitFor("Live output is represented by the framework session") {
                playback(controller).state == PlaybackState.STATE_PLAYING
            }
            var existing: LetterSessionService? = null
            main {
                existing = owner(h.host)
                assertEquals(0L, playback(controller).actions and PlaybackState.ACTION_PLAY)
                controller.transportControls.pause()
            }
            waitFor("Framework pause publishes the current resume capability") {
                h.host.mediaState.value.resumeToken != null &&
                    playback(controller).customActions.any { it.action.startsWith(LetterMediaBrowserService.RESUME_PREFIX) }
            }
            var firstAction = ""
            main {
                firstAction = playback(controller).customActions.single {
                    it.action.startsWith(LetterMediaBrowserService.RESUME_PREFIX)
                }.action
                assertEquals(LetterMediaBrowserService.RESUME_PREFIX + h.host.mediaState.value.resumeToken, firstAction)
                assertEquals(PlaybackState.STATE_PAUSED, playback(controller).state)
                assertSame(existing, owner(h.host))
                assertEquals(stableToken, browser.sessionToken)
                h.assertSilentPorts()
                controller.transportControls.play()
                controller.transportControls.playFromMediaId("first_jump", null)
                controller.transportControls.playFromSearch("펭귄", null)
            }
            settleMediaDispatch()
            main {
                assertEquals(1, h.player.parts.size)
                assertNotNull(h.host.mediaState.value.resumeToken)
                assertEquals(PlaybackState.STATE_PAUSED, playback(controller).state)
                h.assertSilentPorts()
                controller.transportControls.sendCustomAction(firstAction, null)
            }
            waitFor("Only the nonce command resumes the already owned letter") {
                h.player.parts.size == 2 && playback(controller).state == PlaybackState.STATE_PLAYING &&
                    h.host.mediaState.value.resumeToken == null
            }
            main {
                assertSame(existing, owner(h.host))
                assertEquals(stableToken, browser.sessionToken)
                controller.transportControls.sendCustomAction(firstAction, null)
                controller.transportControls.pause()
            }
            waitFor("Second framework pause publishes a fresh nonce") {
                h.host.mediaState.value.resumeToken != null &&
                    playback(controller).customActions.any {
                        it.action.startsWith(LetterMediaBrowserService.RESUME_PREFIX) && it.action != firstAction
                    }
            }
            var secondAction = ""
            main {
                secondAction = playback(controller).customActions.single {
                    it.action.startsWith(LetterMediaBrowserService.RESUME_PREFIX)
                }.action
                controller.transportControls.sendCustomAction(firstAction, null)
            }
            settleMediaDispatch()
            main {
                assertEquals(2, h.player.parts.size)
                assertEquals(PlaybackState.STATE_PAUSED, playback(controller).state)
                assertEquals(LetterMediaBrowserService.RESUME_PREFIX + h.host.mediaState.value.resumeToken, secondAction)
                h.assertSilentPorts()
                controller.transportControls.sendCustomAction(secondAction, null)
            }
            waitFor("Fresh capability resumes the same question again") { h.player.parts.size == 3 && h.player.playing }
            main { repeat(2) { h.player.parts.last().finish() } }
            waitFor("Actual controlled input maps to PAUSED with no resume action") {
                h.mic.active && h.mic.asks.size == 1 &&
                    h.host.mediaState.value.transport?.phase == LetterTransportPhase.INPUT &&
                    playback(controller).state == PlaybackState.STATE_PAUSED &&
                    playback(controller).customActions.none { it.action.startsWith(LetterMediaBrowserService.RESUME_PREFIX) }
            }
            main {
                assertSame(existing, owner(h.host))
                assertFalse(h.player.playing)
                assertEquals(0L, playback(controller).actions and PlaybackState.ACTION_PLAY)
                controller.transportControls.play()
            }
            settleMediaDispatch()
            main {
                assertEquals(4, h.player.parts.size)
                assertTrue(h.mic.active)
                controller.transportControls.stop()
            }
            waitFor("Framework stop releases the private owner and clears the projection") {
                owner(h.host) == null && !h.host.mediaState.value.owned &&
                    playback(controller).state == PlaybackState.STATE_STOPPED
            }
            main {
                assertEquals(4, h.player.parts.size)
                assertNull(h.host.mediaState.value.transport)
                assertNull(h.host.mediaState.value.resumeToken)
                assertTrue(playback(controller).customActions.isEmpty())
                h.assertSilentPorts()
            }
        }
    }

    private fun mediaPause(h: LetterHostHarness): String {
        main { h.host.pauseFromMediaControl() }
        waitFor("Media pause retains the owner and publishes a confirmed nonce") {
            owner(h.host) != null && h.session.state.value.turn.state.paused &&
                h.host.mediaState.value.resumeToken != null
        }
        var token: String? = null
        main {
            h.assertSilentPorts()
            assertTrue(h.host.mediaState.value.owned)
            assertEquals(LetterTransportPhase.STOPPED, h.host.mediaState.value.transport?.phase)
            token = h.host.mediaState.value.resumeToken
        }
        return checkNotNull(token)
    }

    @Test fun notificationResumeNonceIsSingleUseAndOldIntentCannotSpendANewPause() = fixture { h, activity ->
        start(h)
        val manager = h.app.getSystemService(NotificationManager::class.java)
        fun resumeIntent(previous: PendingIntent? = null): PendingIntent {
            var found: PendingIntent? = null
            waitFor("Notification exposes the current nonce action after its coalesced update") {
                val notification = manager.activeNotifications.singleOrNull { it.id == 2202 }?.notification
                val candidate = notification?.actions?.singleOrNull {
                    it.title.toString() == "질문 다시 듣기"
                }?.actionIntent
                if (candidate != null && candidate != previous) {
                    val applied = checkNotNull(notification)
                    assertFalse(applied.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
                    assertEquals(Notification.CATEGORY_SERVICE, applied.category)
                    found = candidate
                    true
                } else false
            }
            return checkNotNull(found).also {
                assertEquals(h.app.packageName, it.creatorPackage)
                assertTrue(it.isService)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(it.isImmutable)
            }
        }
        fun pauseFromNotification(): String {
            var pause: PendingIntent? = null
            waitFor("Active notification exposes its explicit pause control") {
                pause = manager.activeNotifications.singleOrNull { it.id == 2202 }?.notification
                    ?.actions?.singleOrNull { it.title.toString() == "잠깐 쉬기" }?.actionIntent
                pause != null
            }
            checkNotNull(pause).send()
            waitFor("Notification pause keeps the owner and publishes a fresh resume nonce") {
                owner(h.host) != null && h.session.state.value.turn.state.paused &&
                    h.host.mediaState.value.resumeToken != null
            }
            var nonce: String? = null
            main {
                assertEquals(LetterTransportPhase.STOPPED, h.host.mediaState.value.transport?.phase)
                h.assertSilentPorts()
                nonce = h.host.mediaState.value.resumeToken
            }
            return checkNotNull(nonce)
        }
        var existing: LetterSessionService? = null
        main { existing = owner(h.host) }
        activity.moveToState(Lifecycle.State.CREATED)
        pauseFromNotification()
        val old = resumeIntent()
        old.send()
        waitFor("Notification resumes only the retained owner") {
            h.player.parts.size == 2 && !h.session.state.value.turn.state.paused &&
                h.host.mediaState.value.resumeToken == null
        }
        main { assertSame(existing, owner(h.host)) }
        val currentNonce = pauseFromNotification()
        val fresh = resumeIntent(old)
        assertNotEquals(old, fresh)
        old.send()
        settleMediaDispatch()
        main {
            assertSame(existing, owner(h.host))
            assertEquals(currentNonce, h.host.mediaState.value.resumeToken)
            assertEquals(2, h.player.parts.size)
            assertTrue(h.session.state.value.turn.state.paused)
            h.assertSilentPorts()
        }
        fresh.send()
        waitFor("Fresh notification intent consumes the current nonce once") {
            h.player.parts.size == 3 && !h.session.state.value.turn.state.paused &&
                h.host.mediaState.value.resumeToken == null
        }
        fresh.send()
        settleMediaDispatch()
        main {
            assertSame(existing, owner(h.host))
            assertEquals(3, h.player.parts.size)
            assertTrue(h.mic.asks.isEmpty())
            assertTrue(h.player.playing)
            assertNull(h.host.mediaState.value.resumeToken)
        }
    }

    @Test fun mediaPauseStopsOutputAndRejectsLateClipCompletion() = fixture { h, _ ->
        start(h)
        lateinit var old: LetterHostHarness.Player.Part
        var existing: LetterSessionService? = null
        main {
            old = h.player.parts.single()
            existing = owner(h.host)
            assertTrue(h.player.playing)
        }
        val token = mediaPause(h)
        main {
            val paused = h.session.state.value.turn.state
            old.finish()
            assertEquals(paused, h.session.state.value.turn.state)
            assertEquals(1, h.player.parts.size)
            assertTrue(h.mic.asks.isEmpty())
            assertSame(existing, owner(h.host))
            assertEquals(token, h.host.mediaState.value.resumeToken)
            h.assertSilentPorts()
        }
    }

    @Test fun mediaResumeWhileHiddenKeepsOwnerAndTheSameQuestion() = fixture { h, activity ->
        start(h)
        var existing: LetterSessionService? = null
        lateinit var question: JumpState
        main {
            existing = owner(h.host)
            question = h.session.state.value.turn.state
        }
        val token = mediaPause(h)
        activity.moveToState(Lifecycle.State.CREATED)
        main {
            h.host.runExplicit(h.session, h.session::resume)
            assertEquals(1, h.player.parts.size) // A hidden UI call grants nothing.
            h.assertSilentPorts()
            assertTrue(h.host.resumeFromMediaControl(token))
            assertSame(existing, owner(h.host))
            assertEquals(question, h.session.state.value.turn.state)
            assertEquals(2, h.player.parts.size)
            assertEquals("splash", h.player.parts.last().id)
            assertNull(h.host.mediaState.value.resumeToken)
            assertTrue(h.host.mediaState.value.owned)
            assertTrue(h.player.playing)
            assertFalse(h.mic.active)
        }
    }

    @Test fun mediaInputPauseClosesCaptureAndRejectsLateFinal() = fixture { h, _ ->
        start(h)
        main { repeat(3) { h.player.parts.last().finish() } }
        waitFor("Controlled answer window is represented as input, never playback") {
            h.mic.asks.size == 1 && h.host.mediaState.value.transport?.phase == LetterTransportPhase.INPUT
        }
        lateinit var old: LetterHostHarness.Mic.Ask
        main {
            old = h.mic.asks.single()
            assertEquals(LetterPhase.LISTENING, h.session.state.value.phase)
            assertNotEquals(LetterTransportPhase.PLAYING, h.host.mediaState.value.transport?.phase)
            assertFalse(h.player.playing)
            assertTrue(h.mic.active)
        }
        val token = mediaPause(h)
        main {
            val paused = h.session.state.value.turn.state
            old.answer(old.ticket, "물소리")
            assertEquals(paused, h.session.state.value.turn.state)
            assertEquals(3, h.player.parts.size)
            assertEquals(1, h.mic.asks.size)
            assertEquals(token, h.host.mediaState.value.resumeToken)
            h.assertSilentPorts()
        }
    }

    @Test fun mediaNonceIsSingleUseAndCannotResumeANewerPause() = fixture { h, _ ->
        start(h)
        val first = mediaPause(h)
        main {
            assertTrue(h.host.resumeFromMediaControl(first))
            assertFalse(h.host.resumeFromMediaControl(first))
            assertEquals(2, h.player.parts.size)
        }
        val second = mediaPause(h)
        main {
            assertNotEquals(first, second)
            assertFalse(h.host.resumeFromMediaControl(first))
            assertFalse(h.host.resumeFromMediaControl(""))
            assertFalse(h.host.resumeFromMediaControl("invented-nonce"))
            assertEquals(second, h.host.mediaState.value.resumeToken)
            assertNotNull(owner(h.host))
            h.assertSilentPorts()
            assertTrue(h.host.resumeFromMediaControl(second))
            assertEquals(3, h.player.parts.size)
            assertNull(h.host.mediaState.value.resumeToken)
        }
    }

    @Test fun repeatedMediaPauseNeverRenewsTheExactExpiryBoundary() = fixture { h, _ ->
        main { h.now = SystemClock.elapsedRealtime() }
        start(h)
        val token = mediaPause(h)
        main {
            val pausedAt = checkNotNull(h.now)
            assertEquals(120_000L, LetterMediaLease.DURATION_MS)
            h.now = pausedAt + 119_999L
            h.host.pauseFromMediaControl()
            assertEquals(token, h.host.mediaState.value.resumeToken)
            assertNotNull(owner(h.host))
            h.assertSilentPorts()
            h.now = pausedAt + 120_000L
            assertFalse(h.host.resumeFromMediaControl(token))
            assertNull(owner(h.host))
            assertFalse(h.host.mediaState.value.owned)
            assertNull(h.host.mediaState.value.resumeToken)
            assertEquals(1, h.player.parts.size)
            h.assertSilentPorts()
        }
    }

    @Test fun mediaPauseSafetyLossRevokesResumeAndRecoveryDoesNotReviveIt() {
        val cases = listOf("permission", "notification", "family", "mode", "deletion", "unknown", "restricted")
        for (case in cases) fixture("media-$case") { h, _ ->
            start(h)
            val token = mediaPause(h)
            main {
                val family = h.state.value
                when (case) {
                    "permission" -> h.permissions = false
                    "notification" -> h.notifications = false
                    "family" -> h.state.value = family.copy(children = listOf(ChildProfile("different", "other", emptySet())))
                    "mode" -> h.state.value = family.copy(voiceInput = false)
                    "deletion" -> h.state.value = family.copy(familyDeletionPending = true)
                    "unknown" -> h.vehicle.emit(VehicleUxSnapshot(VehicleAccessState.UNKNOWN))
                    "restricted" -> h.vehicle.emit(VehicleUxSnapshot(VehicleAccessState.RESTRICTED,
                        VehicleUxRestrictions(true, 0, 100, 5, 2)))
                }
                assertFalse("$case must revoke before resuming any I/O", h.host.resumeFromMediaControl(token))
                assertNull(owner(h.host))
                assertNull(h.host.mediaState.value.resumeToken)
                h.assertSilentPorts()
                h.permissions = true
                h.notifications = true
                h.state.value = family
                h.vehicle.emit(h.vehicle.initialSnapshot)
                assertFalse("$case recovery is not new consent", h.host.resumeFromMediaControl(token))
                assertNull(owner(h.host))
                assertEquals(1, h.player.parts.size)
                assertTrue(h.mic.asks.isEmpty())
                h.assertSilentPorts()
            }
        }
    }

    @Test fun coldAndHardStoppedMediaControlsNeverCreateAnOwner() = fixture { h, _ ->
        main {
            h.host.pauseFromMediaControl()
            h.host.stop()
            assertFalse(h.host.resumeFromMediaControl("invented-nonce"))
            assertNull(owner(h.host))
            assertFalse(h.host.mediaState.value.owned)
            assertNull(h.host.mediaState.value.transport)
            assertTrue(h.player.parts.isEmpty())
            assertTrue(h.mic.asks.isEmpty())
        }
        start(h)
        val token = mediaPause(h)
        main {
            h.host.stop()
            h.host.pauseFromMediaControl()
            assertFalse(h.host.resumeFromMediaControl(token))
            assertNull(owner(h.host))
            assertFalse(h.host.mediaState.value.owned)
            assertNull(h.host.mediaState.value.transport)
            assertNull(h.host.mediaState.value.resumeToken)
            assertEquals(1, h.player.parts.size)
            assertTrue(h.mic.asks.isEmpty())
            h.assertSilentPorts()
        }
    }

    @Test fun hiddenActivityKeepsVoiceAndIntermediateReadyWaitDoNotReleaseTheOwner() = fixture { h, activity ->
        start(h)
        activity.moveToState(Lifecycle.State.CREATED)
        main { repeat(3) { h.player.parts.last().finish() } }
        waitFor("Next answer window while hidden") { h.mic.asks.size == 1 }
        main {
            assertNotNull(owner(h.host))
            assertEquals(LetterPhase.LISTENING,h.session.state.value.phase)
            val ask=h.mic.asks.single(); ask.answer(ask.ticket,"물소리")
        }
        waitFor("Following output retains service") { owner(h.host) != null && h.player.parts.last().id == "water_ack" }
        main { assertEquals(JumpStep.GUESS,h.session.state.value.turn.state.step) }
    }

    @Test fun restrictionAndUnknownImmediatelyStopAndRejectLateCompletions() {
        for (next in listOf(VehicleUxSnapshot(VehicleAccessState.UNKNOWN),
            VehicleUxSnapshot(VehicleAccessState.RESTRICTED,VehicleUxRestrictions(true,0,100,5,2)))) fixture("restriction-${next.access}") { h,_ ->
            start(h)
            main {
                val old=h.player.parts.last()
                h.vehicle.emit(next)
                assertNull(owner(h.host))
                assertTrue(h.session.state.value.turn.state.paused)
                old.finish()
                assertTrue(h.mic.asks.isEmpty())
                h.vehicle.emit(h.vehicle.initialSnapshot)
                assertNull(owner(h.host))
            }
        }
    }

    @Test fun missingPermissionExplainsWhyStartDidNotRun() = fixture { h,_ ->
        focusedAction(h,"Start with missing permission") {
            h.permissions=false
            h.host.runExplicit(h.session) { h.session.choose("open") }
            assertTrue(h.player.parts.isEmpty())
            assertTrue(h.mic.asks.isEmpty())
            assertNull(owner(h.host))
            assertTrue(h.session.state.value.issue!!.contains("권한"))
        }
    }

    @Test fun unavailableStopControlRefusesStartAndExplainsRecovery() = fixture { h,_ ->
        focusedAction(h,"Start without notification controls") {
            h.notifications=false
            h.host.runExplicit(h.session) { h.session.choose("open") }
            assertNull(owner(h.host))
            assertTrue(h.player.parts.isEmpty())
            assertTrue(h.mic.asks.isEmpty())
            assertTrue(h.session.state.value.issue!!.contains("알림"))
            h.notifications=true
            assertNull(owner(h.host))
            assertTrue(h.player.parts.isEmpty())
        }
    }

    @Test fun revokedPermissionStopsWithinTheBoundedWatchAndDoesNotResumeOnGrant() = fixture { h,_ ->
        start(h)
        main { h.permissions = false }
        waitFor("Permission loss releases foreground owner") { owner(h.host) == null }
        main {
            assertTrue(h.session.state.value.turn.state.paused)
            h.permissions = true
            h.player.parts.first().finish()
            assertTrue(h.mic.asks.isEmpty())
        }
    }

    @Test fun familyModeAndOtherAdventureStopTheOldLetter() {
        val changes=listOf<Pair<String,(DeveloperUiState)->DeveloperUiState>>(
            "family" to { it.copy(children=listOf(ChildProfile("different","other",emptySet()))) },
            "mode" to { it.copy(voiceInput=false) }, "busy" to { it.copy(busy=true) },
            "deletion" to { it.copy(familyDeletionPending=true) })
        for ((name,change) in changes) fixture(name) { h,_ ->
            start(h)
            main { h.state.value=change(h.state.value) }
            waitFor("Changed family/mode/other session closes owner") { owner(h.host) == null }
            main { h.player.parts.first().finish(); assertTrue(h.mic.asks.isEmpty()) }
        }
    }

    @Test fun aTransientBusyStateKeepsTheSameRouteTargetResumable() = fixture { h,_ ->
        start(h)
        main { h.state.value=h.state.value.copy(busy=true) }
        waitFor("Busy transition stops the current owner") { owner(h.host)==null }
        focusedAction(h,"Busy recovery gesture") {
            assertTrue(h.session.state.value.turn.state.paused)
            h.host.runExplicit(h.session,h.session::resume)
            assertEquals(1,h.player.parts.size)
            assertNull(owner(h.host))
            h.state.value=h.state.value.copy(busy=false)
            assertEquals(1,h.player.parts.size)
            assertNull(owner(h.host))
            // The existing Compose route still holds this exact session, without a rebind.
            h.host.runExplicit(h.session,h.session::resume)
        }
        waitFor("Same route target resumes after busy settles") {
            owner(h.host)!=null && h.player.parts.size==2
        }
        main {
            assertFalse(h.session.state.value.turn.state.paused)
            assertEquals("splash",h.player.parts.last().id)
        }
    }

    @Test fun cancelledStartCannotLaunchIoOrCancelANewerExplicitStart() = fixture { h,_ ->
        focusedAction(h,"Cancel and start gesture") {
            h.host.runExplicit(h.session) { h.session.choose("open") }
            val field=LetterSessionHost::class.java.getDeclaredField("pending").apply { isAccessible=true }
            val request=checkNotNull(field.get(h.host))
            val token=request.javaClass.getDeclaredField("token").apply { isAccessible=true }.get(request) as String
            h.host.stop()
            h.host.runExplicit(h.session) { h.session.choose("open") }
            h.app.startService(Intent(h.app,LetterSessionService::class.java).setAction(LetterSessionService.ACTION_START)
                .putExtra(LetterSessionService.EXTRA_TOKEN,token))
        }
        waitFor("New explicit request survives stale START") { owner(h.host) != null && h.player.parts.size == 1 }
    }

    @Test fun inactiveVoiceRebindUsesNewProgressAfterModeRoundTrip() = fixture { h,_ ->
        start(h)
        main { h.state.value=h.state.value.copy(voiceInput=false) }
        waitFor("Old voice owner released") { owner(h.host) == null }
        main {
            h.state.value=h.state.value.copy(voiceInput=true)
            val fresh=h.host.bind(JumpEpisode,listOf("letter-test"),JumpState(JumpStep.DIRECT,guess=JumpGuess.PENGUIN,paused=true))
            assertNotSame(h.session,fresh)
            assertEquals(JumpStep.DIRECT,fresh.state.value.turn.state.step)
            assertTrue(fresh.state.value.turn.state.paused)
            assertEquals(1,h.player.parts.size)
        }
    }

    @Test fun notificationPauseRevokesAndAnOldStopCannotStopANewOwner() = fixture { h,_ ->
        start(h)
        val manager=h.app.getSystemService(NotificationManager::class.java)
        waitFor("Control notification") { manager.activeNotifications.any { it.id==2202 } }
        val controls=manager.activeNotifications.single { it.id==2202 }.notification
        assertFalse("Private voice owner must not publish a media-session token",controls.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(Notification.CATEGORY_SERVICE,controls.category)
        val old=controls.actions.single { it.title.toString()=="편지 닫기" }.actionIntent
        old.send()
        waitFor("Explicit close revokes the owner") { owner(h.host)==null && h.session.state.value.turn.state.paused }
        focusedAction(h,"Resume after notification close") { h.host.runExplicit(h.session,h.session::resume) }
        waitFor("Re-promotion") { owner(h.host)!=null && h.player.parts.size==2 }
        old.send()
        SystemClock.sleep(150)
        main { assertNotNull(owner(h.host)); assertFalse(h.session.state.value.turn.state.paused) }
    }
}

internal class LetterHostHarness(val app: Application) {
    lateinit var scenario: ActivityScenario<MainActivity>
    var caseName="single"
    var focusDiagnostic="not observed"
    var paneDiagnostic="not tapped"
    fun diagnostic(): String {
        fun field(name:String)=LetterSessionHost::class.java.getDeclaredField(name).apply { isAccessible=true }.get(host)
        val presentation=if(this::session.isInitialized) session.state.value else null
        val visible=(field("visible") as? java.lang.ref.WeakReference<*>)?.get() as? android.app.Activity
        return "case=$caseName; $focusDiagnostic; $paneDiagnostic; hostVisibleTask=${visible?.taskId}; hostFocus=${visible?.hasWindowFocus()}; " +
            "owner=${field("service")!=null}; pending=${field("pending")!=null}; stopping=${field("stoppingService")!=null}; " +
            "snapshot=${field("snapshot")}; phase=${presentation?.phase}; issue=${presentation?.issue}; " +
            "state=${presentation?.turn?.state}; parts=${player.parts.map { it.id }}; asks=${mic.asks.size}; " +
            "permission=$permissions; controls=${notifications && LetterSessionService.controlsAvailable(app)}; voice=${state.value.voiceInput}; quiet=${state.value.quiet}; busy=${state.value.busy}"
    }
    val state=MutableStateFlow(DeveloperUiState(onboarded=true,voiceInput=true,interactionConfigured=true,
        children=listOf(ChildProfile("letter-test","test",emptySet())), vehicleAccess=VehicleAccessState.UNRESTRICTED))
    val vehicle=Vehicle()
    val player=Player()
    val mic=Mic()
    var permissions=true
    var notifications=true
    var now:Long?=null // Only expiry tests freeze this process-local lease clock.
    var playbackFocusHeld=false
    var captureFocusHeld=false
    fun assertSilentPorts() {
        assertFalse(player.playing)
        assertFalse(mic.active)
        assertFalse(playbackFocusHeld)
        assertFalse(captureFocusHeld)
    }
    lateinit var session: LetterSession<JumpState>
    val host=LetterSessionHost(app,state,vehicle,{permissions},
        { notifications && LetterSessionService.controlsAvailable(app) },
        elapsedRealtime = { now ?: SystemClock.elapsedRealtime() }) { episode,saved,allowed,checkpoint ->
        LetterSession(episode,player,{id->BundledAudio(id,id,"controlled",30_000)},
            object:PlaybackAudioFocus {
                override fun acquire(onLost:()->Unit):Boolean { playbackFocusHeld=true; return true }
                override fun release(){playbackFocusHeld=false}
            },mic,
            { object:CaptureFocus {
                override fun acquire():Boolean { captureFocusHeld=true; return true }
                override fun release(){captureFocusHeld=false}
            } },
            CaptureSignals(object:CaptureSignalOutput {
                override fun ready(){}; override fun processing(){}; override fun stop(){}; override fun release(){}
            }),allowed,checkpoint,LetterMode.VOICE,saved)
    }
    class Vehicle:VehicleAccessProvider {
        override val initialState=VehicleAccessState.UNRESTRICTED
        override val initialSnapshot=VehicleUxSnapshot(initialState,VehicleUxRestrictions(false,0,100,5,2))
        private var callback:((VehicleUxSnapshot)->Unit)?=null
        override fun start(context:Context,changed:(VehicleAccessState)->Unit){changed(initialState)}
        override fun startObserving(context:Context,changed:(VehicleUxSnapshot)->Unit){callback=changed;changed(initialSnapshot)}
        fun emit(value:VehicleUxSnapshot){callback?.invoke(value)}
        override fun close(){callback=null}
    }
    class Player:StoryClipPlayer {
        data class Part(val id:String,val finish:()->Unit)
        val parts=mutableListOf<Part>()
        var playing=false
        private var current:Part?=null
        override fun play(audio:BundledAudio,complete:()->Unit,failure:()->Unit,started:()->Unit){
            lateinit var part:Part
            part=Part(audio.id) {
                if(current===part){current=null;playing=false}
                complete() // Deliberately deliver stale callbacks to exercise session fencing.
            }
            current=part; playing=true; parts+=part; started()
        }
        override fun positionMillis():Long?=1L
        override fun stop(){current=null;playing=false}; override fun shutdown(){stop()}
    }
    class Mic:SpeechCaptureInput {
        data class Ask(val ticket:CaptureTicket,val answer:(CaptureTicket,String)->Unit)
        val asks=mutableListOf<Ask>()
        var active=false
        override val available=true
        override val source="controlled-test"
        override fun unavailableReason(checkPermission:Boolean):String?=null
        override fun open(ticket:CaptureTicket,onStage:(CaptureTicket,CaptureStage)->Unit,onFinal:(CaptureTicket,String)->Unit,onFailure:(CaptureTicket,SpeechInputFailure)->Unit){active=true;asks+=Ask(ticket,onFinal);onStage(ticket,CaptureStage.LISTENING)}
        override fun close(){active=false}
    }
}

class LetterHostTestRunner:AndroidJUnitRunner() {
    override fun newApplication(cl:ClassLoader,className:String,context:Context):Application {
        check(context.packageName=="dev.carplaymate.demo.demo")
        check(className==OnuApplication::class.java.name)
        return super.newApplication(cl,LetterHostTestApplication::class.java.name,context)
    }
    override fun onCreate(arguments:Bundle) {
        check(arguments.getString("letterHostTests")=="true")
        check(arguments.getString("class")==LetterHostServiceTest::class.java.name)
        super.onCreate(arguments)
    }
}
class LetterHostTestApplication:OnuApplication() {
    internal var fixture:LetterHostHarness?=null
    internal override val letterSessionHost:LetterSessionHost get()=checkNotNull(fixture).host
}