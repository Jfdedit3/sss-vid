package com.jfdedit3.sssvid

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jfdedit3.sssvid.databinding.ActivityGalleryBinding
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadAndDisplayGallery()
    }

    override fun onResume() {
        super.onResume()
        loadAndDisplayGallery()
    }

    private fun loadAndDisplayGallery() {
        val items = loadGalleryItems()
        binding.galleryRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.galleryRecyclerView.adapter = GalleryAdapter(items)
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadGalleryItems(): List<GalleryItem> {
        val realFolderItems = loadFromRealDownloadsFolder()
        if (realFolderItems.isNotEmpty()) return realFolderItems

        val mediaStoreItems = loadFromMediaStoreFallback()
        if (mediaStoreItems.isNotEmpty()) return mediaStoreItems

        return emptyList()
    }

    private fun loadFromRealDownloadsFolder(): List<GalleryItem> {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "sss-vid"
        )

        if (!folder.exists() || !folder.isDirectory) return emptyList()

        return folder.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                GalleryItem(
                    displayName = it.name,
                    infoText = buildInfoText(it.length(), guessMimeTypeFromName(it.name)),
                    contentUri = Uri.fromFile(it).toString()
                )
            }
            ?: emptyList()
    }

    private fun loadFromMediaStoreFallback(): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/sss-vid%")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Fichier"
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeColumn).orEmpty()
                val uri = Uri.withAppendedPath(collection, id.toString())

                items.add(
                    GalleryItem(
                        displayName = name,
                        infoText = buildInfoText(size, mimeType),
                        contentUri = uri.toString()
                    )
                )
            }
        }

        return items
    }

    private fun buildInfoText(size: Long, mimeType: String): String {
        val sizeText = formatFileSize(size)
        return if (mimeType.isBlank()) sizeText else "$sizeText • $mimeType"
    }

    private fun formatFileSize(size: Long): String {
        val kb = 1024L
        val mb = kb * 1024L
        return when {
            size >= mb -> String.format("%.2f MB", size.toDouble() / mb.toDouble())
            size >= kb -> String.format("%.2f KB", size.toDouble() / kb.toDouble())
            else -> "$size B"
        }
    }

    private fun guessMimeTypeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            else -> ""
        }
    }
}
