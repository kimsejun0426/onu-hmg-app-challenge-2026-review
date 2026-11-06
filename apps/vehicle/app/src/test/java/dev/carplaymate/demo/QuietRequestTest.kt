package dev.carplaymate.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietRequestTest {
    @Test fun punctuationAndSpacingDoNotDelayAStop() {
        assertTrue(QuietRequest.matches("  그만해 줘!  "))
        assertTrue(QuietRequest.matches("쉬고 싶어."))
        assertTrue(QuietRequest.matches("일시 정지"))
    }

    @Test fun eitherRegisteredChildMayStopWithoutWaitingForTheirTurn() {
        val names = listOf("민준", "서윤")
        assertTrue(QuietRequest.matches("서윤, 잠깐만.", names))
        assertTrue(QuietRequest.matches("민준이는 이제 그만", names))
        assertTrue(QuietRequest.matches("멈춰 줘!", names))
    }

    @Test fun containingStopWordDoesNotTurnAnAnswerIntoAStop() {
        assertFalse(QuietRequest.matches("그만하지 마"))
        assertFalse(QuietRequest.matches("민준, 멈춰 있는 자동차", listOf("민준")))
        assertFalse(QuietRequest.matches("조용히 물가로 갈래"))
    }

    @Test fun explicitRefusalStopsButQuotedOrNegatedRefusalDoesNot() {
        for (text in listOf("하기 싫어", "하기싫어!", "안 할래", "안할래", "안 하고 싶어", "그만 하고 싶어", "싫어", "싫어요", "오누야 그만")) {
            assertTrue(text, QuietRequest.matches(text))
            assertTrue(text, QuietRequest.matches("서윤, $text", listOf("민준", "서윤")))
        }
        for (text in listOf("하기 싫어라고 말하면 돼", "안 할래라는 말을 들었어", "안 하고 싶은 건 아니야", "오누야 그만하지 마", "싫어하지 않아")) {
            assertFalse(text, QuietRequest.matches(text))
        }
        assertFalse(QuietRequest.matches("몰라"))
    }

    @Test fun nicknameReservationKeepsTheOriginalExactAliasesWithoutAddressParsing() {
        for (name in listOf("오누야 쉬자", "오누야 멈춰", "오누야 하기 싫어")) {
            assertTrue(name, QuietRequest.matches(name))
            assertFalse(name, QuietRequest.isReservedNickname(name))
            assertFalse(name, QuietRequest.matches(name, listOf(name)))
        }
        for (name in listOf("그만", "  쉬고 싶어!  ", "일시 정지", "오누야그만", "오누야, 그만!", "\"오누야 그만\"")) {
            assertTrue(name, QuietRequest.isReservedNickname(name))
        }
        assertFalse(QuietRequest.isReservedNickname("서윤, 쉬자"))
        assertFalse(QuietRequest.isReservedNickname("오누야 쉬지 마"))
    }

    @Test fun oneOnuAddressAcceptsEveryExistingStopFromEitherChild() {
        val stops = listOf("그만", "그만해", "그만해 줘", "그만할래", "그만하자", "이제 그만",
            "멈춰", "멈춰 줘", "조용히", "조용히 해줘", "쉬자", "쉬고 싶어", "쉴래", "졸려",
            "잠깐", "잠깐만", "일시 정지", "하기 싫어", "안 할래", "안 하고 싶어", "그만 하고 싶어", "싫어", "싫어요")
        val names = listOf("민준", "서윤")
        for (stop in stops) {
            assertTrue(stop, QuietRequest.matches("오누야 $stop"))
            for (name in names) {
                assertTrue(stop, QuietRequest.matches("$name, 오누야 $stop!", names))
                assertTrue(stop, QuietRequest.matches("오누야 $name, $stop!", names))
                assertTrue(stop, QuietRequest.matches("오누야 ${name}이는 $stop", names))
            }
        }
        assertTrue(QuietRequest.matches("오누야그만")) // Previously shipped exact alias.
    }

    @Test fun addressUsesOnlyDeclaredBoundariesAndNormalizedKorean() {
        val decomposed = java.text.Normalizer.normalize("오누야", java.text.Normalizer.Form.NFD)
        val separators = listOf(" ", "\t", "\n", "\u001c", "\u0085", "\u3000", "\u00a0", "\u202f", ",", ":", "：", ".", "!", "?")
        for (separator in separators) {
            assertTrue(separator, QuietRequest.matches("$decomposed${separator}서윤, 쉬자", listOf("서윤")))
        }
        for (text in listOf("오누야쉬자", "오누야멈춰", "오누야; 쉬자", "오누야/ 멈춰", "오누야요 쉬자")) {
            assertFalse(text, QuietRequest.matches(text))
        }
    }

    @Test fun fullRegisteredNamesWinAndAmbiguousOnuNamesDisableNewAddressGrammar() {
        for (name in listOf("오누야 쉬자", "오누야쉬자", "오누야 그만")) {
            val names = listOf(name, "서윤")
            assertFalse(name, QuietRequest.matches(name, names))
            assertTrue(name, QuietRequest.matches("$name, 쉬자", names))
            assertTrue(name, QuietRequest.matches("오누야 $name, 멈춰", names))
        }
        for (name in listOf("오누", "오누야", "오 누", "오 누야")) {
            val names = listOf(name, "서윤")
            assertTrue(name, QuietRequest.matches("$name, 쉬자", names))
            assertFalse(name, QuietRequest.matches("서윤, 오누야 쉬자", names))
            assertFalse(name, QuietRequest.matches("오누야 서윤, 쉬자", names))
        }
    }

    @Test fun addressedQuotesNegationExtraNamesAndRepeatedAddressesNeverBecomeAStop() {
        val names = listOf("민준", "서윤")
        val delimiters = listOf('"' to '"', '\'' to '\'', '`' to '`', '“' to '”', '‘' to '’',
            '「' to '」', '『' to '』', '«' to '»', '‹' to '›', '(' to ')', '[' to ']', '{' to '}', '〈' to '〉', '《' to '》')
        for ((left, right) in delimiters) {
            for (text in listOf("오누야 ${left}쉬자$right", "서윤, 오누야 ${left}그만$right", "오누야 ${left}서윤$right, 멈춰")) {
                assertFalse(text, QuietRequest.matches(text, names))
            }
        }
        for (text in listOf("오누야 쉬지 마", "오누야 그만하지 마", "오누야 하기 싫은 건 아니야",
                "오누야 쉬자라고 했어", "오누야 멈춰 있는 자동차", "오누야 쉬자 아니 계속",
                "오누야 오누야 쉬자", "서윤, 오누야 오누야 그만", "오누야 서윤, 오누야 멈춰",
                "민준, 오누야 서윤, 쉬자", "오누야 민준 서윤 쉬자", "오누야 알 수 없는 이름 쉬자",
                "오누야", "오누야 민준", "민준, 오누야 민준", "오누야 계속", "오누야 도와줘", "오누야 기다려")) {
            assertFalse(text, QuietRequest.matches(text, names))
        }
    }
}
