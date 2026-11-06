package dev.carplaymate.demo

import android.content.Context
import android.util.Base64
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class FamilyProfile(
    val first: String = "",
    val second: String? = null,
    val firstId: String? = null,
    val secondId: String? = null,
    val firstInterests: Set<String> = emptySet(),
    val secondInterests: Set<String> = emptySet(),
    val firstInterestsConfigured: Boolean = false,
    val secondInterestsConfigured: Boolean = false,
) {
    val children: List<ChildProfile>
        get() =
            listOfNotNull(
                firstId?.let { ChildProfile(it, first, firstInterests, firstInterestsConfigured) },
                secondId
                    ?.takeIf { second != null }
                    ?.let {
                        ChildProfile(
                            it,
                            requireNotNull(second),
                            secondInterests,
                            secondInterestsConfigured,
                        )
                    },
            )

    companion object {
        private val controls =
            listOf(
                "계속",
                "계속해",
                "계속하자",
                "이어하기",
                "이어서 하자",
                "다시 하자",
                "끝내기",
                "게임 종료",
                "놀이 종료",
                "종료",
                "다시",
                "그거 다시",
                "다시 말해줘",
                "다시 들려줘",
                "못 들었어",
                "다시 듣기",
                "힌트",
                "도와줘",
                "모르겠어",
                "힌트 줘",
                "잘 모르겠어",
                "몰라",
                "잘 몰라",
                "모르겠다",
                "모르겠어요",
                "생각 중",
                "생각중이야",
                "기다려",
                "기다려줘",
                "잠시만 기다려",
            )

        private fun normalize(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter {
                it.isLetterOrDigit() || it == '_'
            }

        private val addedControlNames = setOf(
            "하기싫어", "안할래", "안하고싶어", "그만하고싶어", "싫어", "싫어요", "오누야그만",
            "몰라", "잘몰라", "모르겠다", "모르겠어요",
        )

        fun problem(first: String, second: String?, allowExistingControlNames: Boolean = false,
                    existing: FamilyProfile? = null): String? {
            val names = listOfNotNull(first, second).map { it.trim() }
            if (names.any { it.length !in 1..12 || normalize(it).isBlank() })
                return "부르기 쉬운 별명을 1~12글자로 적어 주세요."
            if (names.map(::normalize).distinct().size != names.size)
                return "누구의 차례인지 알 수 있도록 서로 다른 별명을 적어 주세요."
            if (
                names.withIndex().any { (index, name) ->
                    val previous = if (index == 0) existing?.first else existing?.second
                    val unchanged = previous?.trim() == name
                    !((allowExistingControlNames || unchanged) && normalize(name) in addedControlNames) &&
                        (QuietRequest.isReservedNickname(name) || normalize(name) in controls.map(::normalize))
                }
            )
                return "놀이 중 사용하는 말과 겹치지 않는 별명으로 정해 주세요."
            return null
        }
    }
}

/**
 * Explicit nicknames/interests only. IDs identify this device's current profiles, not past
 * speakers.
 */
class FamilyPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
    private val cipher = PrivateRecordCipher(context, "family_profile_v3")
    val profile: FamilyProfile?
        get() = readSafely().profile

    internal val readIssue: FamilyReadIssue?
        get() = readSafely().issue

    /** A damaged v2 record is never treated as a v1 migration or a new family. */
    @Synchronized
    private fun readSafely(): FamilyReadResult =
        try {
            val stored = prefs.all
            val sealed = stored["profile_v3"]
            val values =
                if (sealed == null) stored
                else {
                    require(sealed is String && sealed.length <= 65_536)
                    val document =
                        JSONObject(
                            String(
                                cipher.decrypt(Base64.decode(sealed, Base64.NO_WRAP)),
                                Charsets.UTF_8,
                            )
                        )
                    require(
                        document.opt("schema_version") == 3 &&
                            document.keyList().toSet() ==
                                setOf("schema_version", "first", "second", "profile")
                    )
                    mapOf(
                        "first" to document.getString("first"),
                        "second" to
                            if (document.isNull("second")) null else document.getString("second"),
                        "profile_v2" to document.getJSONObject("profile").toString(),
                    )
                }
            val rawFirst = values["first"]
            val rawSecond = values["second"]
            val rawSaved = values["profile_v2"]
            if (rawFirst == null && rawSecond == null && rawSaved == null) FamilyReadResult()
            else {
                require(rawFirst is String && (rawSecond == null || rawSecond is String))
                val second = rawSecond as String?
                require(FamilyProfile.problem(rawFirst, second, allowExistingControlNames = true) == null)
                if (rawSaved == null) FamilyReadResult(FamilyProfile(rawFirst, second))
                else {
                    require(rawSaved is String && rawSaved.length <= 32_768)
                    val saved = JSONObject(rawSaved)
                    val version = saved.opt("schema_version")
                    if (version != 2 || saved.keyList().toSet() != PROFILE_KEYS) {
                        FamilyReadResult(issue = FamilyReadIssue.UNSUPPORTED)
                    } else {
                        fun id(key: String): String =
                            (saved.opt(key) as? String ?: error("Invalid identity"))
                        fun interests(key: String): Set<String> {
                            val array = saved.optJSONArray(key) ?: error("Invalid interests")
                            require(array.length() <= InterestOptions.ids.size)
                            return (0 until array.length())
                                .map { array.opt(it) as? String ?: error("Invalid interest") }
                                .toSet()
                                .also { require(it.size == array.length()) }
                        }
                        val firstId = id("first_id")
                        val secondId = id("second_id")
                        val firstInterests = interests("first_interests")
                        val secondInterests = interests("second_interests")
                        val firstConfigured =
                            saved.opt("first_configured") as? Boolean
                                ?: error("Invalid configuration")
                        val secondConfigured =
                            saved.opt("second_configured") as? Boolean
                                ?: error("Invalid configuration")
                        require(PROFILE_ID.matches(firstId))
                        require(
                            if (second == null)
                                secondId.isEmpty() && secondInterests.isEmpty() && !secondConfigured
                            else PROFILE_ID.matches(secondId) && secondId != firstId
                        )
                        if (!InterestOptions.ids.containsAll(firstInterests + secondInterests))
                            FamilyReadResult(issue = FamilyReadIssue.UNSUPPORTED)
                        else
                            FamilyReadResult(
                                FamilyProfile(
                                    rawFirst,
                                    second,
                                    firstId,
                                    secondId.takeIf { second != null },
                                    firstInterests,
                                    secondInterests,
                                    firstConfigured,
                                    secondConfigured,
                                )
                            )
                    }
                }
            }
        } catch (_: Exception) {
            FamilyReadResult(issue = FamilyReadIssue.DAMAGED)
        }

    /** A migration changes only current profiles, never existing sessions or their participants. */
    @Synchronized
    fun migrate(): Boolean {
        val read = readSafely()
        if (read.issue != null) return false
        val current = read.profile ?: return true
        if (current.firstId != null && (current.second == null || current.secondId != null)) {
            if (
                prefs.contains("profile_v3") &&
                    listOf("first", "second", "profile_v2").none(prefs::contains)
            )
                return true
            return write(current)
        }
        return save(current)
    }

    @Synchronized
    fun save(profile: FamilyProfile): Boolean {
        val read = readSafely()
        if (read.issue != null) return false
        val previous = read.profile
        if (FamilyProfile.problem(profile.first, profile.second, existing = previous) != null) return false
        val firstId = previous?.firstId ?: UUID.randomUUID().toString()
        val secondId =
            if (profile.second == null) null else previous?.secondId ?: UUID.randomUUID().toString()
        val next =
            profile.copy(
                firstId = firstId,
                secondId = secondId,
                firstInterests = previous?.firstInterests ?: profile.firstInterests,
                secondInterests =
                    if (profile.second == null) emptySet()
                    else previous?.secondInterests ?: profile.secondInterests,
                firstInterestsConfigured =
                    previous?.firstInterestsConfigured ?: profile.firstInterestsConfigured,
                secondInterestsConfigured =
                    profile.second != null &&
                        (previous?.secondInterestsConfigured ?: profile.secondInterestsConfigured),
            )
        return write(next)
    }

    @Synchronized
    fun saveInterests(first: Set<String>, second: Set<String>): Boolean {
        if (!InterestOptions.ids.containsAll(first + second)) return false
        val read = readSafely()
        if (read.issue != null) return false
        val current = read.profile ?: return false
        if (current.second == null && second.isNotEmpty()) return false
        val next =
            current.copy(
                firstId = current.firstId ?: UUID.randomUUID().toString(),
                secondId =
                    if (current.second == null) null
                    else current.secondId ?: UUID.randomUUID().toString(),
                firstInterests = first.toSet(),
                secondInterests = second.toSet(),
                firstInterestsConfigured = true,
                secondInterestsConfigured = current.second != null,
            )
        return write(next)
    }

    private fun write(profile: FamilyProfile): Boolean =
        try {
            val data =
                JSONObject()
                    .put("schema_version", 2)
                    .put("first_id", profile.firstId)
                    .put("second_id", profile.secondId ?: "")
                    .put(
                        "first_interests",
                        JSONArray(
                            InterestOptions.all
                                .map { it.id }
                                .filter { it in profile.firstInterests }
                        ),
                    )
                    .put(
                        "second_interests",
                        JSONArray(
                            InterestOptions.all
                                .map { it.id }
                                .filter { it in profile.secondInterests }
                        ),
                    )
                    .put("first_configured", profile.firstInterestsConfigured)
                    .put("second_configured", profile.secondInterestsConfigured)
            val document =
                JSONObject()
                    .put("schema_version", 3)
                    .put("first", profile.first)
                    .put("second", profile.second ?: JSONObject.NULL)
                    .put("profile", data)
            val sealed =
                Base64.encodeToString(
                    cipher.encrypt(document.toString().toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                )
            // Ciphertext and removal of the legacy plaintext share one preferences commit.
            val previous =
                listOf("first", "second", "profile_v2", "profile_v3").associateWith {
                    prefs.getString(it, null)
                }
            val saved =
                prefs
                    .edit()
                    .remove("first")
                    .remove("second")
                    .remove("profile_v2")
                    .putString("profile_v3", sealed)
                    .commit()
            if (!saved) {
                // SharedPreferences changes its memory before reporting a failed disk commit.
                // Restore that memory too, so an unconfirmed new identity cannot escape via
                // profile.
                val rollback = prefs.edit()
                previous.forEach { (key, value) -> rollback.putString(key, value) }
                rollback.commit()
            }
            saved &&
                prefs.getString("profile_v3", null) == sealed &&
                listOf("first", "second", "profile_v2").none(prefs::contains)
        } catch (_: Exception) {
            false
        }

    @Synchronized
    fun clear(): Boolean =
        try {
            prefs.edit().clear().commit()
        } catch (_: Exception) {
            false
        }

    companion object {
        private val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val PROFILE_KEYS =
            setOf(
                "schema_version",
                "first_id",
                "second_id",
                "first_interests",
                "second_interests",
                "first_configured",
                "second_configured",
            )
    }
}

internal enum class FamilyReadIssue {
    DAMAGED,
    UNSUPPORTED,
}

private data class FamilyReadResult(
    val profile: FamilyProfile? = null,
    val issue: FamilyReadIssue? = null,
)
