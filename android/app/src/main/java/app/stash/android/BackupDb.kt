package app.stash.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Remembers which phone files are already safe on the server, so a backup run only touches new/changed files.
 * Key = MediaStore content URI; a row only counts while size + modified time still match.
 * status: "uploaded" (we sent it), "present" (server already had identical bytes), "skipped" (empty file).
 * Files over the server's size limit are NOT recorded, so they are tried again once the limit is raised.
 */
class BackupDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "backup.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                   uri TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL,
                   sha256 TEXT NOT NULL, status TEXT NOT NULL, ts INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 also stored over-limit files as "skipped" and never looked at them again. Forget every skipped row once:
        // empty files are skipped again at once, over-limit files are retried if the server limit has been raised.
        if (oldVersion < 2) db.execSQL("DELETE FROM items WHERE status = 'skipped'")
    }

    fun isDone(uri: String, size: Long, modified: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM items WHERE uri = ? AND size = ? AND modified = ?",
            arrayOf(uri, size.toString(), modified.toString()),
        ).use { it.moveToFirst() }

    fun mark(uri: String, size: Long, modified: Long, sha256: String, status: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO items (uri, size, modified, sha256, status, ts) VALUES (?,?,?,?,?,?)",
            arrayOf<Any>(uri, size, modified, sha256, status, System.currentTimeMillis()),
        )
    }

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM items")
    }
}
