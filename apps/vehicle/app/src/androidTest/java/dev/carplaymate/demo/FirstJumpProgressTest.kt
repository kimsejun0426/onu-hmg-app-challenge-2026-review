package dev.carplaymate.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FirstJumpProgressTest {
    @Test fun aClosedOrChangedFamilyCannotOverwriteTheCurrentLetter() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "first_jump_test_" + UUID.randomUUID()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
        val prefs = context.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
        val family = FamilyPreferences(context)
        val progress = FirstJumpProgress(context)
        fun ids() = requireNotNull(family.profile).children.joinToString(",") { it.id }
        val oldTurn = JumpState(JumpStep.QUESTION, JumpGuess.PENGUIN, paused = true).letterCheckpoint()
        val newTurn = JumpState(JumpStep.DIRECT, JumpGuess.BOTH).letterCheckpoint()
        try {
            assertTrue(family.save(FamilyProfile("바다")))
            val single = ids()
            progress.save(single, oldTurn)
            assertEquals(oldTurn, FirstJumpProgress(context).read(single))
            val ciphertext = requireNotNull(prefs.getString("first_jump_progress_v1", null))
            assertFalse(ciphertext.contains("QUESTION"))

            assertTrue(family.save(FamilyProfile("바다", "구름")))
            val pair = ids()
            assertNull(progress.read(single))
            assertNull(progress.read(pair))
            progress.save(pair, newTurn)
            progress.save(single, oldTurn)
            progress.save(pair, "이건 아이가 말한 문장이야.")
            assertEquals(newTurn, FirstJumpProgress(context).read(pair))

            prefs.edit().putString("first_jump_progress_v1", "invalid").commit()
            assertNull(progress.read(pair))
            progress.save(pair, newTurn)
            assertEquals(newTurn, progress.read(pair))
            assertTrue(family.clear())
            progress.save(pair, newTurn)
            assertFalse(prefs.contains("first_jump_progress_v1"))
            assertNull(progress.read(pair))
        } finally {
            base.deleteSharedPreferences(prefix + "family_v1")
        }
    }
}
