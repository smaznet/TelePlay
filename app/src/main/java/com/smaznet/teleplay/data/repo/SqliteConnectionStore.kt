package com.smaznet.teleplay.data.repo

import android.content.ContentValues
import com.smaznet.teleplay.data.db.TelePlayDb
import com.smaznet.teleplay.domain.BotConnection
import com.smaznet.teleplay.domain.ConnectionState

class SqliteConnectionStore(private val db: TelePlayDb) : ConnectionStore {
    override fun load(): BotConnection? = db.readableDatabase.rawQuery(
        "SELECT botUserId, botName, homeDcId, state, authorizedUserId, pairingCodeHash, pairingExpiresAt, authorizedUserAccessHash FROM bot_connection WHERE id = 1",
        null,
    ).use { c ->
        if (!c.moveToFirst()) null else BotConnection(
            botUserId = c.getLong(0), botName = c.getString(1), homeDcId = c.getInt(2),
            state = ConnectionState.valueOf(c.getString(3)),
            authorizedUserId = if (c.isNull(4)) null else c.getLong(4),
            pairingCodeHash = if (c.isNull(5)) null else c.getString(5),
            pairingExpiresAt = if (c.isNull(6)) null else c.getLong(6),
            authorizedUserAccessHash = if (c.isNull(7)) null else c.getLong(7),
        )
    }

    override fun save(connection: BotConnection) {
        db.writableDatabase.insertWithOnConflict(
            "bot_connection", null,
            ContentValues().apply {
                put("id", 1); put("botUserId", connection.botUserId); put("botName", connection.botName)
                put("homeDcId", connection.homeDcId); put("state", connection.state.name)
                put("authorizedUserId", connection.authorizedUserId)
                put("pairingCodeHash", connection.pairingCodeHash)
                put("pairingExpiresAt", connection.pairingExpiresAt)
                put("authorizedUserAccessHash", connection.authorizedUserAccessHash)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun clear() {
        db.writableDatabase.delete("bot_connection", null, null)
    }
}
