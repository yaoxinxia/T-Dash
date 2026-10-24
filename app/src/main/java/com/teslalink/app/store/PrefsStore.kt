package com.teslalink.app.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.teslaDataStore: DataStore<Preferences> by preferencesDataStore(name = "teslalink")

/** Small wrapper around a Preferences DataStore. */
class PrefsStore(private val context: Context) {

    suspend fun getString(key: String): String? =
        context.teslaDataStore.data.first().get(stringPreferencesKey(key))

    suspend fun putString(key: String, value: String?) {
        context.teslaDataStore.edit { prefs ->
            val k = stringPreferencesKey(key)
            if (value == null) prefs.remove(k) else prefs[k] = value
        }
    }

    /**
     * Atomic multi-key write. Used for the key pair: two separate edits could leave a public key
     * whose private counterpart no longer exists, which reads on the car as "key forgotten".
     */
    suspend fun putStrings(values: Map<String, String?>) {
        context.teslaDataStore.edit { prefs ->
            values.forEach { (key, value) ->
                val k = stringPreferencesKey(key)
                if (value == null) prefs.remove(k) else prefs[k] = value
            }
        }
    }

    suspend fun remove(key: String) = putString(key, null)
}
