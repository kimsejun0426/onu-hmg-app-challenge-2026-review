package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationReasonTest {
    private fun card(id: String, vararg interests: String) =
        AdventureCard(id, "1", id, "", "", id, 6, "약 6분", 6,
            interestTags = interests.toSet(), playStyle = "explore")

    private val cards = listOf(card("garden", "animals", "stories"), card("space", "space"))
    private fun child(id: String, name: String, vararg interests: String) =
        ChildProfile(id, name, interests.toSet(), true)
    private fun reason(recommendation: AdventureRecommendation, children: List<ChildProfile>) =
        homeRecommendationReason(recommendation.packId, recommendation, children, false)

    @Test fun aSharedInterestNamesBothCurrentChildren() {
        val children = listOf(child("a", "별", "animals"), child("b", "달", "animals"))
        val recommendation = InterestMatching.recommend(cards, children).first()
        assertTrue(recommendation.shared)
        assertEquals("별·달의 관심사를 함께 반영했어요.", reason(recommendation, children))
        assertEquals(listOf("별의 관심사 ‘동물’에서 골랐어요.", "달의 관심사 ‘동물’에서 골랐어요."),
            recommendation.reasons)
    }

    @Test fun differentInterestsCanShareAPackWithoutClaimingTheSameTaste() {
        val children = listOf(child("a", "별", "animals"), child("b", "달", "stories"))
        val recommendation = InterestMatching.recommend(cards, children).first()
        assertTrue(recommendation.shared)
        assertEquals(setOf("animals", "stories"), recommendation.matchedInterestIds.toSet())
        assertEquals("별·달의 관심사를 함께 반영했어요.", reason(recommendation, children))
    }

    @Test fun separateOrSingleInterestsKeepTheirActualIndividualReasonsAndOrder() {
        val separate = listOf(child("a", "별", "animals"), child("b", "달", "space"))
        val recommendations = InterestMatching.recommend(cards, separate)
        assertEquals(listOf("garden", "space"), recommendations.map { it.packId })
        assertEquals(listOf("별의 관심사 ‘동물’에서 골랐어요.", "달의 관심사 ‘우주’에서 골랐어요."),
            recommendations.map { reason(it, separate) })
        val oneSelected = listOf(child("a", "별", "animals"), child("b", "달"))
        assertEquals("별의 관심사 ‘동물’에서 골랐어요.",
            reason(InterestMatching.recommend(cards, oneSelected).first(), oneSelected))
    }

    @Test fun noInterestsKeepTheHonestFallbackButCurrentFeedbackRemainsAReason() {
        val children = listOf(child("a", "별"), child("b", "달"))
        val original = InterestMatching.recommend(cards, children)
        assertEquals(cards.map { it.packId }, original.map { it.packId })
        assertEquals("관심사를 고르면 아이에게 맞는 주제를 먼저 보여줘요.", reason(original.first(), children))
        val feedback = AdventureFeedbackEntry("completed", "space", "b", AdventureFeedback.LIKED, 100)
        val preferred = InterestMatching.recommend(cards, children, feedback = listOf(feedback)).first()
        assertEquals("space", preferred.packId)
        assertEquals("달의 ‘좋았어요’ 선택을 반영했어요.", reason(preferred, children))
    }

    @Test fun renamingUsesCurrentNamesButAReaddedNamesakeDoesNotInheritTheOldMatch() {
        val old = listOf(child("a", "별", "animals"), child("old-b", "달", "animals"))
        val previous = InterestMatching.recommend(cards, old).first()
        val renamed = listOf(old[0], old[1].copy(nickname = "노을"))
        assertEquals("별·노을의 관심사를 함께 반영했어요.", reason(previous, renamed))
        val current = listOf(old[0], child("new-b", "달"))
        assertEquals("마음이 가는 이야기로 시작해요.", reason(previous, current))
        val oldFeedback = AdventureFeedbackEntry("old-session", "space", "old-b", AdventureFeedback.LIKED, 100)
        val fresh = InterestMatching.recommend(cards, current, feedback = listOf(oldFeedback)).first()
        assertFalse(fresh.shared)
        assertEquals("별의 관심사 ‘동물’에서 골랐어요.", reason(fresh, current))
    }

    @Test fun resumingKeepsTheExistingJourneyDescriptionAndDoesNotBorrowAnotherPacksReason() {
        val children = listOf(child("a", "별", "animals"), child("b", "달", "animals"))
        val recommendation = InterestMatching.recommend(cards, children).first()
        assertEquals("함께 고른 길에서 다시 만나요.",
            homeRecommendationReason("space", recommendation, children, true))
        assertEquals("마음이 가는 이야기로 시작해요.",
            homeRecommendationReason("space", recommendation, children, false))
    }
}
