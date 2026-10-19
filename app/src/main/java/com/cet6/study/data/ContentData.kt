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
    fun readyWordCount(): Int = wordCount()

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
        if (limit <= 0) return emptyList()
        val rows = database.rawQuery(
            "SELECT word_id FROM words ORDER BY rank_no,word_id LIMIT ?",
            arrayOf((limit + excluded.size).toString())
        ).use { c ->
            buildList { while (c.moveToNext()) { val id = c.getInt(0); if (id !in excluded && size < limit) add(id) } }
        }
        return rows
    }

    fun getWordFormSources(): List<WordFormSource> {
        val sql = """
            SELECT w.word_id,w.rank_no,w.lemma,w.frequency_level,w.total_count,w.paper_count,
                   COALESCE(GROUP_CONCAT(DISTINCT s.meaning_zh), '') AS meanings,
                   (SELECT COUNT(*) FROM word_examples e JOIN word_senses sx ON sx.sense_id=e.sense_id
                    WHERE sx.word_id=w.word_id AND e.is_default=1) AS example_count,
                   COALESCE(GROUP_CONCAT(DISTINCT s.pos), '') AS parts_of_speech
            FROM words w LEFT JOIN word_senses s ON s.word_id=w.word_id
            GROUP BY w.word_id ORDER BY w.rank_no,w.word_id
        """.trimIndent()
        return database.rawQuery(sql, emptyArray<String>()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val card=WordCard(c.getInt(0),c.getInt(1),c.getString(2),c.getString(3),c.getInt(4),c.getInt(5),c.getString(6),c.getInt(7))
                    add(WordFormSource(card,c.getString(8) ?: ""))
                }
            }
        }
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

    fun getExcellentWritingPaperIds(): Set<String> = database.rawQuery("""
        SELECT DISTINCT p.paper_id
        FROM papers p
        JOIN content_units u ON u.paper_id=p.paper_id AND u.module='writing'
        JOIN writing_samples s ON s.prompt_unit_id=u.unit_id
        WHERE s.sample_type='excellent'
    """.trimIndent(), emptyArray<String>()).use { c ->
        buildSet { while(c.moveToNext()) add(c.getString(0)) }
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
            SELECT u.unit_id,u.passage_no,u.paragraph_no,u.order_no,u.text,COALESCE(d.translation_zh,''),COALESCE(d.structure_analysis_zh,'')
            FROM content_units u LEFT JOIN reading_details d ON d.unit_id=u.unit_id
            WHERE u.paper_id=? AND u.module='reading' AND u.content_type='text' AND u.reading_type=?
            ORDER BY COALESCE(u.passage_no,0),COALESCE(u.paragraph_no,0),COALESCE(u.order_no,0),u.unit_id
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(actual, readingType)).use { c ->
            buildList { while (c.moveToNext()) add(ReadingSentence(c.getInt(0), if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getInt(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6))) }
        }
    }

    fun getQuestions(paperId: String, readingType: String): List<ReadingQuestion> {
        val actual = readingSource(paperId)
        val sql = """
            SELECT q.unit_id,q.question_no,q.text,COALESCE(a.answer_label,''),COALESCE(o.text,''),
                   COALESCE(a.explanation_zh,''),COALESCE(e.text,''),a.evidence_unit_id
            FROM content_units q LEFT JOIN reading_answers a ON a.question_unit_id=q.unit_id
            LEFT JOIN content_units o ON o.unit_id=a.correct_option_unit_id
            LEFT JOIN content_units e ON e.unit_id=a.evidence_unit_id
            WHERE q.paper_id=? AND q.module='reading' AND q.content_type='question' AND q.reading_type=?
            ORDER BY q.order_no,q.question_no,q.unit_id
        """.trimIndent()
        return database.rawQuery(sql, arrayOf(actual, readingType)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val unitId = c.getInt(0)
                    val options = if (readingType == "close_reading") database.rawQuery(
                        "SELECT unit_id,option_label,text FROM content_units WHERE parent_unit_id=? AND content_type='option' ORDER BY order_no,unit_id",
                        arrayOf(unitId.toString())
                    ).use { optionCursor ->
                        buildList {
                            while (optionCursor.moveToNext()) add(ReadingOption(optionCursor.getInt(0), optionCursor.getString(1) ?: "", optionCursor.getString(2) ?: ""))
                        }
                    } else emptyList()
                    add(ReadingQuestion(
                        unitId=unitId,
                        questionNo=if(c.isNull(1)) null else c.getInt(1),
                        text=c.getString(2) ?: "",
                        answer=c.getString(3) ?: "",
                        optionText=c.getString(4) ?: "",
                        explanation=c.getString(5) ?: "",
                        evidence=c.getString(6) ?: "",
                        evidenceUnitId=if(c.isNull(7)) null else c.getInt(7),
                        options=options
                    ))
                }
            }
        }
    }

    fun getWordBank(paperId: String): List<String> {
        val actual = readingSource(paperId)
        return database.rawQuery(
            "SELECT text FROM content_units WHERE paper_id=? AND module='reading' AND content_type='word_bank' AND reading_type='word_bank' ORDER BY order_no,unit_id",
            arrayOf(actual)
        ).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) ?: "") } }
    }

    fun getTranslations(paperId: String): List<TranslationItem> = getUnits(paperId, "translation").map { unit ->
        val detail = database.rawQuery("SELECT reference_en,optimized_en FROM translation_details WHERE unit_id=?", arrayOf(unit.unitId.toString())).use { c -> if(c.moveToFirst()) c.getString(0) to c.getString(1) else "" to "" }
        TranslationItem(unit.unitId, unit.orderNo, unit.text, detail.first, detail.second)
    }

    fun getWriting(paperId: String): WritingItem? {
        val prompt = getUnits(paperId, "writing").firstOrNull() ?: return null
        val samples = database.rawQuery("SELECT sample_id,sample_type,text,highlights_json FROM writing_samples WHERE prompt_unit_id=? ORDER BY CASE sample_type WHEN 'reference' THEN 0 ELSE 1 END,sample_id", arrayOf(prompt.unitId.toString())).use { c ->
            buildList { while(c.moveToNext()) add(WritingSample(c.getLong(0),c.getString(1),c.getString(2),c.getString(3) ?: "[]")) }
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
data class WordFormSource(val word: WordCard,val partsOfSpeech: String)
data class WordDetail(val wordId: Int,val rank: Int,val lemma: String,val level: String,val totalCount: Int,val paperCount: Int,val senses: List<WordSense>)
data class WordSense(val senseId: Int,val pos: String,val meaning: String,val priority: Int,val examples: List<WordExample> = emptyList())
data class WordExample(val english: String,val chinese: String,val source: String)
data class PaperCard(val paperId: String,val year: Int,val month: Int,val setNo: Int,val readingSourceId: String,val itemCount: Int) { val title get() = "${year}年${month}月 第${setNo}套" }
data class ReadingSentence(val unitId: Int,val passageNo: Int?,val paragraphNo: Int?,val orderNo: Int,val text: String,val translation: String,val analysis: String)
data class ReadingOption(val unitId:Int,val label:String,val text:String)
data class ReadingQuestion(val unitId: Int,val questionNo: Int?,val text: String,val answer: String,val optionText: String,val explanation: String,val evidence: String,val evidenceUnitId:Int?=null,val options:List<ReadingOption> = emptyList())
data class BasicUnit(val unitId: Int,val orderNo: Int,val text: String)
data class TranslationItem(val unitId: Int,val orderNo: Int,val chinese: String,val reference: String,val optimized: String)
data class WritingSample(val sampleId: Long,val type: String,val text: String,val highlightsJson: String)
data class WritingItem(val promptUnitId: Int,val prompt: String,val samples: List<WritingSample>)

@Entity(tableName = "user_word_status")
data class UserWordStatus(
    @PrimaryKey @androidx.room.ColumnInfo(name="word_id") val wordId: Int,
    val familiarity: String,
    @androidx.room.ColumnInfo(name="next_review") val nextReview: Long,
    @androidx.room.ColumnInfo(name="last_reviewed") val lastReviewed: Long,
    @androidx.room.ColumnInfo(name="review_count") val reviewCount: Int,
    @androidx.room.ColumnInfo(name="review_stage", defaultValue = "0") val reviewStage: Int = 0,
    @androidx.room.ColumnInfo(name="is_mastered", defaultValue = "0") val isMastered: Boolean = false,
    @androidx.room.ColumnInfo(name="is_learning_complete", defaultValue = "1") val isLearningComplete: Boolean = true
)

@Entity(tableName = "user_word_folders", indices = [Index(value = ["name"], unique = true)])
data class UserWordFolder(
    @PrimaryKey(autoGenerate = true) @androidx.room.ColumnInfo(name = "folder_id") val folderId: Long = 0,
    val name: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_word_favorites", indices = [Index(value = ["folder_id"])])
data class UserWordFavorite(
    @PrimaryKey @androidx.room.ColumnInfo(name = "word_id") val wordId: Int,
    @androidx.room.ColumnInfo(name = "folder_id") val folderId: Long,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_reading_status", indices = [Index(value = ["paper_id", "reading_type", "passage_no"])])
data class UserReadingStatus(
    @PrimaryKey @androidx.room.ColumnInfo(name = "unit_id") val unitId: Int,
    @androidx.room.ColumnInfo(name = "paper_id") val paperId: String,
    @androidx.room.ColumnInfo(name = "reading_type") val readingType: String,
    @androidx.room.ColumnInfo(name = "passage_no") val passageNo: Int,
    val familiarity: String,
    @androidx.room.ColumnInfo(name = "studied_at") val studiedAt: Long = System.currentTimeMillis()
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
    @androidx.room.ColumnInfo(name="updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(name="source_text", defaultValue = "''") val sourceText: String = "",
    @androidx.room.ColumnInfo(name="review_stage", defaultValue = "0") val reviewStage: Int = 0,
    @androidx.room.ColumnInfo(name="next_review", defaultValue = "0") val nextReview: Long = 0L,
    @androidx.room.ColumnInfo(name="last_reviewed", defaultValue = "0") val lastReviewed: Long = 0L,
    @androidx.room.ColumnInfo(name="review_count", defaultValue = "0") val reviewCount: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "''") val familiarity: String = "",
    @androidx.room.ColumnInfo(name="is_mastered", defaultValue = "0") val isMastered: Boolean = false
)

@Entity(tableName = "user_review_queue", primaryKeys = ["entry_type", "item_id"], indices = [Index(value = ["queue_order"])])
data class UserReviewQueueItem(
    @androidx.room.ColumnInfo(name = "entry_type") val entryType: String,
    @androidx.room.ColumnInfo(name = "item_id") val itemId: Long,
    @androidx.room.ColumnInfo(name = "queue_order") val queueOrder: Long,
    @androidx.room.ColumnInfo(name = "queued_at") val queuedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_note_highlights", indices = [Index(value = ["note_id"])])
data class UserNoteHighlight(
    @PrimaryKey(autoGenerate = true) @androidx.room.ColumnInfo(name = "highlight_id") val highlightId: Long = 0,
    @androidx.room.ColumnInfo(name = "note_id") val noteId: Long,
    @androidx.room.ColumnInfo(name = "start_offset") val startOffset: Int,
    @androidx.room.ColumnInfo(name = "end_offset") val endOffset: Int,
    @androidx.room.ColumnInfo(name = "selected_text") val selectedText: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_writing_highlights", indices = [Index(value = ["sample_id"])])
data class UserWritingHighlight(
    @PrimaryKey(autoGenerate = true) @androidx.room.ColumnInfo(name = "highlight_id") val highlightId: Long = 0,
    @androidx.room.ColumnInfo(name = "sample_id") val sampleId: Long,
    @androidx.room.ColumnInfo(name = "start_offset") val startOffset: Int,
    @androidx.room.ColumnInfo(name = "end_offset") val endOffset: Int,
    @androidx.room.ColumnInfo(name = "selected_text") val selectedText: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_writing_texts")
data class UserWritingText(
    @PrimaryKey @androidx.room.ColumnInfo(name = "sample_id") val sampleId: Long,
    @androidx.room.ColumnInfo(name = "text_override") val textOverride: String,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface UserDao {
    @Query("SELECT * FROM user_word_status WHERE word_id=:wordId") suspend fun status(wordId: Int): UserWordStatus?
    @Query("SELECT * FROM user_word_status WHERE next_review<=:now AND is_mastered=0 AND is_learning_complete=1 ORDER BY next_review,word_id") suspend fun dueWordStatuses(now: Long): List<UserWordStatus>
    @Query("SELECT word_id FROM user_word_status WHERE next_review<=:now AND is_mastered=0 AND is_learning_complete=1 ORDER BY next_review,word_id") suspend fun dueWordIds(now: Long): List<Int>
    @Query("SELECT word_id FROM user_word_status") suspend fun studiedWordIds(): List<Int>
    @Query("SELECT word_id FROM user_word_status WHERE is_learning_complete=0 AND is_mastered=0 ORDER BY last_reviewed,word_id") suspend fun incompleteWordIds(): List<Int>
    @Query("SELECT * FROM user_word_folders ORDER BY created_at,folder_id") suspend fun wordFolders(): List<UserWordFolder>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWordFolder(folder: UserWordFolder): Long
    @Query("SELECT * FROM user_word_favorites WHERE word_id=:wordId LIMIT 1") suspend fun wordFavorite(wordId: Int): UserWordFavorite?
    @Query("SELECT * FROM user_word_favorites WHERE folder_id=:folderId ORDER BY created_at DESC,word_id") suspend fun wordFavorites(folderId: Long): List<UserWordFavorite>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWordFavorite(favorite: UserWordFavorite)
    @Query("DELETE FROM user_word_favorites WHERE word_id=:wordId") suspend fun removeWordFavorite(wordId: Int)
    @Query("SELECT COUNT(*) FROM user_word_status WHERE next_review<=:now AND is_mastered=0 AND is_learning_complete=1") suspend fun dueCount(now: Long): Int
    @Query("SELECT COUNT(*) FROM user_word_status WHERE is_learning_complete=1") suspend fun studiedCount(): Int
    @Query("SELECT * FROM user_reading_status WHERE paper_id=:paperId AND reading_type=:readingType") suspend fun readingStatuses(paperId: String, readingType: String): List<UserReadingStatus>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReadingStatus(status: UserReadingStatus)
    @Query("SELECT * FROM user_writing_highlights WHERE sample_id=:sampleId ORDER BY start_offset,end_offset") suspend fun writingHighlights(sampleId: Long): List<UserWritingHighlight>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWritingHighlight(highlight: UserWritingHighlight)
    @Query("SELECT * FROM user_writing_texts WHERE sample_id=:sampleId") suspend fun writingText(sampleId: Long): UserWritingText?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWritingText(text: UserWritingText)
    @Query("SELECT * FROM user_notes WHERE next_review<=:now AND is_mastered=0 ORDER BY next_review,noteId") suspend fun dueNotes(now: Long): List<UserNote>
    @Query("SELECT COUNT(*) FROM user_notes WHERE next_review<=:now AND is_mastered=0") suspend fun dueNoteCount(now: Long): Int
    @Query("UPDATE user_notes SET is_mastered=:mastered, next_review=:nextReview WHERE noteId=:noteId") suspend fun setNoteMastered(noteId: Long, mastered: Boolean, nextReview: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveStatus(status: UserWordStatus)
    @Query("UPDATE user_word_status SET is_mastered=:mastered, familiarity=CASE WHEN :mastered=1 THEN '彻底熟悉' ELSE familiarity END, next_review=:nextReview, is_learning_complete=CASE WHEN :mastered=1 THEN 1 ELSE is_learning_complete END WHERE word_id=:wordId")
    suspend fun setWordMastered(wordId: Int, mastered: Boolean, nextReview: Long)
    @Transaction suspend fun markWordMastered(wordId: Int, nextReview: Long) {
        val current = status(wordId)
        if (current == null) {
            saveStatus(UserWordStatus(wordId=wordId,familiarity="彻底熟悉",nextReview=nextReview,lastReviewed=System.currentTimeMillis(),reviewCount=1,reviewStage=0,isMastered=true,isLearningComplete=true))
        } else {
            setWordMastered(wordId,true,nextReview)
        }
    }
    @Insert suspend fun insertReviewHistory(item: UserReviewHistory)
    @Transaction suspend fun recordReview(status: UserWordStatus, history: UserReviewHistory) {
        saveStatus(status)
        insertReviewHistory(history)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveNote(note: UserNote): Long
    @Query("SELECT * FROM user_notes WHERE item_type=:type AND item_id=:itemId ORDER BY updated_at DESC") suspend fun notes(type: String,itemId: Int): List<UserNote>
    @Query("SELECT * FROM user_notes ORDER BY updated_at DESC") suspend fun allNotes(): List<UserNote>
    @Query("SELECT * FROM user_notes WHERE noteId=:noteId LIMIT 1") suspend fun noteById(noteId: Long): UserNote?
    @Query("SELECT * FROM user_review_queue ORDER BY queue_order,entry_type,item_id") suspend fun reviewQueueItems(): List<UserReviewQueueItem>
    @Query("SELECT COALESCE(MAX(queue_order),0) FROM user_review_queue") suspend fun maxReviewQueueOrder(): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueueReviewItems(items: List<UserReviewQueueItem>): List<Long>
    @Query("DELETE FROM user_review_queue WHERE entry_type=:entryType AND item_id=:itemId") suspend fun removeReviewQueueItem(entryType: String,itemId: Long)
    @Query("UPDATE user_review_queue SET queue_order=:queueOrder WHERE entry_type=:entryType AND item_id=:itemId") suspend fun setReviewQueueOrder(entryType: String,itemId: Long,queueOrder: Long)
    @Transaction suspend fun moveReviewQueueItemToEnd(entryType: String,itemId: Long) {
        setReviewQueueOrder(entryType,itemId,maxReviewQueueOrder()+1)
    }
    @Query("SELECT * FROM user_note_highlights WHERE note_id=:noteId ORDER BY start_offset,end_offset") suspend fun noteHighlights(noteId: Long): List<UserNoteHighlight>
    @Query("SELECT * FROM user_note_highlights") suspend fun allNoteHighlights(): List<UserNoteHighlight>
    @Query("DELETE FROM user_note_highlights WHERE note_id=:noteId") suspend fun clearNoteHighlights(noteId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveNoteHighlights(highlights: List<UserNoteHighlight>)
    @Transaction suspend fun replaceNoteHighlights(noteId: Long, highlights: List<UserNoteHighlight>) {
        clearNoteHighlights(noteId)
        if (highlights.isNotEmpty()) saveNoteHighlights(highlights)
    }
}

@Database(entities = [UserWordStatus::class,UserNote::class,UserNoteHighlight::class,UserReviewHistory::class,UserReadingStatus::class,UserWritingHighlight::class,UserWritingText::class,UserWordFolder::class,UserWordFavorite::class,UserReviewQueueItem::class],version = 11,exportSchema = false)
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_notes ADD COLUMN source_text TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE user_notes ADD COLUMN review_stage INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_notes ADD COLUMN next_review INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_notes ADD COLUMN last_reviewed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_notes ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_notes ADD COLUMN familiarity TEXT NOT NULL DEFAULT ''")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_reading_status (
                        unit_id INTEGER NOT NULL PRIMARY KEY,
                        paper_id TEXT NOT NULL,
                        reading_type TEXT NOT NULL,
                        passage_no INTEGER NOT NULL,
                        familiarity TEXT NOT NULL,
                        studied_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_reading_status_paper_id_reading_type_passage_no ON user_reading_status(paper_id, reading_type, passage_no)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Version 4 keeps the version 3 user-data schema and adds app behavior only.
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_word_status ADD COLUMN is_mastered INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_writing_highlights (
                        highlight_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sample_id INTEGER NOT NULL,
                        start_offset INTEGER NOT NULL,
                        end_offset INTEGER NOT NULL,
                        selected_text TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_writing_highlights_sample_id ON user_writing_highlights(sample_id)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_writing_texts (
                        sample_id INTEGER NOT NULL PRIMARY KEY,
                        text_override TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_note_highlights (
                        highlight_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        note_id INTEGER NOT NULL,
                        start_offset INTEGER NOT NULL,
                        end_offset INTEGER NOT NULL,
                        selected_text TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_note_highlights_note_id ON user_note_highlights(note_id)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_word_status ADD COLUMN is_learning_complete INTEGER NOT NULL DEFAULT 1")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_word_folders (
                        folder_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_user_word_folders_name ON user_word_folders(name)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_word_favorites (
                        word_id INTEGER NOT NULL PRIMARY KEY,
                        folder_id INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_word_favorites_folder_id ON user_word_favorites(folder_id)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_notes ADD COLUMN is_mastered INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_review_queue (
                        entry_type TEXT NOT NULL,
                        item_id INTEGER NOT NULL,
                        queue_order INTEGER NOT NULL,
                        queued_at INTEGER NOT NULL,
                        PRIMARY KEY(entry_type, item_id)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_review_queue_queue_order ON user_review_queue(queue_order)")
            }
        }

        fun get(context: Context): UserDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,UserDatabase::class.java,"user_data.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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
        "了解", "有点了解", "不会" -> (safeStage - 1).coerceAtLeast(0)
        else -> 0
    }
    val days = intervals[nextStage]
    return ReviewSchedule(nextStage, days, reviewedAt + days * 24L * 60L * 60L * 1000L)
}
fun todayStartMillis(): Long = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
