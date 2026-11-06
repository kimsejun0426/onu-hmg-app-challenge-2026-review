package dev.carplaymate.demo

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FamilyProfileTest {
    @Test fun newControlsAreReservedButAnExistingChildMayKeepTheirOwnName() {
        val existing = FamilyProfile("몰라", "안할래")
        assertNotNull(FamilyProfile.problem("몰라", null))
        assertNull(FamilyProfile.problem("몰라", "새달", existing = existing))
        assertNull(FamilyProfile.problem("새별", "안할래", existing = existing))
        assertNull(FamilyProfile.problem("몰라", null, existing = existing))
        assertNotNull(FamilyProfile.problem("안할래", null, existing = existing))
        assertNotNull(FamilyProfile.problem("새별", "몰라", existing = existing))
    }

    @Test fun spokenAddressGrammarDoesNotInvalidatePreviouslyAllowedNames() {
        for (name in listOf("오누야 쉬자", "오누야 멈춰", "오누야 하기 싫어")) {
            val existing = FamilyProfile(name, "서윤")
            assertNull(name, FamilyProfile.problem(name, null))
            assertNull(name, FamilyProfile.problem(name, "서윤", allowExistingControlNames = true))
            assertNull(name, FamilyProfile.problem(name, "서윤", existing = existing))
            assertNull(name, FamilyProfile.problem("민준", name, existing = FamilyProfile("민준", name)))
        }
    }

    @Test fun shippedCompactOnuStopStillReservesNewNamesAndPreservesTheSameExistingChild() {
        for (name in listOf("오누야그만", "오누야 그만", "오누야, 그만!")) {
            val existing = FamilyProfile(name, "서윤")
            assertNotNull(name, FamilyProfile.problem(name, null))
            assertNull(name, FamilyProfile.problem(name, "서윤", allowExistingControlNames = true))
            assertNull(name, FamilyProfile.problem(name, "새달", existing = existing))
            assertNotNull(name, FamilyProfile.problem("민준", name, existing = existing))
        }
        assertNotNull(FamilyProfile.problem("쉬자", null, allowExistingControlNames = true))
    }

    @Test fun nicknamesMayBeKoreanAndSinglePlayerHasNoSecondName() {
        assertNull(FamilyProfile.problem(" 별 ", null))
        assertNull(FamilyProfile.problem("별", "달"))
    }
    @Test fun normalizedDuplicatesAndReservedControlsCannotClaimTurns() {
        assertNotNull(FamilyProfile.problem("별", "별"))
        assertNotNull(FamilyProfile.problem("Ａ", "a"))
        assertNotNull(FamilyProfile.problem("그만", null))
        assertNotNull(FamilyProfile.problem("다시 말해줘", null))
    }
    @Test fun blankPunctuationAndOversizedNamesAreRejectedBeforeStarting() {
        assertNotNull(FamilyProfile.problem(" ", null))
        assertNotNull(FamilyProfile.problem("...", null))
        assertNotNull(FamilyProfile.problem("1234567890123", null))
        assertNotNull(FamilyProfile.problem("별", ""))
    }
}
