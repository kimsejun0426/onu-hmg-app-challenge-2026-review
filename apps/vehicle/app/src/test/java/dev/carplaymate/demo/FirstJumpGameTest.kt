package dev.carplaymate.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstJumpGameTest {
    @Test fun bothOfferedQuestionsAcceptNaturalRequestsWithoutMatchingNegation() {
        val question = hear(hear(open(), "물소리"), "해달")
        for (topic in listOf("깃털이 있는지", "날개로 헤엄치는지")) {
            for (request in listOf("물어보자", "물어봐", "물어보면 어때", "물어볼래")) {
                val response = hear(question, "$topic $request")
                assertEquals("$topic $request", JumpStep.IDENTIFY, response.state.step)
                assertTrue("feather_evidence" in response.cues)
                assertFalse("unheard" in response.cues)
            }
        }
        for (rejected in listOf("날개로 헤엄치는지 물어보지 마", "깃털이 있는지 물어보지 마",
                "날개로 헤엄치는 건 아니야", "깃털은 없어")) {
            val response = hear(question, rejected)
            assertEquals(rejected, JumpStep.QUESTION, response.state.step)
            assertTrue("unheard" in response.cues)
        }
    }

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
            assertTrue(fast.cues.indexOf("feather_evidence") < fast.cues.indexOf("reveal_penguin"))
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
        assertFalse(outside.cues.any { it.startsWith("reveal") })
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
        val quiet = hear(firstSilence, " ")
        assertEquals(JumpStep.WATER, quiet.state.step)
        assertTrue(quiet.state.paused)
        assertFalse(quiet.acceptsAnswer)
        assertEquals(null, quiet.state.ending)
        assertTrue(quiet.cues.isEmpty())
        val recovered = hear(hear(start, "타닥타닥처럼 웬 이상한 말"), "물에 빠졌어.")
        assertEquals(JumpStep.GUESS, recovered.state.step)
        assertFalse(recovered.state.assisted)
    }

    @Test fun repeatingHelpMovesPastCluesButDoesNotChooseTheChildsBroadcast() {
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
        val quiet = hear(offered, "힌트")
        assertEquals(JumpStep.DIRECT, quiet.state.step)
        assertEquals(null, quiet.state.ending)
        assertTrue(quiet.state.paused)
        assertTrue(quiet.cues.isEmpty())
        assertFalse(quiet.acceptsAnswer)
        val broadcast = hear(choose(quiet, "resume"), "작게 퐁당")
        assertEquals(JumpEnding.BOLD, broadcast.state.ending)
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

    @Test fun theRevealAnswersTheChildsEarlierGuessBeforeInvitingTheBroadcast() {
        for ((guess, cue) in listOf(
            "penguin" to "reveal_penguin",
            "otter" to "reveal_otter",
            "both" to "reveal_unsure",
            "outside" to "reveal_unsure",
        )) {
            val prediction = choose(choose(open(), "wet"), guess)
            val clue = choose(prediction, "feathers")
            val director = choose(clue, "penguin")
            assertEquals(listOf(cue, "direct_q"), director.cues)
            assertEquals(prediction.state.guess, director.state.guess)
            assertEquals("펭귄", director.answerLabel)
            val restored = FirstJumpGame.resume(restoreLetter(director.state.letterCheckpoint())!!)
            assertEquals(director.cues, restored.cues)
            assertEquals(null, restored.answerLabel)
            val complete = choose(director, "bold")
            assertEquals(JumpEnding.BOLD, complete.state.ending)
            assertEquals(listOf("direct_q"), choose(complete, "remix").cues)
        }
        val opening = open().text
        assertTrue(opening.contains("어제는 무서워서 못 뛰었대"))
        assertTrue(opening.contains("첫 점프를 같이 중계해 주자"))
    }

    @Test fun silenceAtEveryQuestionPreservesTheUnfinishedStoryAndNeverCreatesAReplyMemory() {
        for (step in listOf(JumpStep.WATER, JumpStep.GUESS, JumpStep.QUESTION, JumpStep.IDENTIFY, JumpStep.DIRECT)) {
            val original = JumpState(step = step, guess = JumpGuess.OTTER)
            val invited = FirstJumpGame.hear(original, null)
            val quiet = hear(invited, null)
            assertEquals(step, quiet.state.step)
            assertEquals(JumpGuess.OTTER, quiet.state.guess)
            assertEquals(null, quiet.state.ending)
            assertEquals(null, quiet.answerLabel)
            assertTrue(quiet.state.paused)
            assertTrue(quiet.cues.isEmpty())
            assertFalse(quiet.acceptsAnswer)
            val restored = restoreLetter(quiet.state.letterCheckpoint())!!
            assertEquals(quiet.state, restored)
            val resumed = FirstJumpGame.choose(restored, "resume")
            assertEquals(step, resumed.state.step)
            assertEquals(null, resumed.answerLabel)
            assertTrue(resumed.acceptsAnswer)
        }
    }

    @Test fun aSecondUnrecognizedGuessPausesWithoutInventingBothCandidates() {
        val guessing = choose(open(), "wet")
        val first = hear(guessing, "부릉부릉")
        assertEquals(JumpStep.GUESS, first.state.step)
        assertTrue(first.state.assisted)
        assertFalse(first.state.paused)
        assertEquals(null, first.state.guess)
        assertEquals(null, first.answerLabel)
        assertTrue("unheard" in first.cues)

        val quiet = hear(first, "아직 모르는 소리야")
        assertEquals(JumpStep.GUESS, quiet.state.step)
        assertEquals(null, quiet.state.guess)
        assertEquals(null, quiet.state.ending)
        assertEquals(null, quiet.answerLabel)
        assertTrue(quiet.state.paused)
        assertTrue(quiet.cues.isEmpty())
        assertFalse(quiet.acceptsAnswer)
        val restored = restoreLetter(quiet.state.letterCheckpoint())!!
        assertEquals(quiet.state, restored)
        val resumed = FirstJumpGame.choose(restored, "resume")
        assertEquals(JumpStep.GUESS, resumed.state.step)
        assertEquals(null, resumed.state.guess)
        assertEquals(null, resumed.answerLabel)
        assertEquals(listOf("candidates", "guess_q"), resumed.cues)
    }

    @Test fun explicitGuessHelpKeepsAnUnknownGuessThroughRestoreAndReveal() {
        val guessing = choose(open(), "wet")
        for (help in listOf("힌트 줘", "모르겠어")) {
            val assisted = hear(guessing, help)
            assertEquals(JumpStep.QUESTION, assisted.state.step)
            assertEquals(null, assisted.state.guess)
            assertEquals(null, assisted.answerLabel)
            assertEquals(listOf("ask_question"), assisted.cues)
            val resting = choose(assisted, "rest")
            val restored = restoreLetter(resting.state.letterCheckpoint())!!
            assertEquals(resting.state, restored)
            val resumed = FirstJumpGame.choose(restored, "resume")
            assertEquals(null, resumed.state.guess)
            assertEquals(null, resumed.answerLabel)
            assertEquals(listOf("ask_question"), resumed.cues)
            val identified = choose(choose(resumed, "feathers"), "penguin")
            assertEquals(listOf("reveal_unsure", "direct_q"), identified.cues)
            assertEquals(null, identified.state.guess)
        }
        // An explicit two-candidate answer still remains the child's own prediction.
        val both = hear(guessing, "둘 다 가능해")
        assertEquals(JumpGuess.BOTH, both.state.guess)
        assertEquals("둘 다 가능해", both.answerLabel)
        assertEquals(listOf("guess_both", "ask_question"), both.cues)
    }

    @Test fun recognizedAnswersShowOnlyCanonicalLabelsAndNeverBecomeStoredSpeech() {
        val question = hear(hear(open(), "물소리"), "해달 같아")
        val raw = "오누야, 그 친구 몸에 기털이 있어?"
        val answer = hear(question, raw)
        assertEquals(choose(question, "feathers"), answer)
        assertEquals("깃털이 있어?", answer.answerLabel)
        assertEquals("날개로 헤엄쳐?", hear(question, "날개로 헤엄쳐?").answerLabel)
        assertEquals(null, hear(question, "깃털이 없대").answerLabel)
        assertEquals(null, choose(question, "invalid").answerLabel)
        assertEquals(null, choose(answer, "again").answerLabel)
        assertEquals(null, choose(answer, "rest").answerLabel)
        assertEquals(null, FirstJumpGame.resume(answer.state).answerLabel)
        val token = answer.state.letterCheckpoint()
        assertFalse(token.contains(raw))
        assertFalse(token.contains(answer.answerLabel!!))
        assertEquals(answer.state, restoreLetter(token))
        val directing = hear(answer, "펭귄")
        val unheard = hear(hear(directing, "성큼성큼 크게 첨벙"), "성큼성큼 크게 첨벙")
        assertTrue(unheard.state.paused)
        assertEquals(null, unheard.state.ending)
        assertEquals(null, unheard.answerLabel)
    }

    @Test fun bothHeardSoundsNeverBecomeAnUnspokenAnimalPrediction() {
        val sounds = hear(open(), "둘 다")
        assertEquals(JumpStep.GUESS, sounds.state.step)
        assertEquals(null, sounds.state.guess)
        assertEquals("물소리와 발소리", sounds.answerLabel)
        assertEquals(listOf("sounds_ack", "candidates", "guess_q"), sounds.cues)
        for (words in listOf("둘 다야", "둘 다 들렸어", "물소리랑 발소리", "발소리랑 물소리",
            "물소리도 나고 발소리도 났어", "발소리도 나고 물소리도 났어")) {
            assertEquals(sounds, hear(open(), words))
        }
        for (words in listOf("나는 물소리 들었어", "물이 첨벙하는 소리")) {
            assertEquals(choose(open(), "wet"), hear(open(), words))
        }
        for (words in listOf("해달이나 펭귄", "펭귄이나 해달")) {
            val earlyAnimals = hear(open(), words)
            assertEquals(JumpStep.QUESTION, earlyAnimals.state.step)
            assertEquals(JumpGuess.BOTH, earlyAnimals.state.guess)
            assertEquals(listOf("candidates", "guess_both", "ask_question"), earlyAnimals.cues)
        }
        val actualPrediction = hear(sounds, "둘 다")
        assertEquals(JumpGuess.BOTH, actualPrediction.state.guess)
        assertEquals(listOf("guess_both", "ask_question"), actualPrediction.cues)
    }

    @Test fun soundAliasesStillRejectNegationsAndConflictingPhrasesAsWholeUtterances() {
        for (words in listOf("나는 물소리 들었어 아니야", "물이 첨벙하는 소리가 아니야",
            "물소리도 나고 발소리도 난 건 아니야", "물소리 말고 발소리도 아니야",
            "둘 다 아니야", "둘 다 말고 물소리", "해달이나 펭귄은 아니야")) {
            val miss = hear(open(), words)
            assertEquals(JumpStep.WATER, miss.state.step)
            assertEquals(null, miss.state.guess)
            assertEquals(null, miss.answerLabel)
            assertTrue("unheard" in miss.cues)
            assertFalse(miss.text.contains(words))
        }
    }

    private fun identifySender() = choose(choose(choose(open(), "wet"), "otter"), "feathers")

    private fun assertIdentifyClueRetry(original: JumpTurn, retry: JumpTurn, expectedLabel: String?) {
        assertEquals(JumpStep.IDENTIFY, retry.state.step)
        assertEquals(original.state.copy(assisted = true), retry.state)
        assertEquals(listOf("feather_evidence", "who_q"), retry.cues)
        assertFalse(retry.text.contains("물어보자"))
        assertFalse(retry.text.contains("물어볼까"))
        assertEquals(expectedLabel, retry.answerLabel)
        assertTrue(retry.acceptsAnswer)
        assertEquals(retry.state, restoreLetter(retry.state.letterCheckpoint()))
    }

    @Test fun firstIncorrectSenderAnswerRechecksTheReceivedClueWithoutAskingAgain() {
        val identity = identifySender()
        for (retry in listOf(choose(identity, "otter"), hear(identity, "해달"))) {
            assertIdentifyClueRetry(identity, retry, "해달")
            val explained = hear(retry, "해달")
            assertEquals(JumpStep.DIRECT, explained.state.step)
            assertEquals(listOf("reveal_help", "direct_q"), explained.cues)
            assertEquals("해달", explained.answerLabel)
            assertTrue(explained.state.revealedWithHelp)
        }
    }

    @Test fun explicitSenderHelpRechecksTheReceivedClueWithoutInventingAnAnswer() {
        val identity = identifySender()
        for (retry in listOf(choose(identity, "hint"), hear(identity, "힌트 줘"), hear(identity, "모르겠어"))) {
            assertIdentifyClueRetry(identity, retry, null)
            val explained = hear(retry, "모르겠어")
            assertEquals(JumpStep.DIRECT, explained.state.step)
            assertEquals(listOf("reveal_help", "direct_q"), explained.cues)
            assertEquals(null, explained.answerLabel)
            assertTrue(explained.state.revealedWithHelp)
        }
    }

    @Test fun firstSenderSilenceRechecksTheReceivedClueAndSecondSilenceStillPauses() {
        val identity = identifySender()
        for (silence in listOf(null, " ")) {
            val retry = hear(identity, silence)
            assertIdentifyClueRetry(identity, retry, null)
            val quiet = hear(retry, silence)
            assertEquals(retry.state.copy(paused = true), quiet.state)
            assertEquals(null, quiet.answerLabel)
            assertEquals(null, quiet.state.ending)
            assertTrue(quiet.cues.isEmpty())
            assertFalse(quiet.acceptsAnswer)
            assertEquals(quiet.state, restoreLetter(quiet.state.letterCheckpoint()))
        }
    }

    @Test fun questionStageStillSuggestsWhatToAskBeforeTheReplyArrives() {
        val question = choose(choose(open(), "wet"), "otter")
        val helped = hear(question, "힌트 줘")
        assertEquals(JumpStep.IDENTIFY, helped.state.step)
        assertEquals(listOf("hint_body", "feather_evidence", "who_q"), helped.cues)
        assertEquals(question.state.guess, helped.state.guess)
        assertFalse(helped.state.assisted)
        assertEquals(null, helped.answerLabel)
        val silence = hear(question, null)
        assertEquals(question.state.copy(assisted = true), silence.state)
        assertEquals(listOf("hint_body", "ask_question"), silence.cues)
        assertEquals(null, silence.answerLabel)
    }

    @Test fun anAssistedRevealExplainsTheSenderWithoutIgnoringTheLatestAnswer() {
        for (guess in listOf("penguin", "otter", "both")) {
            val prediction = choose(choose(open(), "wet"), guess)
            val identity = choose(prediction, "feathers")
            for (answers in listOf(listOf("해달", "해달"), listOf("모르겠어", "모르겠어"),
                listOf("부릉부릉", "부릉부릉"))) {
                val explained = hear(hear(identity, answers[0]), answers[1])
                assertEquals(JumpStep.DIRECT, explained.state.step)
                assertTrue(explained.state.revealedWithHelp)
                assertEquals(prediction.state.guess, explained.state.guess)
                assertEquals(listOf("reveal_help", "direct_q"), explained.cues)
                assertEquals(if (answers[1] == "해달") "해달" else null, explained.answerLabel)
                assertEquals(listOf("repeat_prompt", "reveal_help", "direct_q"), choose(explained, "again").cues)
                val resting = choose(explained, "rest")
                val restored = restoreLetter(resting.state.letterCheckpoint())!!
                assertEquals(resting.state, restored)
                val resumed = FirstJumpGame.choose(restored, "resume")
                assertEquals(explained.state, resumed.state)
                assertEquals(listOf("reveal_help", "direct_q"), resumed.cues)
                assertEquals(null, resumed.answerLabel)
                val ended = choose(resumed, "soft")
                assertEquals(JumpEnding.SOFT, ended.state.ending)
                assertEquals(ended.state, restoreLetter(ended.state.letterCheckpoint()))
                assertTrue(ended.state.revealedWithHelp)
                assertFalse(choose(ended, "restart").state.revealedWithHelp)
            }
            val corrected = hear(hear(identity, "해달"), "펭귄")
            assertFalse(corrected.state.revealedWithHelp)
            assertEquals(prediction.state.guess, corrected.state.guess)
            assertFalse("reveal_help" in corrected.cues)
        }
    }

    @Test fun allLegacyCheckpointsKeepTheirCanonicalBytesAndTheNewFlagIsStrict() {
        for (step in JumpStep.values()) {
            val old = JumpState(step = step, guess = if (step == JumpStep.READY) null else JumpGuess.OTTER,
                ending = if (step == JumpStep.END) JumpEnding.BOLD else null)
            val expected = listOf(step.name, old.guess?.name.orEmpty(), old.ending?.name.orEmpty(), "false", "false")
                .joinToString("|")
            assertEquals(expected, old.letterCheckpoint())
            val restored = restoreLetter(expected)!!
            assertEquals(old, restored)
            assertFalse(restored.revealedWithHelp)
            assertEquals(expected, restored.letterCheckpoint())
            if (step !in setOf(JumpStep.READY, JumpStep.END)) {
                val paused = expected.substringBeforeLast("|false") + "|true"
                assertEquals(old.copy(paused = true), restoreLetter(paused))
                assertEquals(paused, restoreLetter(paused)!!.letterCheckpoint())
            }
        }
        val helped = JumpState(step = JumpStep.DIRECT, guess = JumpGuess.PENGUIN, revealedWithHelp = true)
        val canonical = "DIRECT|PENGUIN||false|false|true"
        assertEquals(canonical, helped.letterCheckpoint())
        assertEquals(helped, restoreLetter(canonical))
        assertEquals(canonical, restoreLetter(canonical)!!.letterCheckpoint())
        for (invalid in listOf("DIRECT|PENGUIN||false|false|false", "DIRECT|PENGUIN||false|false|",
            "DIRECT|PENGUIN||false|false|TRUE", "DIRECT|PENGUIN||false|false|unknown",
            "DIRECT|PENGUIN||false|false|true|future", "DIRECT|PENGUIN||maybe|false",
            "DIRECT|PENGUIN||false|1", "WATER|||false|false|true", "FUTURE|||false|false")) {
            assertEquals(null, restoreLetter(invalid))
        }
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
