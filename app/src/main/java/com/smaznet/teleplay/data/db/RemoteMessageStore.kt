package com.smaznet.teleplay.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.smaznet.teleplay.domain.remote.RemoteMessage

/** Persistence of the single Now-playing message row. Blocking; call off the main thread. */
interface RemoteMessageStore {
    fun get(): RemoteMessage?
    fun put(m: RemoteMessage)
    fun clear()
}

class SqliteRemoteMessageStore(private val db: TelePlayDb) : RemoteMessageStore {
    override fun get(): RemoteMessage? = db.readableDatabase.rawQuery(
        "SELECT chatUserId, messageId, sessionId, lastTextHash, pinned, updatedAt FROM remote_message WHERE id = 1", null,
    ).use { c ->
        if (!c.moveToFirst()) null else RemoteMessage(
            c.getLong(0), c.getInt(1), c.getString(2), c.getInt(3), c.getInt(4) != 0, c.getLong(5),
        )
    }

    override fun put(m: RemoteMessage) {
        db.writableDatabase.insertWithOnConflict(
            "remote_message", null,
            ContentValues().apply {
                put("id", 1); put("chatUserId", m.chatUserId); put("messageId", m.messageId)
                put("sessionId", m.sessionId); put("lastTextHash", m.lastTextHash)
                put("pinned", if (m.pinned) 1 else 0); put("updatedAt", m.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun clear() {
        db.writableDatabase.delete("remote_message", null, null)
    }
}
