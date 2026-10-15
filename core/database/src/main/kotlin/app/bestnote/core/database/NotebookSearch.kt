package app.bestnote.core.database

import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Title search over the index. Queries of 3+ characters use an FTS5 table with the `trigram`
 * tokenizer (substring matches, works for Arabic, which word tokenizers handle poorly); shorter
 * queries fall back to `LIKE`. The FTS table is kept in sync with `notebooks` by triggers and is
 * created (and back-filled) on open, so fresh installs and migrated databases both get it.
 */
class NotebookSearch(private val db: BestNoteDatabase) {
    /** Notebooks whose title contains [text] (case-insensitive), newest first. */
    suspend fun search(text: String, limit: Int = DEFAULT_LIMIT): List<NotebookEntity> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val dao = db.notebookDao()
        return if (q.codePointCount(0, q.length) >= TRIGRAM) {
            dao.rawNotebooks(RoomRawQuery(FTS_QUERY) { it.bindText(1, phrase(q)); it.bindLong(2, limit.toLong()) })
        } else {
            dao.rawNotebooks(RoomRawQuery(LIKE_QUERY) { it.bindText(1, "%${escapeLike(q)}%"); it.bindLong(2, limit.toLong()) })
        }
    }

    /** Creates the FTS table and triggers when missing. */
    object Callback : RoomDatabase.Callback() {
        override fun onOpen(connection: SQLiteConnection) = ensureFts(connection)
    }

    companion object {
        const val FTS_TABLE = "notebooks_fts"
        private const val DEFAULT_LIMIT = 200
        private const val TRIGRAM = 3

        private const val FTS_QUERY =
            "SELECT notebooks.* FROM notebooks JOIN $FTS_TABLE ON $FTS_TABLE.id = notebooks.id " +
                "WHERE $FTS_TABLE MATCH ? ORDER BY notebooks.updatedAtMillis DESC LIMIT ?"
        private const val LIKE_QUERY =
            "SELECT * FROM notebooks WHERE title LIKE ? ESCAPE '\\' ORDER BY updatedAtMillis DESC LIMIT ?"

        /** One FTS5 phrase: the user's text is matched literally, never parsed as query syntax. */
        internal fun phrase(text: String) = "\"" + text.replace("\"", "\"\"") + "\""

        internal fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        // Keyed by notebook id (not rowid) so REPLACE upserts can never leave stale entries behind.
        private val CREATE = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts5(id UNINDEXED, title, tokenize = 'trigram')",
            "CREATE TRIGGER IF NOT EXISTS notebooks_fts_insert AFTER INSERT ON notebooks BEGIN " +
                "DELETE FROM $FTS_TABLE WHERE id = new.id; INSERT INTO $FTS_TABLE(id, title) VALUES (new.id, new.title); END",
            "CREATE TRIGGER IF NOT EXISTS notebooks_fts_update AFTER UPDATE OF id, title ON notebooks BEGIN " +
                "DELETE FROM $FTS_TABLE WHERE id = old.id; INSERT INTO $FTS_TABLE(id, title) VALUES (new.id, new.title); END",
            "CREATE TRIGGER IF NOT EXISTS notebooks_fts_delete AFTER DELETE ON notebooks BEGIN " +
                "DELETE FROM $FTS_TABLE WHERE id = old.id; END",
        )

        internal fun ensureFts(connection: SQLiteConnection) {
            val exists = connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$FTS_TABLE'").use { it.step() }
            if (exists) return
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                CREATE.forEach(connection::execSQL)
                connection.execSQL("INSERT INTO $FTS_TABLE(id, title) SELECT id, title FROM notebooks")
                connection.execSQL("COMMIT")
            } catch (e: Exception) {
                connection.execSQL("ROLLBACK")
                throw e
            }
        }
    }
}
