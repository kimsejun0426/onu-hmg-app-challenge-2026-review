package dev.carplaymate.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RainLetterProgressTest {
    @Test fun aLegacyEndingDeliversOneReplyAndBothLettersRetainTheirOwnPosition() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "rain_progress_test_" + UUID.randomUUID()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
        val family = FamilyPreferences(context)
        val first = FirstJumpProgress(context)
        val rain = FirstJumpProgress(context, "rain_music")
        val prefs = context.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
        try {
            assertTrue(family.save(FamilyProfile("구름")))
            val id = requireNotNull(family.profile).children.single().id
            val original = JumpState(JumpStep.END, JumpGuess.PENGUIN, JumpEnding.BOLD).letterCheckpoint()
            first.save(id, original)
            val source = requireNotNull(restoreLetter(FirstJumpProgress(context).read(id)))
            val delivered = RainGame.initial(source.ending).state.rainCheckpoint()
            assertEquals(delivered, rain.initialize(id, delivered))
            first.save(id, JumpState().letterCheckpoint())
            assertEquals(JumpEnding.BOLD, restoreRain(rain.read(id))!!.previousJump)
            val incoming = RainState(RainStep.TEMPO, JumpEnding.BOLD, paused = true).rainCheckpoint()
            rain.save(id, incoming)
            first.save(id, original)
            assertEquals(incoming, rain.initialize(id, RainGame.initial(JumpEnding.SOFT).state.rainCheckpoint()))
            assertEquals(original, first.read(id))
            assertEquals(incoming, FirstJumpProgress(context, "rain_music").read(id))
            assertFalse(prefs.getString("rain_music_progress_v1", "")!!.contains("TEMPO"))
            family.clear()
            assertNull(rain.read(id))
            assertNull(first.read(id))
        } finally { prefs.edit().clear().commit() }
    }
}
