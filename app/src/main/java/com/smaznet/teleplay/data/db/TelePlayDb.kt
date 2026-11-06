package com.smaznet.teleplay.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local database for connection state, update cursor, video history, progress and the cache index.
 * Secrets (token, auth keys) are NOT stored here; see SecretStore.
 *
 * Enum columns hold the names of: ConnectionState (SIGNED_OUT, CONNECTING, AWAITING_PAIRING, READY,
 * REJECTED, REVOKED, OFFLINE) and Availability (PLAYABLE, UNPLAYABLE_FORMAT, REMOTE_GONE).
 */
class TelePlayDb(context: Context, name: String? = "teleplay.db") :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE bot_connection (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                botUserId INTEGER NOT NULL,
                botName TEXT NOT NULL,
                homeDcId INTEGER NOT NULL,
                state TEXT NOT NULL,
                authorizedUserId INTEGER,
                authorizedUserAccessHash INTEGER,
                pairingCodeHash TEXT,
                pairingExpiresAt INTEGER
            )""",
        )
        db.execSQL(
            """CREATE TABLE update_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                pts INTEGER NOT NULL, qts INTEGER NOT NULL, date INTEGER NOT NULL, seq INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE video (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chatUserId INTEGER NOT NULL,
                messageId INTEGER NOT NULL,
                documentId INTEGER NOT NULL,
                accessHash INTEGER NOT NULL,
                fileReference BLOB NOT NULL,
                dcId INTEGER NOT NULL,
                title TEXT NOT NULL,
                durationSec INTEGER NOT NULL,
                sizeBytes INTEGER NOT NULL,
                mimeType TEXT NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                thumbSize TEXT,
                receivedAt INTEGER NOT NULL,
                availability TEXT NOT NULL,
                UNIQUE (chatUserId, messageId)
            )""",
        )
        db.execSQL("CREATE INDEX video_document ON video(documentId)")
        db.execSQL("CREATE INDEX video_received ON video(receivedAt DESC)")
        db.execSQL(
            """CREATE TABLE playback_progress (
                videoId INTEGER PRIMARY KEY REFERENCES video(id) ON DELETE CASCADE,
                positionMs INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE cache_entry (
                documentId INTEGER PRIMARY KEY,
                sizeOnDisk INTEGER NOT NULL,
                lastAccess INTEGER NOT NULL
            )""",
        )
        createRemoteMessage(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE bot_connection ADD COLUMN authorizedUserAccessHash INTEGER")
            createRemoteMessage(db)
        }
    }

    private fun createRemoteMessage(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE remote_message (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                chatUserId INTEGER NOT NULL,
                messageId INTEGER NOT NULL,
                sessionId TEXT NOT NULL,
                lastTextHash INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )""",
        )
    }

    /** Deletes all rows (used by disconnect and reset). */
    fun clearAll() {
        writableDatabase.apply {
            beginTransaction()
            try {
                for (t in listOf("playback_progress", "video", "update_state", "bot_connection", "cache_entry", "remote_message")) {
                    delete(t, null, null)
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    companion object {
        const val VERSION = 2
    }
}
