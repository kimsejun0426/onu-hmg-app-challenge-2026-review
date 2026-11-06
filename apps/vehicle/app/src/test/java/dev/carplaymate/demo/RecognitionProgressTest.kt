package dev.carplaymate.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionProgressTest {
    @Test fun initializationDelayIsNotChildSilence() {
        assertFalse(RecognitionProgress().waitingWithoutSpeech)
    }

    @Test fun onlyReadyListenerWithoutSpeechMayReportNoResponse() {
        val progress = RecognitionProgress()
        progress.readyForSpeech()
        assertTrue(progress.waitingWithoutSpeech)
        progress.beginningOfSpeech()
        assertFalse(progress.waitingWithoutSpeech)
        progress.endOfSpeech()
        assertFalse(progress.waitingWithoutSpeech)
    }

    @Test fun processingWithoutBeginningCallbackIsStillTechnicalDelay() {
        val progress = RecognitionProgress()
        progress.readyForSpeech()
        progress.endOfSpeech()
        assertFalse(progress.waitingWithoutSpeech)
    }
}
