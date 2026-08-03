package com.dhaval.echo.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * The little bit of SQLite that [DatabaseRewriter] needs.
 *
 * It has to run against two different things: a staged database file opened
 * directly during a restore, and the *live* database through Room's handle when
 * an account re-key is applied after a deferred sign-in. Those are
 * [SQLiteDatabase] and [SupportSQLiteDatabase], which share no common type
 * despite offering the same three operations. One interface here is cheaper
 * than two copies of the re-keying logic — and two copies of that particular
 * logic is how they drift apart and one of them starts orphaning rows.
 */
interface SqlHandle {
    fun exec(sql: String, args: Array<Any?>? = null)
    fun query(sql: String, args: Array<Any?>? = null): Cursor
    fun close()

    companion object {
        /** Opens a database file directly, bypassing Room. */
        fun open(file: File): SqlHandle = FrameworkHandle(
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        )

        /**
         * Wraps Room's live connection. Closing is a no-op — the caller
         * borrowed the app's database and must not shut it down.
         */
        fun of(database: SupportSQLiteDatabase): SqlHandle = SupportHandle(database)
    }
}

private class FrameworkHandle(private val db: SQLiteDatabase) : SqlHandle {
    override fun exec(sql: String, args: Array<Any?>?) {
        if (args == null) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun query(sql: String, args: Array<Any?>?): Cursor =
        db.rawQuery(sql, args?.map { it?.toString() }?.toTypedArray())

    override fun close() {
        runCatching { db.close() }
    }
}

private class SupportHandle(private val db: SupportSQLiteDatabase) : SqlHandle {
    override fun exec(sql: String, args: Array<Any?>?) {
        if (args == null) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun query(sql: String, args: Array<Any?>?): Cursor =
        if (args == null) db.query(sql) else db.query(sql, args)

    /** Borrowed, not owned. */
    override fun close() = Unit
}
