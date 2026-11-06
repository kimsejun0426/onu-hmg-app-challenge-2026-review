package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class InterestMatchingTest {
    private val cards = InterestOptions.all.map { option ->
        AdventureCard(option.id, "1", option.label, "", "", option.id, 6, "약 6분", 6,
            interestTags = setOf(option.id), playStyle = "explore")
    }
    private fun child(id: String, vararg interests: String) = ChildProfile(id, id, interests.toSet(), true)
    private fun feedback(child: String, pack: String, choice: AdventureFeedback, at: Long = 100) =
        AdventureFeedbackEntry("s-$at", pack, child, choice, at)

    @Test fun everyExplicitInterestChangesTheFirstRecommendationToItsRealTag() {
        for (interest in InterestOptions.ids) {
            val result = InterestMatching.recommend(cards, listOf(child("별", interest))).first()
            assertEquals(interest, result.packId)
            assertEquals(listOf(interest), result.matchedInterestIds)
            assertEquals(listOf("별"), result.matchedChildIds)
            assertTrue(result.reasons.first().contains(InterestOptions.labels(setOf(interest)).single()))
        }
    }

    @Test fun aSharedExplicitInterestOffersOneActuallySharedCandidate() {
        val first = InterestMatching.recommend(cards,
            listOf(child("별", "dinosaurs", "ocean"), child("달", "ocean", "space"))).first()
        assertEquals("ocean", first.packId)
        assertTrue(first.shared)
        assertEquals(listOf("별", "달"), first.matchedChildIds)
    }

    @Test fun disjointInterestsGiveBothChildrenAnEarlyCandidate() {
        val result = InterestMatching.recommend(cards, listOf(child("별", "dinosaurs"), child("달", "ocean")))
        assertEquals(listOf("dinosaurs", "ocean"), result.take(2).map { it.packId })
        assertEquals(listOf(listOf("별"), listOf("달")), result.take(2).map { it.matchedChildIds })
        assertTrue(result.none { it.shared })
    }

    @Test fun aDatedCompletionAlternatesWhichChildIsOfferedFirst() {
        val result = InterestMatching.recommend(cards, listOf(child("별", "dinosaurs"), child("달", "ocean")),
            listOf(CompletedAdventure("dinosaurs", 100)))
        assertEquals(listOf("ocean", "dinosaurs"), result.take(2).map { it.packId })
    }

    @Test fun coveringOneChildPrefersAnAlternativeTheOtherChildHasNotDeclined() {
        val children = listOf(child("별", "dinosaurs", "space"), child("달", "animals"))
        val completed = listOf(CompletedAdventure("animals", 300), CompletedAdventure("stories", 200),
            CompletedAdventure("ocean", 100))
        val result = InterestMatching.recommend(cards, children, completed,
            listOf(feedback("달", "dinosaurs", AdventureFeedback.TRY_ANOTHER_TOPIC, 400)))
        assertEquals(listOf("space", "animals"), result.take(2).map { it.packId })
        assertEquals(listOf(listOf("별"), listOf("달")), result.take(2).map { it.matchedChildIds })
        assertTrue(result.indexOfFirst { it.packId == "dinosaurs" } > 1)
        assertEquals(setOf("dinosaurs", "space"), children[0].interests)
    }

    @Test fun anotherChildsFeedbackDoesNotRemoveTheOnlyCandidateForOneChild() {
        val result = InterestMatching.recommend(cards, listOf(child("별", "dinosaurs"), child("달", "animals")),
            feedback = listOf(feedback("달", "dinosaurs", AdventureFeedback.TRY_ANOTHER_TOPIC)))
        assertEquals(listOf("dinosaurs", "animals"), result.take(2).map { it.packId })
        assertEquals(listOf(listOf("별"), listOf("달")), result.take(2).map { it.matchedChildIds })
        assertTrue(result.first().reasons.any { it.contains("달의 ‘다음엔 다른 주제’") })
        assertEquals(cards.size, result.map { it.packId }.toSet().size)
    }

    @Test fun UndatedLegacyRecordsCannotInventRecentOrder() {
        val children = listOf(child("별", "dinosaurs"), child("달", "ocean"))
        assertEquals(InterestMatching.recommend(cards, children),
            InterestMatching.recommend(cards, children, listOf(CompletedAdventure("dinosaurs", null))))
    }

    @Test fun noInterestIsAnHonestBrowsableCatalogWithoutInferredMatches() {
        val result = InterestMatching.recommend(cards, listOf(child("별")))
        assertEquals(cards.size, result.size)
        assertTrue(result.all { it.matchedChildIds.isEmpty() && it.matchedInterestIds.isEmpty() && !it.shared })
        assertTrue(result.all { it.reasons.single().contains("관심사를 고르면") })
    }

    @Test fun aColdProfileWithoutEvidenceKeepsTheAuthoredCatalogOrder() {
        assertEquals(cards.map { it.packId }, InterestMatching.recommend(cards, emptyList()).map { it.packId })
        assertEquals(cards.map { it.packId }, InterestMatching.recommend(cards, listOf(child("별"), child("달"))).map { it.packId })
    }

    @Test fun explicitFeedbackChangesRankingWithoutChangingDeclaredInterests() {
        val children = listOf(child("별", "dinosaurs", "ocean"))
        val liked = InterestMatching.recommend(cards, children, feedback = listOf(feedback("별", "ocean", AdventureFeedback.LIKED)))
        assertEquals("ocean", liked.first().packId)
        val next = InterestMatching.recommend(cards, children, feedback = listOf(feedback("별", "dinosaurs", AdventureFeedback.TRY_ANOTHER_TOPIC)))
        assertEquals("ocean", next.first().packId)
        assertEquals(setOf("dinosaurs", "ocean"), children.single().interests)
        assertEquals("dinosaurs", next.last().packId)
    }

    @Test fun feedbackFromAnOldProfileCannotBeAttachedByMatchingNickname() {
        val current = listOf(child("new-id", "dinosaurs", "ocean"))
        assertEquals(InterestMatching.recommend(cards, current), InterestMatching.recommend(cards, current,
            feedback = listOf(feedback("old-id", "ocean", AdventureFeedback.LIKED))))
    }

    @Test fun askingForAnotherTopicOutweighsEvenSeveralMatchingTags() {
        val multiple = cards.first().copy(interestTags = setOf("dinosaurs", "space", "animals"))
        val result = InterestMatching.recommend(listOf(multiple) + cards.drop(1),
            listOf(child("별", "dinosaurs", "space", "animals")),
            feedback = listOf(feedback("별", multiple.packId, AdventureFeedback.TRY_ANOTHER_TOPIC)))
        assertEquals(multiple.packId, result.last().packId)
        assertNotEquals(multiple.packId, result.first().packId)
    }

    @Test fun latestExplicitFeedbackWinsAndClearRestoresTheUnbiasedRanking() {
        val children = listOf(child("별", "dinosaurs", "ocean"))
        val entries = listOf(feedback("별", "dinosaurs", AdventureFeedback.LIKED, 100),
            feedback("별", "dinosaurs", AdventureFeedback.TRY_ANOTHER_TOPIC, 200))
        assertEquals("ocean", InterestMatching.recommend(cards, children, feedback = entries).first().packId)
        assertEquals("dinosaurs", InterestMatching.recommend(cards, children).first().packId)
    }

    @Test fun identicalEvidenceHasStableResultsIndependentOfCatalogInputOrder() {
        val children = listOf(child("별", "stories", "space"), child("달", "ocean"))
        assertEquals(InterestMatching.recommend(cards, children), InterestMatching.recommend(cards.reversed(), children))
    }
}
