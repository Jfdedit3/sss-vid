package com.jfdedit3.sssvid

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jfdedit3.sssvid.databinding.ItemGalleryFileBinding

class GalleryAdapter(
    private val items: List<GalleryItem>
) : RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val binding = ItemGalleryFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GalleryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class GalleryViewHolder(
        private val binding: ItemGalleryFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GalleryItem) {
            binding.fileNameText.text = item.displayName
            binding.fileInfoText.text = item.infoText

            binding.root.setOnClickListener {
                val context = binding.root.context
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(item.contentUri), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
        }
    }
}
