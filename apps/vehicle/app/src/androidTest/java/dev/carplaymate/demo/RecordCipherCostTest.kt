package dev.carplaymate.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in observation, never part of android_smoke's default class list. Enable with
 * -e class dev.carplaymate.demo.RecordCipherCostTest -e storageCipherCostTests true.
 * Only synthetic data and a key unique to this test scope are used. No key bytes are requested.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RecordCipherCostTest {
    @Test
    fun existingKeyLookupAndCachedHandleCostsWithDeletionAndReplacementChecks() {
        assumeTrue(
            "Run the isolated cipher cost observation explicitly",
            InstrumentationRegistry.getArguments().getString("storageCipherCostTests") == "true",
        )
        assertNotSame("Do not measure on the UI thread", Looper.getMainLooper(), Looper.myLooper())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = JSONArray()
        val report = JSONObject()
            .put("schema_version", 1)
            .put("status", "running")
            .put("completed", false)
            .put("api_level", Build.VERSION.SDK_INT)
            .put("payload_bytes", PAYLOAD_BYTES)
            .put("roundtrips_per_sample", ROUNDTRIPS)
            .put("repetitions_per_mode", REPETITIONS)
            .put("measured_samples", samples)
            .put("cached_key_material_exported", false)
            .put("scope", JSONArray(listOf(
                "A fixed synthetic 4 KiB payload; both modes create fresh Cipher instances and provider-generated GCM IVs for each encryption.",
                "A roundtrip includes encrypt, decrypt, envelope checks and plaintext equality. The current helper performs an existing-key lookup for each encrypt and decrypt.",
                "The cached alternative loads one AndroidKeyStore SecretKey handle before timing. It does not export or cache key bytes; initial key generation and handle lookup are excluded.",
                "Three samples of 300 roundtrips per mode, with alternating mode order and no discarded warm-up; results are observations, not latency acceptance thresholds.",
                "Deletion and same-alias replacement checks start new cryptographic operations after the key change. They do not test cancellation of an operation already initialized.",
                "Results apply to the tested Android implementation. They do not establish portability, hardware-backed protection, production cache safety or vehicle performance.",
            )))
        writeReport(context, report)
        var primaryFailure: Throwable? = null
        try {
            val scope = CipherScope(context)
            try {
                val payload = ByteArray(PAYLOAD_BYTES) { ((it * 37 + 11) and 255).toByte() }
                val current = PrivateRecordCipher(scope, DOMAIN)
                val initial = current.encrypt(payload) // Deliberately fresh isolated bootstrap.
                val cached = scope.keyStore().getKey(scope.alias, null) as? SecretKey
                    ?: throw GeneralSecurityException("Expected an AndroidKeyStore SecretKey handle")
                assertArrayEquals(payload, cachedDecrypt(cached, initial))
                val fromCached = cachedEncrypt(cached, payload)
                assertArrayEquals(payload, current.decrypt(fromCached))
                assertArrayEquals(payload, cachedDecrypt(cached, current.encryptExisting(payload)))
                assertFalse(initial.copyOfRange(4, 16).contentEquals(fromCached.copyOfRange(4, 16)))
                report.put("interoperability", JSONObject()
                    .put("current_to_cached", true)
                    .put("cached_to_current", true)
                    .put("fresh_provider_ivs", true))

                repeat(REPETITIONS) { repetition ->
                    val order = if (repetition % 2 == 0) listOf(LOOKUP, CACHED) else listOf(CACHED, LOOKUP)
                    order.forEachIndexed { position, mode ->
                        val started = SystemClock.elapsedRealtimeNanos()
                        repeat(ROUNDTRIPS) {
                            val encrypted = if (mode == LOOKUP) current.encryptExisting(payload)
                                else cachedEncrypt(cached, payload)
                            val decoded = if (mode == LOOKUP) current.decrypt(encrypted)
                                else cachedDecrypt(cached, encrypted)
                            check(decoded.contentEquals(payload)) { "Synthetic roundtrip differs" }
                        }
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                        check(elapsed.isFinite() && elapsed > 0)
                        samples.put(JSONObject().put("mode", mode).put("repetition", repetition)
                            .put("position_in_pair", position).put("roundtrips", ROUNDTRIPS)
                            .put("elapsed_ms", elapsed).put("per_roundtrip_ms", elapsed / ROUNDTRIPS))
                    }
                }

                // A valid ciphertext from this exact key, checked before deleting its alias.
                val oldCiphertext = cachedEncrypt(cached, payload)
                assertArrayEquals(payload, current.decrypt(oldCiphertext))
                scope.keyStore().deleteEntry(scope.alias)
                assertFalse(scope.keyStore().containsAlias(scope.alias))
                rejected { current.encryptExisting(payload) }
                rejected { current.decrypt(oldCiphertext) }
                rejected { cachedEncrypt(cached, payload) }
                rejected { cachedDecrypt(cached, oldCiphertext) }
                assertFalse("Failed operations must not recreate the deleted alias", scope.keyStore().containsAlias(scope.alias))
                report.put("after_key_deletion", JSONObject()
                    .put("current_encrypt_rejected", true).put("current_decrypt_rejected", true)
                    .put("cached_encrypt_rejected", true).put("cached_decrypt_rejected", true)
                    .put("alias_remains_absent", true))

                // Creating a replacement is explicit and confined to this disposable key scope.
                // An old cached handle must not silently start addressing the replacement key.
                val replacementCiphertext = current.encrypt(payload)
                assertTrue(scope.keyStore().containsAlias(scope.alias))
                assertArrayEquals(payload, current.decrypt(replacementCiphertext))
                rejected { current.decrypt(oldCiphertext) }
                rejected { cachedEncrypt(cached, payload) }
                rejected { cachedDecrypt(cached, oldCiphertext) }
                rejected { cachedDecrypt(cached, replacementCiphertext) }
                report.put("after_same_alias_replacement", JSONObject()
                    .put("replacement_current_roundtrip", true)
                    .put("old_ciphertext_rejected_by_current", true)
                    .put("old_handle_encrypt_rejected", true)
                    .put("old_handle_decrypt_rejected", true)
                    .put("old_handle_cannot_decrypt_replacement", true))
                val aggregates = JSONObject()
                for (mode in listOf(LOOKUP, CACHED)) {
                    val values = (0 until samples.length()).map { samples.getJSONObject(it) }
                        .filter { it.getString("mode") == mode }.map { it.getDouble("elapsed_ms") }
                    assertEquals(REPETITIONS, values.size)
                    aggregates.put(mode, JSONObject()
                        .put("elapsed_ms", statistics(values))
                        .put("per_roundtrip_ms", statistics(values.map { it / ROUNDTRIPS })))
                }
                report.put("aggregates", aggregates)
            } finally {
                scope.clean()
            }
            report.put("scope_key_and_directory_removed", true)
                .put("completed", true).put("status", "passed")
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("completed", false).put("status", "failed")
            throw error
        } finally {
            try {
                writeReport(context, report)
            } catch (writeFailure: Throwable) {
                if (primaryFailure == null) throw writeFailure
                primaryFailure.addSuppressed(writeFailure)
            }
        }
    }

    private fun cachedEncrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (cipher.iv.size != 12) throw GeneralSecurityException("Unexpected IV size")
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        if (ciphertext.size != plaintext.size + 16) throw GeneralSecurityException("Unexpected tag size")
        return HEADER + cipher.iv + ciphertext
    }

    private fun cachedDecrypt(key: SecretKey, encrypted: ByteArray): ByteArray {
        if (!PrivateRecordCipher.hasEnvelope(encrypted) || encrypted.size < 32)
            throw GeneralSecurityException("Unsupported envelope")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(4, 16)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(encrypted, 16, encrypted.size - 16)
    }

    private fun rejected(operation: () -> Any?) {
        try {
            operation()
            fail("An operation using the missing or replaced key unexpectedly succeeded")
        } catch (_: GeneralSecurityException) {
            // Provider may reject the key at init or at finalization.
        } catch (_: ProviderException) {
            // Some Android providers wrap a KeyStore operation failure in ProviderException.
        }
    }

    private fun statistics(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        check(sorted.size == REPETITIONS)
        return JSONObject().put("median", sorted[sorted.size / 2]).put("min", sorted.first())
            .put("max", sorted.last()).put("samples", JSONArray(values))
    }

    private fun writeReport(context: Context, report: JSONObject) {
        val file = AtomicFile(File(context.filesDir, "cipher-cost.json"))
        val output = file.startWrite()
        try {
            output.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private class CipherScope(context: Context) : ContextWrapper(context) {
        private val parent = context.noBackupFilesDir.canonicalFile
        private val directory = File(parent, "cipher_cost_${UUID.randomUUID()}").also { check(it.mkdir()) }
        val alias = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)

        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${directory.name}_$name", mode)
        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun clean() {
            check(getSharedPreferences("family_v1", Context.MODE_PRIVATE).edit().clear().commit())
            keyStore().deleteEntry(alias)
            check(!keyStore().containsAlias(alias))
            check(directory.canonicalFile.parentFile == parent && directory.name.startsWith("cipher_cost_"))
            check(directory.deleteRecursively())
        }
    }

    companion object {
        private const val PAYLOAD_BYTES = 4 * 1024
        private const val ROUNDTRIPS = 300
        private const val REPETITIONS = 3
        private const val DOMAIN = "record-cipher-cost-v1"
        private const val LOOKUP = "per_call_existing_key_lookup"
        private const val CACHED = "cached_keystore_handle"
        private val HEADER = byteArrayOf(0x4f, 0x4e, 0x55, 0x31)
        private val AAD = HEADER + DOMAIN.toByteArray(Charsets.UTF_8)
    }
}
