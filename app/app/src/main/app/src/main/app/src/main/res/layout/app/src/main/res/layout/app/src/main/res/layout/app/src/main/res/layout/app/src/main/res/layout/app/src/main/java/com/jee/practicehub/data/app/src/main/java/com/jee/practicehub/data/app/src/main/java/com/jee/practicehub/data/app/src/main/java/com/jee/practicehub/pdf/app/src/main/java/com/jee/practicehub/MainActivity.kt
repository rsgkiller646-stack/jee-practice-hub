package com.jee.practicehub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jee.practicehub.data.AppDatabase
import com.jee.practicehub.data.PdfBook
import com.jee.practicehub.databinding.ActivityMainBinding
import com.jee.practicehub.databinding.ItemBookBinding
import com.jee.practicehub.pdf.PdfUtils
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: BookAdapter

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importPdfWithOcr(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BookAdapter(
            onClick = { book ->
                val intent = Intent(this, ChapterListActivity::class.java)
                intent.putExtra("BOOK_ID", book.id)
                startActivity(intent)
            },
            onLongClick = { book ->
                confirmDeleteBook(book)
            }
        )

        binding.rvBooks.layoutManager = LinearLayoutManager(this)
        binding.rvBooks.adapter = adapter

        binding.fabAddPdf.setOnClickListener {
            pickPdfLauncher.launch(arrayOf("application/pdf"))
        }

        lifecycleScope.launch {
            db.appDao().getAllBooks().collect { books ->
                adapter.submitList(books)
                binding.tvEmptyLibrary.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun importPdfWithOcr(uri: Uri) {
        binding.progressOcr.visibility = View.VISIBLE
        binding.fabAddPdf.isEnabled = false

        lifecycleScope.launch {
            try {
                val title = queryFileName(uri).removeSuffix(".pdf")
                val (copiedFile, totalPages) = PdfUtils.copyPdfToLibrary(this@MainActivity, uri, title)
                val bookId = db.appDao().insertBook(
                    PdfBook(
                        title = title,
                        localFilePath = copiedFile.absolutePath,
                        totalPages = totalPages
                    )
                )

                val chapters = PdfUtils.detectChaptersViaOcr(copiedFile, bookId, totalPages)
                db.appDao().insertChapters(chapters)

                Toast.makeText(
                    this@MainActivity,
                    "Added '$title' (${chapters.size} chapter section(s) detected)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error importing PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressOcr.visibility = View.GONE
                binding.fabAddPdf.isEnabled = true
            }
        }
    }

    private fun confirmDeleteBook(book: PdfBook) {
        AlertDialog.Builder(this)
            .setTitle("Remove PDF?")
            .setMessage("Remove '${book.title}' and its practice logs from the app?")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    File(book.localFilePath).delete()
                    db.appDao().deleteBook(book)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun queryFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIdx >= 0) {
                return cursor.getString(nameIdx)
            }
        }
        return "JEE_Practice_PDF.pdf"
    }

    class BookAdapter(
        private val onClick: (PdfBook) -> Unit,
        private val onLongClick: (PdfBook) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.BookVH>() {

        private var items = listOf<PdfBook>()

        fun submitList(newItems: List<PdfBook>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class BookVH(val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookVH {
            val b = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return BookVH(b)
        }

        override fun onBindViewHolder(holder: BookVH, position: Int) {
            val item = items[position]
            holder.b.tvBookTitle.text = item.title
            holder.b.tvBookPages.text = "${item.totalPages} pages • Tap to view chapters"
            holder.b.root.setOnClickListener { onClick(item) }
            holder.b.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
