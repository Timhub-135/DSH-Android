package io.github.timhub135.dsh.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secret storage: AES-GCM with a non-exportable Android Keystore key, files in noBackupFilesDir
 * (excluded from backups). The unnamed slot is the DeepSeek API key; named slots hold third-party
 * provider keys (`provider-<routeId>`), passed to the engine only as environment variables.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = context.noBackupFilesDir
    private val file = File(dir, "deepseek.key")
    private fun named(name: String) = File(dir, "secret-" + name.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".key")

    fun read(): String? = read(file)
    fun write(value: String) = write(file, value)
    fun clear() { file.delete() }

    fun read(name: String): String? = read(named(name))
    fun write(name: String, value: String) = write(named(name), value)
    fun clear(name: String) { named(name).delete() }

    private fun read(file: File): String? = runCatching {
        if (!file.exists()) return null
        val (iv, data) = file.readText().split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(data))
    }.getOrNull()

    private fun write(file: File, value: String) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.toByteArray())
        file.writeText(Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "dsh.apikey"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
