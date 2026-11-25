package com.jee.practicehub.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert
    suspend fun insertBook(book: PdfBook): Long

    @Delete
    suspend fun deleteBook(book: PdfBook)

    @Query("SELECT * FROM pdf_books ORDER BY addedTimestamp DESC")
    fun getAllBooks(): Flow<List<PdfBook>>

    @Query("SELECT * FROM pdf_books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): PdfBook?

    @Insert
    suspend fun insertChapters(chapters: List<Chapter>)

    @Insert
    suspend fun insertChapter(chapter: Chapter): Long

    @Query("""
        SELECT c.id, c.bookId, c.name, c.startPage, c.endPage,
            COALESCE(SUM(CASE WHEN q.status = 'CORRECT' THEN 1 ELSE 0 END), 0) AS correctCount,
            COALESCE(SUM(CASE WHEN q.status = 'INCORRECT' THEN 1 ELSE 0 END), 0) AS incorrectCount,
            COALESCE(SUM(CASE WHEN q.status = 'SKIPPED' THEN 1 ELSE 0 END), 0) AS skippedCount
        FROM chapters c
        LEFT JOIN question_logs q ON c.id = q.chapterId
        WHERE c.bookId = :bookId
        GROUP BY c.id
        ORDER BY c.startPage ASC
    """)
    fun getChaptersWithStats(bookId: Long): Flow<List<ChapterWithStats>>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getChapterById(chapterId: Long): Chapter?

    @Insert
    suspend fun insertQuestionLog(log: QuestionLog)

    @Query("SELECT * FROM question_logs WHERE chapterId = :chapterId ORDER BY id DESC")
    fun getLogsForChapter(chapterId: Long): Flow<List<QuestionLog>>
}
