package com.wodcapture.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wodcapture.domain.model.AthleteProfile
import com.wodcapture.domain.model.ConversionMode
import com.wodcapture.domain.parser.CloudParserConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App settings (REQ-AG01–AG03, REQ-AX10). Persisted as one JSON document in DataStore. */
@Serializable
data class AppSettings(
    val profile: AthleteProfile = AthleteProfile(),
    val profileRevision: Int = 1,
    /** "Cloud parsing" toggle (REQ-AX10). */
    val cloudParsing: Boolean = true,
    /** First-use consent that images/text go to Anthropic (REQ-AX10). */
    val consentGiven: Boolean = false,
    val model: String = CloudParserConfig.DEFAULT_MODEL,
    val effort: String = "medium",
    /** Pairing generation (REQ-DM27, SY11); incremented by delete-all. */
    val generation: Int = 1,
    val selectedDeviceId: String? = null,
    /** Connect IQ `TETHERED` mode for the simulator (REQ-SY08). */
    val tethered: Boolean = false,
    val useBetaApp: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** DataStore-backed settings. No secrets live here (see [SecretStore]). */
class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        (p[key]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()).migrated()
    }

    /** The Conversion choice left Settings (REQ-AG01); an old "arithmetic" pick must not linger unseen. */
    private fun AppSettings.migrated(): AppSettings =
        if (profile.conversionMode == ConversionMode.EQUIVALENTS) this
        else copy(profile = profile.copy(conversionMode = ConversionMode.EQUIVALENTS))

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
            p[key] = json.encodeToString(AppSettings.serializer(), transform(cur))
        }
    }
}

/**
 * The athlete's Anthropic API key, encrypted with an Android Keystore AES-GCM key (REQ-AX11,
 * REQ-NF07). Stored in private prefs excluded from backup; never logged.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun saveApiKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(PREF_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun apiKey(): String? {
        val iv = prefs.getString(PREF_IV, null) ?: return null
        val ct = prefs.getString(PREF_CT, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun hasApiKey(): Boolean = prefs.contains(PREF_CT)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wod_capture_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREF_IV = "iv"
        const val PREF_CT = "ct"
    }
}
