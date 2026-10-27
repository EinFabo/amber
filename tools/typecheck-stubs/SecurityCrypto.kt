package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences

class MasterKey private constructor() {
    enum class KeyScheme { AES256_GCM }

    class Builder(context: Context) {
        fun setKeyScheme(keyScheme: KeyScheme): Builder = this
        fun build(): MasterKey = throw UnsupportedOperationException()
    }
}

object EncryptedSharedPreferences {
    enum class PrefKeyEncryptionScheme { AES256_SIV }
    enum class PrefValueEncryptionScheme { AES256_GCM }

    fun create(
        context: Context,
        fileName: String,
        masterKey: MasterKey,
        prefKeyEncryptionScheme: PrefKeyEncryptionScheme,
        prefValueEncryptionScheme: PrefValueEncryptionScheme,
    ): SharedPreferences = throw UnsupportedOperationException()
}
