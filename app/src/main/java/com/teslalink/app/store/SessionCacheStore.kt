package com.teslalink.app.store

import android.content.Context
import android.util.Base64
import com.teslalink.app.protocol.Dispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists per-VIN protocol session cache so the first command skips the handshake. */
class SessionCacheStore(context: Context) {
    private val prefs = PrefsStore(context)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Entry(val domain: Int, val data: String, val createdAtWallMs: Long)

    @Serializable
    private data class CacheFile(val vin: String, val entries: List<Entry> = emptyList())

    suspend fun load(vin: String): List<Dispatcher.CachedSession> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return try {
            val file = json.decodeFromString<CacheFile>(raw)
            if (file.vin != vin) return emptyList()
            file.entries.map {
                Dispatcher.CachedSession(
                    it.domain,
                    Base64.decode(it.data, Base64.NO_WRAP),
                    it.createdAtWallMs,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun save(vin: String, sessions: List<Dispatcher.CachedSession>) {
        val file = CacheFile(
            vin,
            sessions.map {
                Entry(it.domain, Base64.encodeToString(it.encodedInfo, Base64.NO_WRAP), it.createdAtWallMs)
            }
        )
        prefs.putString(KEY, json.encodeToString(file))
    }

    suspend fun clear() = prefs.remove(KEY)

    companion object {
        private const val KEY = "session_cache"
    }
}
