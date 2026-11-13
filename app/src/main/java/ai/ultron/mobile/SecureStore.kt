package ai.ultron.mobile
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context, "ultron_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun get(key: String): String = prefs.getString(key, "") ?: ""
    fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}
