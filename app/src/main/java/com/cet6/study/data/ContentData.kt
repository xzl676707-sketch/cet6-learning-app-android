package com.cet6.study.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

private fun SQLiteDatabase.string(c: android.database.Cursor, name: String): String = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
private fun SQLiteDatabase.int(c: android.database.Cursor, name: String): Int = c.getInt(c.getColumnIndexOrThrow(name))
private fun SQLiteDatabase.long(c: android.database.Cursor, name: String): Long = c.getLong(c.getColumnIndexOrThrow(name))
private fun SQLiteDatabase.nullableString(c: android.database.Cursor, name: String): String? {
    val i = c.getColumnIndexOrThrow(name)
    return if (c.isNull(i)) null else c.getString(i)
}

class ContentDb private constructor(context: Context) {
    private val app = context.applicationContext
    private val dbFile = File(app.getDatabasePath("cet6_content.db").absolutePath)
    private val database: SQLiteDatabase

    init {
        dbFile.parentFile?.mkdirs()
        val assetVersion = app.assets.open("content_version.txt").bufferedReader().use { it.readText().trim() }
        val prefs = app.getSharedPreferences("content_db", Context.MODE_PRIVATE)
        val savedVersion = prefs.getString("version", null)
        if (!dbFile.exists() || savedVersion != assetVersion) {
            val temp = File(dbFile.parentFile, "cet6_content.tmp")
            app.assets.open("cet6_content.db").use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output); output.fd.sync() }
            }
            if (dbFile.exists()) dbFile.delete()
            check(temp.renameTo(dbFile)) { "无法安装离线内容库" }
            prefs.edit().putString("version", assetVersion).apply()
        }
        database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun wordCount(): Int = database.rawQuery("SELECT COUNT(*) FROM words", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun readyWordCount(): Int = database.rawQuery("""
        SELECT COUNT(*) FROM (
          SELECT w.word_id FROM words w
          LEFT JOIN word_senses s ON s.word_id=w.word_id
          LEFT JOIN word_examples e ON e.sense_id=s.sense_id AND e.is_default=1
          GROUP BY w.word_id HAVING COUNT(e.example_link_id)>=3
        )
    """.trimIndent(), null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun getWords(search: String = "", limit: Int = 250): List<WordCard> {
        val pattern = "%${search.trim()}%"
        val sql = """
            SELECT w.word_id,w.rank_no,w.lemma,w.frequency_level,w.total_count,w.paper_count,
                   COALESCE(GROUP_CONCAT(DISTINCT s.meaning_zh), '') AS meanings,
                   (SELECT COUNT(*) FROM word_examples e JOIN word_senses sx ON sx.sense_id=e.sense_id
                    WHERE sx.word_id=w.word_id AND e.is_default=1) AS example_count
            FROM words w LEFT JOIN word_senses s ON s.word_id=w.word_id
            WHERE (?='' OR w.lemma LIKE ? OR s.meaning_zh LIKE ?)
            GROUP BY w.word_id ORDER BY w.rank_no LIMIT ?
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(search.trim(), pattern, pattern, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(WordCard(c.getInt(0), c.getInt(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getString(6), c.getInt(7)))
            }
        }
    }

    fun getNewWordIds(limit: Int, excluded: Set<Int>): List<Int> {
        val rows = database.rawQuery("""
            SELECT w.word_id FROM words w
            JOIN word_senses s ON s.word_id=w.word_id
            JOIN word_examples e ON e.sense_id=s.sense_id AND e.is_default=1
            GROUP BY w.word_id,w.rank_no HAVING COUNT(e.example_link_id)>=3
            ORDER BY w.rank_no LIMIT ?
        """.trimIndent(), arrayOf((limit + excluded.size).toString())).use { c ->
            buildList { while (c.moveToNext()) { val id = c.getInt(0); if (id !in excluded && size < limit) add(id) } }
        }
        return rows
    }

    fun getWord(wordId: Int): WordDetail? {
        val word = database.rawQuery("SELECT word_id,rank_no,lemma,frequency_level,total_count,paper_count FROM words WHERE word_id=?", arrayOf(wordId.toString())).use { c ->
            if (!c.moveToFirst()) null else WordDetail(c.getInt(0), c.getInt(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), emptyList())
        } ?: return null
        val senses = database.rawQuery("SELECT sense_id,pos,meaning_zh,priority FROM word_senses WHERE word_id=? ORDER BY priority", arrayOf(wordId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(WordSense(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3))) }
        }.map { sense ->
            val examples = database.rawQuery("""
                SELECT COALESCE(cu.text,se.text_en) AS english,
                       COALESCE(rd.translation_zh,se.text_zh,'') AS chinese,
                       CASE WHEN e.source_type='exam' THEN COALESCE(p.year||'年'||p.month||'月真题','真题例句') ELSE COALESCE(se.source_name,'期刊补充例句') END AS source
                FROM word_examples e
                LEFT JOIN content_units cu ON cu.unit_id=e.unit_id
                LEFT JOIN reading_details rd ON rd.unit_id=cu.unit_id
                LEFT JOIN supplemental_examples se ON se.example_id=e.supplemental_example_id
                LEFT JOIN papers p ON p.paper_id=cu.paper_id
                WHERE e.sense_id=? AND e.is_default=1
                ORDER BY e.display_rank,e.example_link_id
            """.trimIndent(), arrayOf(sense.senseId.toString())).use { c ->
                buildList { while (c.moveToNext()) add(WordExample(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "例句")) }
            }
            sense.copy(examples = examples)
        }
        return word.copy(senses = senses)
    }

    fun getPapers(module: String): List<PaperCard> {
        val sql = """
            SELECT p.paper_id,p.year,p.month,p.set_no,p.reading_source_paper_id,
                   (SELECT COUNT(*) FROM content_units u WHERE u.paper_id=p.paper_id AND u.module=?) AS item_count
            FROM papers p ORDER BY p.year DESC,p.month DESC,p.set_no DESC
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(module)).use { c ->
            buildList { while (c.moveToNext()) add(PaperCard(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getString(4), c.getInt(5))) }
        }
    }

    fun getReadingTypes(paperId: String): List<String> {
        val actual = readingSource(paperId)
        return database.rawQuery("SELECT DISTINCT reading_type FROM content_units WHERE paper_id=? AND module='reading' AND content_type='text' AND reading_type IS NOT NULL ORDER BY reading_type", arrayOf(actual)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun getReadingSentences(paperId: String, readingType: String): List<ReadingSentence> {
        val actual = readingSource(paperId)
        val sql = """
            SELECT u.unit_id,u.paragraph_no,u.order_no,u.text,COALESCE(d.translation_zh,''),COALESCE(d.structure_analysis_zh,'')
            FROM content_units u LEFT JOIN reading_details d ON d.unit_id=u.unit_id
            WHERE u.paper_id=? AND u.module='reading' AND u.content_type='text' AND u.reading_type=?
            ORDER BY COALESCE(u.passage_no,0),COALESCE(u.paragraph_no,0),COALESCE(u.order_no,0),u.unit_id
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(actual, readingType)).use { c ->
            buildList { while (c.moveToNext()) add(ReadingSentence(c.getInt(0), if (c.isNull(1)) null else c.getInt(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))) }
        }
    }

    fun getQuestions(paperId: String, readingType: String): List<ReadingQuestion> {
        val actual = readingSource(paperId)
        val sql = """
            SELECT q.unit_id,q.question_no,q.text,a.answer_label,COALESCE(o.text,''),COALESCE(a.explanation_zh,''),COALESCE(e.text,'')
            FROM content_units q LEFT JOIN reading_answers a ON a.question_unit_id=q.unit_id
            LEFT JOIN content_units o ON o.unit_id=a.correct_option_unit_id
            LEFT JOIN content_units e ON e.unit_id=a.evidence_unit_id
            WHERE q.paper_id=? AND q.module='reading' AND q.content_type='question' AND q.reading_type=?
            ORDER BY q.order_no,q.question_no,q.unit_id
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(actual, readingType)).use { c ->
            buildList { while (c.moveToNext()) add(ReadingQuestion(c.getInt(0), if(c.isNull(1)) null else c.getInt(1), c.getString(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "")) }
        }
    }

    fun getTranslations(paperId: String): List<TranslationItem> = getUnits(paperId, "translation").map { unit ->
        val detail = database.rawQuery("SELECT reference_en,optimized_en FROM translation_details WHERE unit_id=?", arrayOf(unit.unitId.toString())).use { c -> if(c.moveToFirst()) c.getString(0) to c.getString(1) else "" to "" }
        TranslationItem(unit.unitId, unit.orderNo, unit.text, detail.first, detail.second)
    }

    fun getWriting(paperId: String): WritingItem? {
        val prompt = getUnits(paperId, "writing").firstOrNull() ?: return null
        val samples = database.rawQuery("SELECT sample_type,text,highlights_json FROM writing_samples WHERE prompt_unit_id=? ORDER BY CASE sample_type WHEN 'reference' THEN 0 ELSE 1 END,sample_id", arrayOf(prompt.unitId.toString())).use { c ->
            buildList { while(c.moveToNext()) add(WritingSample(c.getString(0),c.getString(1),c.getString(2) ?: "[]")) }
        }
        return WritingItem(prompt.unitId, prompt.text, samples)
    }

    private fun getUnits(paperId: String, module: String): List<BasicUnit> = database.rawQuery(
        "SELECT unit_id,order_no,text FROM content_units WHERE paper_id=? AND module=? ORDER BY order_no,unit_id",
        arrayOf(paperId, module)
    ).use { c -> buildList { while(c.moveToNext()) add(BasicUnit(c.getInt(0),c.getInt(1),c.getString(2))) } }

    private fun readingSource(paperId: String): String = database.rawQuery("SELECT reading_source_paper_id FROM papers WHERE paper_id=?", arrayOf(paperId)).use { c -> if(c.moveToFirst()) c.getString(0) ?: paperId else paperId }

    companion object {
        @Volatile private var instance: ContentDb? = null
        fun get(context: Context): ContentDb = instance ?: synchronized(this) { instance ?: ContentDb(context).also { instance = it } }
    }
}

data class WordCard(val wordId: Int,val rank: Int,val lemma: String,val level: String,val totalCount: Int,val paperCount: Int,val meanings: String,val exampleCount: Int)
data class WordDetail(val wordId: Int,val rank: Int,val lemma: String,val level: String,val totalCount: Int,val paperCount: Int,val senses: List<WordSense>)
data class WordSense(val senseId: Int,val pos: String,val meaning: String,val priority: Int,val examples: List<WordExample> = emptyList())
data class WordExample(val english: String,val chinese: String,val source: String)
data class PaperCard(val paperId: String,val year: Int,val month: Int,val setNo: Int,val readingSourceId: String,val itemCount: Int) { val title get() = "${year}年${month}月 第${setNo}套" }
data class ReadingSentence(val unitId: Int,val paragraphNo: Int?,val orderNo: Int,val text: String,val translation: String,val analysis: String)
data class ReadingQuestion(val unitId: Int,val questionNo: Int?,val text: String,val answer: String,val optionText: String,val explanation: String,val evidence: String)
data class BasicUnit(val unitId: Int,val orderNo: Int,val text: String)
data class TranslationItem(val unitId: Int,val orderNo: Int,val chinese: String,val reference: String,val optimized: String)
data class WritingSample(val type: String,val text: String,val highlightsJson: String)
data class WritingItem(val promptUnitId: Int,val prompt: String,val samples: List<WritingSample>)

@Entity(tableName = "user_word_status")
data class UserWordStatus(
    @PrimaryKey @androidx.room.ColumnInfo(name="word_id") val wordId: Int,
    val familiarity: String,
    @androidx.room.ColumnInfo(name="next_review") val nextReview: Long,
    @androidx.room.ColumnInfo(name="last_reviewed") val lastReviewed: Long,
    @androidx.room.ColumnInfo(name="review_count") val reviewCount: Int,
    @androidx.room.ColumnInfo(name="review_stage") val reviewStage: Int = 0
)

@Entity(
    tableName = "user_review_history",
    indices = [Index(value = ["word_id", "reviewed_at"])]
)
data class UserReviewHistory(
    @PrimaryKey(autoGenerate = true) @androidx.room.ColumnInfo(name = "review_id") val reviewId: Long = 0,
    @androidx.room.ColumnInfo(name = "word_id") val wordId: Int,
    @androidx.room.ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    val familiarity: String,
    @androidx.room.ColumnInfo(name = "stage_before") val stageBefore: Int,
    @androidx.room.ColumnInfo(name = "stage_after") val stageAfter: Int,
    @androidx.room.ColumnInfo(name = "interval_days") val intervalDays: Int
)

@Entity(tableName = "user_notes")
data class UserNote(
    @PrimaryKey(autoGenerate = true) val noteId: Long = 0,
    @androidx.room.ColumnInfo(name="item_type") val itemType: String,
    @androidx.room.ColumnInfo(name="item_id") val itemId: Int,
    val content: String,
    @androidx.room.ColumnInfo(name="updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface UserDao {
    @Query("SELECT * FROM user_word_status WHERE word_id=:wordId") suspend fun status(wordId: Int): UserWordStatus?
    @Query("SELECT word_id FROM user_word_status WHERE next_review<=:now ORDER BY next_review") suspend fun dueWordIds(now: Long): List<Int>
    @Query("SELECT word_id FROM user_word_status") suspend fun studiedWordIds(): List<Int>
    @Query("SELECT COUNT(*) FROM user_word_status WHERE next_review<=:now") suspend fun dueCount(now: Long): Int
    @Query("SELECT COUNT(*) FROM user_word_status") suspend fun studiedCount(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveStatus(status: UserWordStatus)
    @Insert suspend fun insertReviewHistory(item: UserReviewHistory)
    @Transaction suspend fun recordReview(status: UserWordStatus, history: UserReviewHistory) {
        saveStatus(status)
        insertReviewHistory(history)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveNote(note: UserNote)
    @Query("SELECT * FROM user_notes WHERE item_type=:type AND item_id=:itemId ORDER BY updated_at DESC") suspend fun notes(type: String,itemId: Int): List<UserNote>
}

@Database(entities = [UserWordStatus::class,UserNote::class,UserReviewHistory::class],version = 2,exportSchema = false)
abstract class UserDatabase : RoomDatabase() {
    abstract fun dao(): UserDao
    companion object {
        @Volatile private var instance: UserDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_word_status ADD COLUMN review_stage INTEGER NOT NULL DEFAULT 0")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_review_history (
                        review_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word_id INTEGER NOT NULL,
                        reviewed_at INTEGER NOT NULL,
                        familiarity TEXT NOT NULL,
                        stage_before INTEGER NOT NULL,
                        stage_after INTEGER NOT NULL,
                        interval_days INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_review_history_word_id_reviewed_at ON user_review_history(word_id, reviewed_at)")
            }
        }

        fun get(context: Context): UserDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,UserDatabase::class.java,"user_data.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance=it }
        }
    }
}

data class ReviewSchedule(val stage: Int, val intervalDays: Int, val nextReviewMillis: Long)

fun scheduleNextReview(familiarity: String, currentStage: Int = -1, reviewedAt: Long = System.currentTimeMillis()): ReviewSchedule {
    val intervals = listOf(1, 3, 7, 15, 30, 60)
    val safeStage = currentStage.coerceIn(-1, intervals.lastIndex)
    val nextStage = when (familiarity) {
        "熟悉" -> (safeStage + 1).coerceIn(0, intervals.lastIndex)
        "了解", "有点了解" -> (safeStage - 1).coerceAtLeast(0)
        else -> 0
    }
    val days = intervals[nextStage]
    return ReviewSchedule(nextStage, days, reviewedAt + days * 24L * 60L * 60L * 1000L)
}
fun todayStartMillis(): Long = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
