package dev.carplaymate.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstJumpGameTest {
    @Test fun slowButAdvancingPlaybackCanOutliveItsNominalDuration() {
        val started = 15_000L
        val progress = LetterPlaybackProgress(11_338L, started)
        for (second in 1L..36L) {
            assertFalse(progress.stalled(started + second * 1_000L, second * 300L))
        }
        assertFalse(progress.stalled(started + 38_000L, 11_338L))
        // Reaching the final position still leaves time for the native completion callback.
        assertFalse(progress.stalled(started + 39_000L, 11_338L))
        assertTrue(progress.stalled(started + 38_000L + LetterPlaybackProgress.STALL_MS, 11_999L))
    }

    @Test fun stoppedOrMissingPositionsCannotKeepAudioFocusIndefinitely() {
        val started = 5_000L
        val progress = LetterPlaybackProgress(11_338L, started)
        assertFalse(progress.stalled(started + 1_000L, 300L))
        assertFalse(progress.stalled(started + 2_000L, 290L))
        assertTrue(progress.stalled(started + 1_000L + LetterPlaybackProgress.STALL_MS, null))
        val unknown = LetterPlaybackProgress(11_338L, started)
        assertFalse(unknown.stalled(started + 11_338L, null))
        assertTrue(unknown.stalled(started + 11_338L + LetterPlaybackProgress.STALL_MS, null))
    }

    private fun hear(turn: JumpTurn, text: String?) = FirstJumpGame.hear(turn.state, text)
    private fun choose(turn: JumpTurn, id: String) = FirstJumpGame.choose(turn.state, id)
    private fun open() = choose(FirstJumpGame.initial(), "open")

    @Test fun spokenAndButtonPathsBothDeliverTheFixedSenderAndDifferentActualFinales() {
        val start = open()
        assertEquals(listOf("opening", "splash", "water_q"), start.cues)
        val guess = hear(start, "물속에 뛰어들었어!")
        assertEquals(choose(start, "wet"), guess)
        val otherGuess = hear(guess, "해달일 것 같아.")
        assertEquals(JumpGuess.OTTER, otherGuess.state.guess)
        val evidence = hear(otherGuess, "날개로 헤엄쳐?")
        assertEquals(JumpStep.IDENTIFY, evidence.state.step)
        assertTrue("feather_evidence" in evidence.cues)
        val direct = hear(evidence, "펭귄이야!")
        assertEquals(JumpStep.DIRECT, direct.state.step)
        assertEquals(JumpGuess.OTTER, direct.state.guess)
        val soft = hear(direct, "살금살금 가다가 크게 첨벙!")
        assertEquals(JumpEnding.SOFT, soft.state.ending)
        assertEquals(listOf("finale_soft"), soft.cues)
        val bold = hear(choose(soft, "remix"), "성큼성큼, 작게 퐁당!")
        assertEquals(JumpEnding.BOLD, bold.state.ending)
        assertEquals(listOf("finale_bold"), bold.cues)
        assertFalse(bold.acceptsAnswer)
        assertEquals(FirstJumpGame.initial(), choose(bold, "restart"))
    }

    @Test fun earlyGuessIsRememberedAndCombinedCorrectEvidenceSkipsRedundantQuestion() {
        val start = open()
        val early = hear(start, "펭귄 아닐까?")
        assertEquals(JumpStep.QUESTION, early.state.step)
        assertEquals(JumpGuess.PENGUIN, early.state.guess)
        assertTrue("candidates" in early.cues)
        assertFalse("guess_q" in early.cues)
        for (turn in listOf(start, hear(start, "물소리"), early)) {
            val fast = hear(turn, "펭귄! 깃털이 있는지 물어봐.")
            assertEquals(JumpStep.DIRECT, fast.state.step)
            assertEquals(JumpGuess.PENGUIN, fast.state.guess)
            assertTrue(fast.cues.indexOf("feather_evidence") >= 0)
            assertTrue(fast.cues.indexOf("feather_evidence") < fast.cues.indexOf("reveal"))
            assertFalse("who_q" in fast.cues)
        }
        assertEquals(JumpStep.DIRECT, hear(early, "깃털이 있으니까 펭귄일 것 같아.").state.step)
        val revised = hear(early, "해달 같아.")
        assertEquals(JumpGuess.OTTER, revised.state.guess)
        assertEquals(JumpStep.IDENTIFY, revised.state.step)
        // A guess supported by fur needs a chance to change after hearing the evidence.
        assertEquals(JumpStep.IDENTIFY, hear(early, "펭귄이야, 털이 있어.").state.step)
    }

    @Test fun otherCandidatesAndGenuinelyAmbiguousSwimmingProduceNoFalseAnswer() {
        val step = hear(open(), "뭔가 떨어졌어.")
        val outside = hear(step, "오리도 헤엄쳐!")
        assertEquals(JumpGuess.OTHER, outside.state.guess)
        assertTrue("candidates" in outside.cues)
        assertFalse("reveal" in outside.cues)
        val possible = hear(step, "둘 다 헤엄치잖아!")
        assertEquals(JumpGuess.BOTH, possible.state.guess)
        val weak = hear(possible, "수영 잘해?")
        assertEquals(JumpStep.QUESTION, weak.state.step)
        assertEquals(listOf("weak_shared"), weak.cues)
        val again = hear(weak, "헤엄을 잘 치니?")
        assertEquals(JumpStep.IDENTIFY, again.state.step)
        val otter = hear(again, "해달 아닐까?")
        assertEquals(JumpStep.IDENTIFY, otter.state.step)
        assertTrue("feather_evidence" in otter.cues)
        assertEquals(JumpStep.DIRECT, hear(otter, "잘 모르겠어").state.step)
    }

    @Test fun unknownWordsNegationsAndContradictoryDirectionsNeverAwardAChoice() {
        val start = open()
        for (fragment in listOf("선물", "건물에 들어갔어", "펭귄 아니야", "펭귄은 아닌 것 같아", "다시 말고 물", "펭귄털이없어")) {
            val miss = hear(start, fragment)
            assertEquals(JumpStep.WATER, miss.state.step)
            assertTrue("unheard" in miss.cues)
            assertEquals(null, miss.state.guess)
            assertFalse(miss.text.contains(fragment))
        }
        val direct = hear(start, "펭귄? 깃털이 있어?")
        val clash = hear(direct, "성큼성큼 크게 첨벙")
        assertEquals(JumpStep.DIRECT, clash.state.step)
        assertEquals(null, clash.state.ending)
    }

    @Test fun hintsReplaysAndTwoSilentTurnsHaveDistinctBoundedOutcomes() {
        val start = open()
        val replay = hear(start, "오누야, 다시 들려 줘.")
        assertEquals(start.state, replay.state)
        assertTrue("splash" in replay.cues)
        assertFalse("opening" in replay.cues)
        val help = hear(start, "힌트 줘")
        assertTrue("water_hint" in help.cues && "splash" in help.cues)
        val firstSilence = hear(start, null)
        assertTrue(firstSilence.state.assisted)
        assertTrue("water_hint" in firstSilence.cues)
        val quietEnding = hear(firstSilence, " ")
        assertEquals(JumpStep.END, quietEnding.state.step)
        assertFalse(quietEnding.acceptsAnswer)
        assertTrue("reveal" in quietEnding.cues)
        assertEquals("finale_soft", quietEnding.cues.last())
        val recovered = hear(hear(start, "타닥타닥처럼 웬 이상한 말"), "물에 빠졌어.")
        assertEquals(JumpStep.GUESS, recovered.state.step)
        assertFalse(recovered.state.assisted)
    }

    @Test fun repeatingHelpMovesPastTheSplashAndFinishesTheBroadcast() {
        val firstHint = hear(open(), "잘 모르겠어")
        assertEquals(JumpStep.WATER, firstHint.state.step)
        assertTrue("water_hint" in firstHint.cues && "splash" in firstHint.cues)
        val replay = hear(firstHint, "다시 들려줘")
        assertEquals(firstHint.state, replay.state)
        assertTrue("splash" in replay.cues)
        val identifiedWater = hear(replay, "잘 모르겠어")
        assertEquals(JumpStep.GUESS, identifiedWater.state.step)
        assertEquals(listOf("water_ack", "candidates", "guess_q"), identifiedWater.cues)
        assertFalse(identifiedWater.state.assisted)

        val directing = hear(identifiedWater, "펭귄! 깃털이 있어?")
        val offered = hear(directing, "잘 모르겠어")
        assertEquals(JumpStep.DIRECT, offered.state.step)
        assertEquals(listOf("direct_q"), offered.cues)
        val broadcast = hear(offered, "힌트")
        assertEquals(JumpStep.END, broadcast.state.step)
        assertEquals(JumpEnding.SOFT, broadcast.state.ending)
        assertEquals(listOf("finale_soft"), broadcast.cues)
        assertFalse(broadcast.acceptsAnswer)
    }

    @Test fun pauseRestoreAndOutOfTurnRecognitionCannotAdvanceTheStory() {
        val guess = hear(hear(open(), "발소리가 들렸어"), "해달 같아")
        val quiet = hear(guess, "오누야, 쉬자")
        assertTrue(quiet.state.paused)
        assertFalse(quiet.acceptsAnswer)
        assertTrue(quiet.cues.isEmpty())
        assertEquals(quiet, FirstJumpGame.resume(quiet.state))
        assertEquals(quiet, hear(quiet, "깃털이 있어?"))
        val resumed = choose(quiet, "resume")
        assertEquals(guess.state, resumed.state)
        assertEquals(listOf("ask_question"), resumed.cues)
        assertEquals(FirstJumpGame.initial(), FirstJumpGame.hear(JumpState(), "펭귄"))
    }

    @Test fun actualDialogueSampleAndCommonAsrSpellingPlayFromStartToFinish() {
        for (clue in listOf("그 친구 몸에 깃털이 있어?", "기털이 있어")) {
            val observation = hear(open(), "이건 물에 뛰어든 소리 같아")
            assertEquals(JumpStep.GUESS, observation.state.step)
            val hypothesis = hear(observation, "펭귄 같아")
            assertEquals(JumpStep.QUESTION, hypothesis.state.step)
            val question = hear(hypothesis, clue)
            assertEquals(JumpStep.IDENTIFY, question.state.step)
            val sender = hear(question, "펭귄이다!")
            assertEquals(JumpStep.DIRECT, sender.state.step)
            val final = hear(sender, "살금살금, 그러다 크게 첨벙!")
            assertEquals(JumpEnding.SOFT, final.state.ending)
            assertFalse(final.acceptsAnswer)
        }
        assertEquals(JumpStep.WATER, hear(open(), "이건 물에 뛰어든 소리가 아니야").state.step)
        val question = hear(hear(open(), "첨벙"), "둘 다 가능해")
        assertTrue("unheard" in hear(question, "그 친구 몸에 깃털이 없대").cues)
    }

    @Test fun captionsAreAvailableForEveryCueReachableThroughUI() {
        val pending = ArrayDeque<JumpTurn>()
        val visited = mutableSetOf<JumpState>()
        pending.add(FirstJumpGame.initial())
        while (pending.isNotEmpty()) {
            val turn = pending.removeFirst()
            turn.cues.forEach { assertTrue(FirstJumpGame.caption(it).isNotBlank()) }
            if (!visited.add(turn.state)) continue
            val actions = turn.choices.map { it.id } + if (turn.acceptsAnswer) listOf("hint", "again", "rest") else emptyList()
            actions.forEach { pending.add(choose(turn, it)) }
        }
        assertTrue(visited.any { it.ending == JumpEnding.BOLD } && visited.any { it.ending == JumpEnding.SOFT })
    }
}
