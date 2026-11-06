package dev.carplaymate.demo

data class InterestOption(val id: String, val label: String)

object InterestOptions {
    val all = listOf(
        InterestOption("dinosaurs", "공룡"), InterestOption("space", "우주"),
        InterestOption("animals", "동물"), InterestOption("vehicles", "자동차"),
        InterestOption("ocean", "바다"), InterestOption("stories", "이야기"),
    )
    val ids: Set<String> = all.map { it.id }.toSet()
    fun labels(ids: Set<String>): List<String> = all.filter { it.id in ids }.map { it.label }
}

data class ChildProfile(
    val id: String,
    val nickname: String,
    val interests: Set<String> = emptySet(),
    val interestsConfigured: Boolean = false,
)

enum class AdventureFeedback { LIKED, TRY_ANOTHER_TOPIC }

data class AdventureFeedbackEntry(
    val sessionId: String,
    val packId: String,
    val childId: String,
    val feedback: AdventureFeedback,
    val recordedAtEpochMillis: Long,
)

data class JournalParticipant(val profileId: String?, val nickname: String)

data class AdventureRecommendation(
    val packId: String,
    val reasons: List<String>,
    val matchedChildIds: List<String>,
    val matchedInterestIds: List<String>,
    val shared: Boolean,
)

data class CompletedAdventure(val packId: String, val completedAtEpochMillis: Long?)

/** No model, inferred interests, randomness, clock, Android dependency or mutation. */
object InterestMatching {
    fun recommend(
        cards: List<AdventureCard>,
        children: List<ChildProfile>,
        completed: List<CompletedAdventure> = emptyList(),
        feedback: List<AdventureFeedbackEntry> = emptyList(),
    ): List<AdventureRecommendation> {
        if (cards.isEmpty()) return emptyList()
        val active = children.take(2)
        val recent = completed.filter { it.completedAtEpochMillis != null }
            .sortedWith(compareByDescending<CompletedAdventure> { it.completedAtEpochMillis }.thenBy { it.packId })
            .map { it.packId }.distinct().take(3)
        val latestFeedback = feedback.filter { entry -> active.any { it.id == entry.childId } }
            .sortedWith(compareByDescending<AdventureFeedbackEntry> { it.recordedAtEpochMillis }
                .thenBy { it.sessionId })
            .distinctBy { it.childId to it.packId }.associateBy { it.childId to it.packId }
        fun matched(child: ChildProfile, card: AdventureCard) = child.interests.intersect(card.interestTags)
        fun recentPenalty(card: AdventureCard): Int = recent.indexOf(card.packId).let { if (it < 0) 0 else (3 - it) * 8 }
        fun childScore(child: ChildProfile, card: AdventureCard): Int {
            if (latestFeedback[child.id to card.packId]?.feedback == AdventureFeedback.TRY_ANOTHER_TOPIC) {
                return -180 - recentPenalty(card)
            }
            val preference = when (latestFeedback[child.id to card.packId]?.feedback) {
                AdventureFeedback.LIKED -> 40
                else -> 0
            }
            return matched(child, card).size * 100 + preference - recentPenalty(card)
        }
        fun common(card: AdventureCard) = active.size == 2 && active.all {
            matched(it, card).isNotEmpty() && latestFeedback[it.id to card.packId]?.feedback != AdventureFeedback.TRY_ANOTHER_TOPIC
        }
        fun score(card: AdventureCard) = active.sumOf { childScore(it, card) } + if (common(card)) 70 else 0
        val hasEvidence = active.any { it.interests.isNotEmpty() } || latestFeedback.isNotEmpty() || recent.isNotEmpty()
        val ranked = if (hasEvidence) cards.sortedWith(compareByDescending<AdventureCard>(::score).thenBy { it.packId }) else cards
        val ordered = mutableListOf<AdventureCard>()
        fun add(card: AdventureCard?) { if (card != null && ordered.none { it.packId == card.packId }) ordered.add(card) }
        if (active.size == 2) {
            val shared = ranked.firstOrNull(::common)
            if (shared != null) add(shared)
            else {
                // After a dated completion matching only one child, offer the other child's turn first.
                val last = recent.firstOrNull()?.let { id -> cards.firstOrNull { it.packId == id } }
                val lead = if (last != null && matched(active[0], last).isNotEmpty() && matched(active[1], last).isEmpty()) 1 else 0
                for (index in listOf(lead, 1 - lead)) {
                    val child = active[index]
                    val candidates = cards.filter { candidate ->
                        ordered.none { it.packId == candidate.packId } && childScore(child, candidate) > 0
                    }.sortedWith(compareByDescending<AdventureCard> { childScore(child, it) }.thenBy { it.packId })
                    // Cover this child without overriding the other child's explicit request
                    // when an equally relevant different topic is available.
                    add(candidates.firstOrNull { candidate -> active.none {
                        latestFeedback[it.id to candidate.packId]?.feedback == AdventureFeedback.TRY_ANOTHER_TOPIC
                    } } ?: candidates.firstOrNull())
                }
            }
        }
        ranked.forEach(::add)
        return ordered.map { card ->
            val matches = active.filter { matched(it, card).isNotEmpty() }
            val reasons = mutableListOf<String>()
            for (child in matches) reasons += "${child.nickname}의 관심사 ‘${InterestOptions.labels(matched(child, card)).joinToString("·")}’에서 골랐어요."
            for (child in active) when (latestFeedback[child.id to card.packId]?.feedback) {
                AdventureFeedback.LIKED -> reasons += "${child.nickname}의 ‘좋았어요’ 선택을 반영했어요."
                AdventureFeedback.TRY_ANOTHER_TOPIC -> reasons += "${child.nickname}의 ‘다음엔 다른 주제’ 선택 기록이 있어요."
                null -> Unit
            }
            if (recent.isNotEmpty() && card.packId !in recent) reasons += "최근에 마친 모험과 다른 이야기를 만나봐요."
            if (reasons.isEmpty()) reasons += if (active.all { it.interests.isEmpty() })
                "관심사를 고르면 아이에게 맞는 주제를 먼저 보여줘요." else "다른 주제도 함께 둘러볼 수 있어요."
            AdventureRecommendation(card.packId, reasons, matches.map { it.id },
                InterestOptions.all.map { it.id }.filter { id -> matches.any { id in matched(it, card) } }, common(card))
        }
    }
}
