package com.jfdedit3.sssvid

import android.content.Intent
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
                val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, item.contentUri)
                    putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, item.displayName)
                }
                context.startActivity(intent)
            }
        }
    }
}
