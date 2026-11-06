package com.smaznet.teleplay.data.db

import android.content.ContentValues
import com.smaznet.teleplay.data.telegram.UpdateState
import com.smaznet.teleplay.data.telegram.UpdateStateStore

/** Persists the `updates.getDifference` cursor in the single-row `update_state` table. */
class SqliteUpdateStateStore(private val db: TelePlayDb) : UpdateStateStore {
    override fun load(): UpdateState? =
        db.readableDatabase.rawQuery("SELECT pts, qts, date, seq FROM update_state WHERE id = 1", null).use {
            if (it.moveToFirst()) UpdateState(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) else null
        }

    override fun save(state: UpdateState) {
        val v = ContentValues().apply {
            put("id", 1); put("pts", state.pts); put("qts", state.qts); put("date", state.date); put("seq", state.seq)
        }
        db.writableDatabase.insertWithOnConflict("update_state", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun clear() {
        db.writableDatabase.delete("update_state", null, null)
    }
}
