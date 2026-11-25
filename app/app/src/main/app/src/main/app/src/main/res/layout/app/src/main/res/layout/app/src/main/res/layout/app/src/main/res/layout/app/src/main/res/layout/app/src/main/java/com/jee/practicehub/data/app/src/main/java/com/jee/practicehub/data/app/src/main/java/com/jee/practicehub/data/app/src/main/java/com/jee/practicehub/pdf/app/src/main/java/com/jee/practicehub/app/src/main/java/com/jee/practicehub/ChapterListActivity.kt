package com.jee.practicehub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jee.practicehub.data.AppDatabase
import com.jee.practicehub.data.Chapter
import com.jee.practicehub.data.ChapterWithStats
import com.jee.practicehub.data.PdfBook
import com.jee.practicehub.databinding.ActivityChapterListBinding
import com.jee.practicehub.databinding.ItemChapterBinding
import com.jee.practicehub.pdf.PdfUtils
import kotlinx.coroutines.launch
import java.io.File

class ChapterListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChapterListBinding
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var bookId: Long = -1L
    private var currentBook: PdfBook? = null
    private lateinit var adapter: ChapterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChapterListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra("BOOK_ID", -1L)
        if (bookId == -1L) {
            finish()
            return
        }

        adapter = ChapterAdapter(
            onPractice = { chapter ->
                val intent = Intent(this, PracticeActivity::class.java)
                intent.putExtra("CHAPTER_ID", chapter.id)
                startActivity(intent)
            },
            onExport = { chapter ->
                exportSingleChapter(chapter)
            }
        )

        binding.rvChapters.layoutManager = LinearLayoutManager(this)
        binding.rvChapters.adapter = adapter

        binding.fabAddChapterManual.setOnClickListener {
            showAddChapterDialog()
        }

        lifecycleScope.launch {
            currentBook = db.appDao().getBookById(bookId)
            binding.tvHeaderBookTitle.text = currentBook?.title ?: "Chapters"

            db.appDao().getChaptersWithStats(bookId).collect { chapters ->
                adapter.submitList(chapters)
                val totalCorrect = chapters.sumOf { it.correctCount }
                val totalAttempted = chapters.sumOf { it.attemptedCount }
                val totalSkipped = chapters.sumOf { it.skippedCount }
                val overallAcc = if (totalAttempted == 0) 0 else (totalCorrect * 100) / totalAttempted
                binding.tvOverallStats.text =
                    "Book Progress • Attempted: $totalAttempted | Correct: $totalCorrect | Skipped: $totalSkipped | Accuracy: $overallAcc%"
            }
        }
    }

    private fun exportSingleChapter(chapter: ChapterWithStats) {
        val book = currentBook ?: return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ChapterListActivity, "Generating chapter PDF...", Toast.LENGTH_SHORT).show()
                val exportedFile = PdfUtils.exportChapterPdf(
                    context = this@ChapterListActivity,
                    sourcePdf = File(book.localFilePath),
                    chapterName = chapter.name,
                    startPage = chapter.startPage,
                    endPage = chapter.endPage
                )

                val uri = FileProvider.getUriForFile(
                    this@ChapterListActivity,
                    "${packageName}.fileprovider",
                    exportedFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export or Preview Chapter PDF"))
            } catch (e: Exception) {
                Toast.makeText(this@ChapterListActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddChapterDialog() {
        val book = currentBook ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val etName = EditText(this).apply { hint = "Chapter Name (e.g., Rotational Motion)" }
        val etStart = EditText(this).apply {
            hint = "Start Page (1 to ${book.totalPages})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etEnd = EditText(this).apply {
            hint = "End Page (1 to ${book.totalPages})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(etName)
        layout.addView(etStart)
        layout.addView(etEnd)

        AlertDialog.Builder(this)
            .setTitle("Add Chapter Section")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val start = etStart.text.toString().toIntOrNull() ?: 1
                val end = etEnd.text.toString().toIntOrNull() ?: book.totalPages
                if (name.isNotEmpty() && start in 1..end && end <= book.totalPages) {
                    lifecycleScope.launch {
                        db.appDao().insertChapter(
                            Chapter(bookId = book.id, name = name, startPage = start, endPage = end)
                        )
                    }
                } else {
                    Toast.makeText(this, "Invalid chapter name or page range", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class ChapterAdapter(
        private val onPractice: (ChapterWithStats) -> Unit,
        private val onExport: (ChapterWithStats) -> Unit
    ) : RecyclerView.Adapter<ChapterAdapter.ChapterVH>() {

        private var items = listOf<ChapterWithStats>()

        fun submitList(newItems: List<ChapterWithStats>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ChapterVH(val b: ItemChapterBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterVH {
            val b = ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ChapterVH(b)
        }

        override fun onBindViewHolder(holder: ChapterVH, position: Int) {
            val c = items[position]
            holder.b.tvChapterName.text = c.name
            holder.b.tvPageRange.text = "Pages ${c.startPage} – ${c.endPage}"
            holder.b.tvChapterStats.text =
                "Attempted: ${c.attemptedCount}  |  ✓ ${c.correctCount}  |  ✕ ${c.incorrectCount}  |  Skipped: ${c.skippedCount}  |  Accuracy: ${c.accuracyPercent}%"

            holder.b.btnPracticeChapter.setOnClickListener { onPractice(c) }
            holder.b.btnExportChapter.setOnClickListener { onExport(c) }
        }

        override fun getItemCount() = items.size
    }
}
