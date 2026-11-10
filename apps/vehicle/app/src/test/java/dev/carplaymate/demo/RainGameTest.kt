package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class RainGameTest {
    private fun choose(turn: RainTurn, id: String) = RainGame.choose(turn.state, id)
    private fun hear(turn: RainTurn, text: String?) = RainGame.hear(turn.state, text)
    private fun open(memory: JumpEnding? = null) = choose(RainGame.initial(memory), "open")
    private fun wiper() = hear(open(), "빗방울이 톡톡 떨어졌어")
    private fun tempo() = hear(wiper(), "유리 닦는 와이퍼야")
    private fun direct() = hear(tempo(), "나중에 둘 다 빨라졌어")

    @Test fun theChildCanFinishWithTheSpokenExampleAndDirectADifferentOpeningOnRemix() {
        val first = open()
        assertEquals(listOf("opening", "rain_drops", "drops_q"), first.cues)
        val second = hear(first, "빗방울이 톡톡 떨어졌어")
        assertEquals(RainStep.WIPER, second.state.step)
        assertEquals(listOf("rain_ack", "wiper", "wiper_q"), second.cues)
        val pattern = hear(second, "유리 닦는 와이퍼야")
        assertEquals(RainStep.TEMPO, pattern.state.step)
        assertEquals(listOf("wiper_ack", "rain_beat", "tempo_q"), pattern.cues)
        val director = hear(pattern, "나중에 둘 다 빨라졌어")
        assertEquals(RainStep.DIRECT, director.state.step)
        val rainMusic = hear(director, "톡톡 먼저, 그다음 슥삭!")
        assertEquals(RainStep.END, rainMusic.state.step)
        assertEquals(RainEnding.DROPS, rainMusic.state.ending)
        assertEquals(listOf("finale_drops"), rainMusic.cues)
        val wiperMusic = hear(choose(rainMusic, "remix"), "와이퍼 먼저, 빗방울 나중")
        assertEquals(RainEnding.WIPER, wiperMusic.state.ending)
        assertEquals(listOf("finale_wiper"), wiperMusic.cues)
        assertFalse(wiperMusic.acceptsAnswer)
    }

    @Test fun aReturningFamilyHearsOnlyItsActualPriorBroadcast() {
        for ((jump,cue) in listOf(JumpEnding.SOFT to "memory_soft", JumpEnding.BOLD to "memory_bold")) {
            val entered = open(jump)
            assertEquals(listOf("opening", cue, "rain_drops", "drops_q"), entered.cues)
            val readyAgain = choose(choose(hear(entered, "빗방울이랑 와이퍼가 점점 빨라졌어"), "drops"), "restart")
            assertEquals(RainStep.READY, readyAgain.state.step)
            assertEquals(jump, readyAgain.state.previousJump)
        }
        assertFalse(open().cues.any { it.startsWith("memory_") })
    }

    @Test fun prematureButSpecificPredictionsAreVerifiedByPlayingTheActualClues() {
        val both = hear(open(), "빗방울이랑 와이퍼 소리 같아")
        assertEquals(RainStep.TEMPO, both.state.step)
        assertEquals(listOf("rain_ack", "wiper", "wiper_ack", "rain_beat", "tempo_q"), both.cues)
        val all = hear(open(), "빗방울이랑 와이퍼가 점점 빨라졌어")
        assertEquals(RainStep.DIRECT, all.state.step)
        assertTrue(all.cues.indexOf("wiper") < all.cues.indexOf("rain_beat"))
        assertTrue(all.cues.indexOf("rain_beat") < all.cues.indexOf("tempo_ack"))
        assertFalse("wiper_q" in all.cues || "tempo_q" in all.cues)
        val fastWiper = hear(wiper(), "와이퍼가 나중에 더 빨라졌어")
        assertEquals(RainStep.DIRECT, fastWiper.state.step)
        assertTrue("rain_beat" in fastWiper.cues)
    }

    @Test fun wrongHypothesesGetOneRelevantHintAndEachDoubleHelpAdvances() {
        val mistake = hear(open(), "와이퍼 같아")
        assertEquals(RainStep.DROPS, mistake.state.step)
        assertTrue("rain_hint" in mistake.cues)
        val assistedRain = hear(mistake, "잘 모르겠어")
        assertEquals(RainStep.WIPER, assistedRain.state.step)
        assertTrue("rain_ack" in assistedRain.cues)
        val helpedWiper = hear(hear(assistedRain, "힌트"), "힌트 줘")
        assertEquals(RainStep.TEMPO, helpedWiper.state.step)
        val mistakeTempo = hear(helpedWiper, "소리가 더 커졌어")
        assertEquals(RainStep.TEMPO, mistakeTempo.state.step)
        assertTrue("tempo_hint" in mistakeTempo.cues && "rain_beat" in mistakeTempo.cues)
        val moved = hear(mistakeTempo, "더 느려졌어")
        assertEquals(RainStep.DIRECT, moved.state.step)
        assertTrue("tempo_ack" in moved.cues)
        assertEquals(RainEnding.DROPS, hear(hear(moved,"몰라"),"오누가 해 줘").state.ending)
    }

    @Test fun spokenAliasesAndButtonsAgreeWhileNegationIsNeverCredited() {
        for (w in listOf("톡톡", "비 오는 소리", "오누야, 물방울!")) assertEquals(choose(open(),"drops"), hear(open(),w))
        for (w in listOf("쓱싹", "슥삭", "앞유리 닦는 거")) assertEquals(choose(wiper(),"wiper"), hear(wiper(),w))
        for (w in listOf("빨리", "소리 사이가 짧아졌어", "간격이 좁아졌어", "처음보다 더 빨라졌어")) {
            assertEquals(choose(tempo(),"faster"), hear(tempo(),w))
        }
        for (w in listOf("비밀", "비행기 소리", "빗방울은 아니야", "와이퍼는 아닌 것 같아")) {
            val unsupported = hear(open(),w)
            assertEquals(RainStep.DROPS, unsupported.state.step)
            assertTrue("unheard" in unsupported.cues)
            assertFalse(w in unsupported.text)
        }
        assertEquals(RainEnding.WIPER, hear(direct(), "와이퍼가 먼저").state.ending)
        assertEquals(RainEnding.DROPS, hear(direct(), "빗방울 소리부터").state.ending)
        assertTrue(hear(open(), "오누야, 잠깐 쉬자").state.paused)
        assertFalse(hear(open(), "잠깐 쉬자는 건 아니야").state.paused)
        assertEquals(RainStep.TEMPO, hear(tempo(),"빨라진 건 아니야").state.step)
        assertEquals(RainStep.DIRECT, hear(direct(),"와이퍼 말고 빗방울도 아니야").state.step)
    }

    @Test fun deliberateReplayRetainsTheHintAndSilenceNeverOpensAnEndlessLoop() {
        val firstSilence = hear(tempo(),null)
        assertTrue(firstSilence.state.assisted)
        val replay = hear(firstSilence,"다시 들려 줘")
        assertEquals(firstSilence.state,replay.state)
        assertEquals(listOf("repeat_prompt","rain_beat","tempo_q"),replay.cues)
        val ending = hear(replay," ")
        assertFalse(ending.acceptsAnswer)
        assertEquals(listOf("tempo_ack","finale_drops"),ending.cues)
        val unheard = hear(wiper(),"그건 내가 모르던 물건이야")
        assertTrue("unheard" in unheard.cues)
        assertEquals(RainStep.TEMPO, hear(unheard,"다른 표현").state.step)
    }

    @Test fun checkpointContainsOnlyEnumsAndRestoringDoesNotStartTheMicrophone() {
        val heard = hear(open(JumpEnding.BOLD), "비 오는 소리")
        val paused = hear(heard,"오누야, 쉬자")
        assertEquals("WIPER|BOLD||false|true",paused.state.rainCheckpoint())
        val restored = RainGame.resume(restoreRain(paused.state.rainCheckpoint())!!)
        assertEquals(paused,restored)
        assertFalse(restored.acceptsAnswer)
        assertTrue(restored.cues.isEmpty())
        assertEquals(restored, hear(restored,"와이퍼야"))
        val resumed = choose(restored,"resume")
        assertEquals(heard.state,resumed.state)
        assertEquals(listOf("wiper","wiper_q"),resumed.cues)
        for (invalid in listOf(null,"","CHILD|나의말|||","END|SOFT||false|false","READY||WIPER|false|false",
            "READY|||true|false","END||DROPS|false|true","WATER|||false|false","WIPER|||false|1")) {
            assertNull(restoreRain(invalid))
        }
        assertFalse(RainGame.initial().acceptsAnswer)
        assertEquals(RainGame.initial(),hear(RainGame.initial(),"톡톡"))
    }

    @Test fun readingCluesShowSoundAndTimingWithoutGivingAwayTheAnswer() {
        assertEquals(setOf("rain_drops","wiper","rain_beat"),
            listOf("opening","rain_drops","wiper","rain_beat","drops_q","finale_drops","finale_wiper").filter(RainGame::isEffect).toSet())
        for (id in listOf("rain_drops","wiper","rain_beat")) {
            val text=RainGame.caption(id)
            assertFalse("빗방울" in text || "와이퍼" in text || "빨라" in text)
        }
    }
}
