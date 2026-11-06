package dev.carplaymate.demo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private authenticated records. Keys stay in AndroidKeyStore; no plaintext disk staging. */
internal class PrivateRecordCipher(context: Context, domain: String) {
    private val keyContext = context.applicationContext
    private val alias = alias(context)
    private val associatedData = HEADER + domain.toByteArray(Charsets.UTF_8)
    private var keyScope: KeyScope? = null

    internal constructor(context: Context, domain: String, scope: KeyScope) : this(context, domain) {
        require(alias == scope.alias)
        keyScope = scope
    }

    init {
        require(domain.isNotBlank())
    }

    fun encrypt(plaintext: ByteArray): ByteArray = encrypt(plaintext, createKey = true)

    /** Established records must never replace a lost shared key, even after a successful read. */
    fun encryptExisting(plaintext: ByteArray): ByteArray = encrypt(plaintext, createKey = false)

    private fun encrypt(plaintext: ByteArray, createKey: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore generates a fresh IV; caller-supplied IVs are deliberately forbidden.
        cipher.init(Cipher.ENCRYPT_MODE, key(create = createKey))
        if (cipher.iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected record IV size")
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        if (ciphertext.size != plaintext.size + TAG_BYTES) {
            throw GeneralSecurityException("Unexpected record tag size")
        }
        return HEADER + cipher.iv + ciphertext
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        if (!hasEnvelope(encrypted) || encrypted.size < ENVELOPE_OVERHEAD_BYTES) {
            throw GeneralSecurityException("Unsupported private record envelope")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Losing a key is a recovery error, never permission to generate a replacement here.
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(create = false),
            GCMParameterSpec(
                TAG_BYTES * 8,
                encrypted.copyOfRange(HEADER.size, HEADER.size + IV_BYTES),
            ),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(
            encrypted,
            HEADER.size + IV_BYTES,
            encrypted.size - HEADER.size - IV_BYTES,
        )
    }

    private fun key(create: Boolean): SecretKey = keyScope?.key(create) ?: loadKey(keyContext, alias, create)

    /** One open/transaction owns this opaque AndroidKeyStore handle, never key material. */
    internal class KeyScope(context: Context) : Closeable {
        private val keyContext = context.applicationContext
        internal val alias = alias(context)
        private var handle: SecretKey? = null
        private var closed = false

        @Synchronized
        internal fun key(create: Boolean): SecretKey {
            if (closed) throw GeneralSecurityException("Private record key scope is closed")
            return handle ?: loadKey(keyContext, alias, create).also { handle = it }
        }

        @Synchronized
        override fun close() {
            closed = true
            handle = null
        }
    }

    companion object {
        private val HEADER = byteArrayOf(0x4f, 0x4e, 0x55, 0x31) // ONU1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        const val ENVELOPE_OVERHEAD_BYTES = 4 + IV_BYTES + TAG_BYTES
        private val keyLock = Any()

        private fun alias(context: Context) = "onu.records.v1." + MessageDigest.getInstance("SHA-256")
            .digest(context.applicationContext.noBackupFilesDir.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)

        private fun loadKey(context: Context, alias: String, create: Boolean): SecretKey = synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null)
            if (existing != null) {
                return@synchronized existing as? SecretKey
                    ?: throw GeneralSecurityException("Unexpected private record key type")
            }
            if (!create || store.containsAlias(alias) || hasEncryptedRecords(context)) {
                throw GeneralSecurityException("Private record key is unavailable")
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build()
                    )
                }
                .generateKey()
        }

        /** All writers share this guard, including first mode/profile saves after key loss. */
        private fun hasEncryptedRecords(context: Context): Boolean {
            val family = context.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
            if (family.contains("profile_v3") || family.contains("interaction_v1")) return true
            return context.noBackupFilesDir.listFiles()?.any { file ->
                file.isFile && (file.name.endsWith(".records.sqlite") || file.inputStream().use { input ->
                    val header = ByteArray(4)
                    input.read(header) == 4 && hasEnvelope(header)
                })
            } ?: false
        }

        fun hasEnvelope(bytes: ByteArray): Boolean =
            bytes.size >= HEADER.size && HEADER.indices.all { bytes[it] == HEADER[it] }
    }
}
