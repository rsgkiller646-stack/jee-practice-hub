package com.jee.practicehub.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jee.practicehub.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object PdfUtils {

    suspend fun copyPdfToLibrary(context: Context, uri: Uri, displayName: String): Pair<File, Int> =
        withContext(Dispatchers.IO) {
            val libraryDir = File(context.filesDir, "pdf_library").apply { mkdirs() }
            val safeFile = File(libraryDir, "${UUID.randomUUID()}.pdf")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(safeFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to read selected PDF.")

            val pageCount = getPageCount(safeFile)
            Pair(safeFile, pageCount)
        }

    fun getPageCount(pdfFile: File): Int {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                return renderer.pageCount
            }
        }
    }

    suspend fun renderPageBitmap(pdfFile: File, pageIndex: Int, scaleFactor: Float = 2.0f): Bitmap =
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(safeIndex).use { page ->
                        val width = (page.width * scaleFactor).toInt()
                        val height = (page.height * scaleFactor).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }

    suspend fun detectChaptersViaOcr(
        pdfFile: File,
        bookId: Long,
        totalPages: Int,
        indexPagesToScan: Int = 3
    ): List<Chapter> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val detectedEntries = mutableListOf<Pair<String, Int>>()
        val pagesToCheck = minOf(totalPages, indexPagesToScan)

        val linePattern = Regex("""^(?:\d+[\.\)]\s*)?([A-Za-z][A-Za-z0-9\s,&\-:()]{3,60}?)[\s.\-_]+(\d{1,4})$""")

        for (pageIdx in 0 until pagesToCheck) {
            val bitmap = renderPageBitmap(pdfFile, pageIdx, scaleFactor = 2.0f)
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val raw = line.text.trim()
                    val match = linePattern.find(raw)
                    if (match != null) {
                        val title = match.groupValues[1].trim().trimEnd('.', '-', '_')
                        val pageNum = match.groupValues[2].toIntOrNull() ?: continue
                        if (pageNum in 1..totalPages && title.length >= 3) {
                            detectedEntries.add(title to pageNum)
                        }
                    }
                }
            }
            bitmap.recycle()
        }

        val sorted = detectedEntries
            .distinctBy { it.first.lowercase() }
            .sortedBy { it.second }

        if (sorted.isEmpty()) {
            return@withContext listOf(
                Chapter(
                    bookId = bookId,
                    name = "Full PDF Practice",
                    startPage = 1,
                    endPage = totalPages
                )
            )
        }

        val chapters = mutableListOf<Chapter>()
        for (i in sorted.indices) {
            val (name, start) = sorted[i]
            val end = if (i + 1 < sorted.size) {
                maxOf(start, sorted[i + 1].second - 1)
            } else {
                totalPages
            }
            chapters.add(
                Chapter(
                    bookId = bookId,
                    name = name,
                    startPage = start,
                    endPage = end.coerceAtMost(totalPages)
                )
            )
        }
        chapters
    }

    suspend fun exportChapterPdf(
        context: Context,
        sourcePdf: File,
        chapterName: String,
        startPage: Int,
        endPage: Int
    ): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exported_chapters").apply { mkdirs() }
        val cleanName = chapterName.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim().replace(" ", "_")
        val outFile = File(exportDir, "${cleanName}_p${startPage}-${endPage}.pdf")

        val pdfDocument = PdfDocument()
        ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val safeStart = (startPage - 1).coerceIn(0, renderer.pageCount - 1)
                val safeEnd = (endPage - 1).coerceIn(safeStart, renderer.pageCount - 1)

                for (pageIndex in safeStart..safeEnd) {
                    renderer.openPage(pageIndex).use { page ->
                        val width = page.width * 2
                        val height = page.height * 2
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val pageInfo = PdfDocument.PageInfo.Builder(width, height, pageIndex - safeStart + 1).create()
                        val newPage = pdfDocument.startPage(pageInfo)
                        newPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        pdfDocument.finishPage(newPage)
                        bitmap.recycle()
                    }
                }
            }
        }

        FileOutputStream(outFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        outFile
    }
}
