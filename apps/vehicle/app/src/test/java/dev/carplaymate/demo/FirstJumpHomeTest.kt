package dev.carplaymate.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstJumpHomeTest {
    private fun child(id: String, vararg interests: String) =
        ChildProfile(id, id, interests.toSet(), interestsConfigured = true)

    @Test fun aFamilyThatSkippedInterestsOrSharesTheSeaSeesTheLetterFirst() {
        assertTrue(shouldFeatureFirstJump(listOf(child("a")), false))
        assertTrue(shouldFeatureFirstJump(listOf(child("a", "ocean", "space"), child("b")), false))
        assertTrue(shouldFeatureFirstJump(listOf(child("a", "ocean"), child("b", "animals", "ocean")), false))
    }

    @Test fun aSavedWorldOrAnExplicitDifferentInterestKeepsTheUsualRecommendation() {
        assertFalse(shouldFeatureFirstJump(listOf(child("a", "ocean")), true))
        assertFalse(shouldFeatureFirstJump(listOf(child("a")), true))
        assertFalse(shouldFeatureFirstJump(listOf(child("a", "dinosaurs")), false))
        assertFalse(shouldFeatureFirstJump(listOf(child("a", "dinosaurs"), child("b", "ocean")), false))
        assertFalse(shouldFeatureFirstJump(emptyList(), false))
    }
}
