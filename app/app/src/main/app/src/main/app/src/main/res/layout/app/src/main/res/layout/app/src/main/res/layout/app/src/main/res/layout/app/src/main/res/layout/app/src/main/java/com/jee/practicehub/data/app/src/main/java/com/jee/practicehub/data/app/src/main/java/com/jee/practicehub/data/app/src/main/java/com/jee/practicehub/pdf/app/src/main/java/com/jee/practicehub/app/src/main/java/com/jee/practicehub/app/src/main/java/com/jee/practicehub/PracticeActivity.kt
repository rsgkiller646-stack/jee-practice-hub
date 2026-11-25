package com.jee.practicehub

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jee.practicehub.data.AppDatabase
import com.jee.practicehub.data.Chapter
import com.jee.practicehub.data.PdfBook
import com.jee.practicehub.data.QuestionLog
import com.jee.practicehub.databinding.ActivityPracticeBinding
import com.jee.practicehub.pdf.PdfUtils
import kotlinx.coroutines.launch
import java.io.File

class PracticeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPracticeBinding
    private val db by lazy { AppDatabase.getDatabase(this) }

    private var chapter: Chapter? = null
    private var book: PdfBook? = null
    private var currentPage: Int = 1

    private var elapsedSeconds: Long = 0L
    private var isRunning: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                elapsedSeconds++
                updateStopwatchDisplay()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private var nextQuestionNumber = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPracticeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val chapterId = intent.getLongExtra("CHAPTER_ID", -1L)
        if (chapterId == -1L) {
            finish()
            return
        }

        setupStopwatchControls()
        setupQuestionButtons()

        lifecycleScope.launch {
            val loadedChapter = db.appDao().getChapterById(chapterId) ?: return@launch
            val loadedBook = db.appDao().getBookById(loadedChapter.bookId) ?: return@launch
            chapter = loadedChapter
            book = loadedBook
            currentPage = loadedChapter.startPage

            binding.tvPracticeTitle.text = loadedChapter.name
            renderCurrentPdfPage()

            db.appDao().getLogsForChapter(chapterId).collect { logs ->
                nextQuestionNumber = logs.size + 1
                val correct = logs.count { it.status == "CORRECT" }
                val incorrect = logs.count { it.status == "INCORRECT" }
                val skipped = logs.count { it.status == "SKIPPED" }
                val attempted = correct + incorrect
                binding.tvSessionSummary.text =
                    "Next: Q#$nextQuestionNumber • Attempted: $attempted | Correct: $correct | Wrong: $incorrect | Skipped: $skipped"
            }
        }

        binding.btnPrevPage.setOnClickListener {
            val c = chapter ?: return@setOnClickListener
            if (currentPage > c.startPage) {
                currentPage--
                renderCurrentPdfPage()
            }
        }

        binding.btnNextPage.setOnClickListener {
            val c = chapter ?: return@setOnClickListener
            if (currentPage < c.endPage) {
                currentPage++
                renderCurrentPdfPage()
            }
        }
    }

    private fun renderCurrentPdfPage() {
        val c = chapter ?: return
        val b = book ?: return
        val relativePage = currentPage - c.startPage + 1
        val totalChapterPages = c.endPage - c.startPage + 1
        binding.tvPageIndicator.text = "Page $relativePage of $totalChapterPages (PDF p.$currentPage)"

        lifecycleScope.launch {
            val bitmap = PdfUtils.renderPageBitmap(File(b.localFilePath), currentPage - 1)
            binding.ivPdfPage.setImageBitmap(bitmap)
        }
    }

    private fun setupStopwatchControls() {
        binding.btnStartPause.setOnClickListener {
            if (isRunning) {
                pauseStopwatch()
            } else {
                startStopwatch()
            }
        }

        binding.btnResetWatch.setOnClickListener {
            pauseStopwatch()
            elapsedSeconds = 0L
            updateStopwatchDisplay()
        }
    }

    private fun startStopwatch() {
        if (!isRunning) {
            isRunning = true
            binding.btnStartPause.text = "Stop"
            handler.postDelayed(timerRunnable, 1000L)
        }
    }

    private fun pauseStopwatch() {
        isRunning = false
        binding.btnStartPause.text = "Start"
        handler.removeCallbacks(timerRunnable)
    }

    private fun updateStopwatchDisplay() {
        val mins = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        binding.tvStopwatch.text = String.format("%02d:%02d", mins, secs)
    }

    private fun setupQuestionButtons() {
        binding.btnMarkCorrect.setOnClickListener { logOutcome("CORRECT") }
        binding.btnMarkIncorrect.setOnClickListener { logOutcome("INCORRECT") }
        binding.btnMarkSkipped.setOnClickListener { logOutcome("SKIPPED") }
    }

    private fun logOutcome(status: String) {
        val c = chapter ?: return
        val timeForThisQuestion = elapsedSeconds

        lifecycleScope.launch {
            db.appDao().insertQuestionLog(
                QuestionLog(
                    chapterId = c.id,
                    questionNumber = nextQuestionNumber,
                    status = status,
                    timeTakenSeconds = timeForThisQuestion
                )
            )
            elapsedSeconds = 0L
            updateStopwatchDisplay()
            Toast.makeText(
                this@PracticeActivity,
                "Q#${nextQuestionNumber - 1} saved as $status (${timeForThisQuestion}s)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
    }
}
