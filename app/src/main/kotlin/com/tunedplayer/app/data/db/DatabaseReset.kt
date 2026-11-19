package com.tunedplayer.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Empties every table, as on a fresh install, except the ones named in [keep]. The tables are read from the database
 * itself, so a table a later version adds is emptied too without anyone listing it here. Runs in one transaction:
 * either everything is gone or nothing is.
 */
fun TunedDatabase.wipe(keep: Set<String> = emptySet()) {
    runInTransaction(Runnable { empty(openHelper.writableDatabase, keep) })
}

private fun empty(
    sql: SupportSQLiteDatabase,
    keep: Set<String>,
) {
    val tables = tablesToWipe(sql, keep)
    sql.execSQL("PRAGMA defer_foreign_keys = TRUE")
    tables.forEach { sql.execSQL("DELETE FROM `$it`") }
    // New rows count from 1 again, as on a fresh install.
    if (names(sql, "SELECT name FROM sqlite_master WHERE name = 'sqlite_sequence'").isNotEmpty()) {
        sql.execSQL("DELETE FROM sqlite_sequence WHERE name IN (${tables.joinToString { "'$it'" }})")
    }
    // A fresh install starts with one profile, in use.
    TunedDatabase.addFirstProfile(sql)
    // The search index follows the items through Room's triggers; a rebuild makes sure nothing is left over.
    virtualTables(sql).forEach { sql.execSQL("INSERT INTO `$it`(`$it`) VALUES('rebuild')") }
}

/** Room's and SQLite's own bookkeeping, which a wipe must leave alone. */
private val SYSTEM = setOf("android_metadata", "room_master_table", "room_table_modification_log")

private fun tablesToWipe(
    sql: SupportSQLiteDatabase,
    keep: Set<String>,
): List<String> {
    val virtual = virtualTables(sql)
    return names(sql, "SELECT name FROM sqlite_master WHERE type = 'table' AND sql NOT LIKE 'CREATE VIRTUAL TABLE%'")
        .filterNot { it in SYSTEM || it in keep || it.startsWith("sqlite_") }
        // A full-text index keeps its words in shadow tables (items_fts_content and so on); SQLite owns those.
        .filterNot { name -> virtual.any { name.startsWith("${it}_") } }
}

private fun virtualTables(sql: SupportSQLiteDatabase): List<String> =
    names(sql, "SELECT name FROM sqlite_master WHERE type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%'")

private fun names(
    sql: SupportSQLiteDatabase,
    query: String,
): List<String> = sql.query(query).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
