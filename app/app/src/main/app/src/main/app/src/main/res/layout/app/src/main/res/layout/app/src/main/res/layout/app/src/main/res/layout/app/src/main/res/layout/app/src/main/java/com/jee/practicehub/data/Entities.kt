package com.jee.practicehub.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_books")
data class PdfBook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val localFilePath: String,
    val totalPages: Int,
    val addedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = PdfBook::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val startPage: Int,
    val endPage: Int
)

@Entity(
    tableName = "question_logs",
    foreignKeys = [ForeignKey(
        entity = Chapter::class,
        parentColumns = ["id"],
        childColumns = ["chapterId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("chapterId")]
)
data class QuestionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val questionNumber: Int,
    val status: String, // "CORRECT", "INCORRECT", "SKIPPED"
    val timeTakenSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChapterWithStats(
    val id: Long,
    val bookId: Long,
    val name: String,
    val startPage: Int,
    val endPage: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val skippedCount: Int
) {
    val attemptedCount: Int get() = correctCount + incorrectCount
    val totalLogged: Int get() = correctCount + incorrectCount + skippedCount
    val accuracyPercent: Int
        get() = if (attemptedCount == 0) 0 else ((correctCount * 100.0) / attemptedCount).toInt()
}
