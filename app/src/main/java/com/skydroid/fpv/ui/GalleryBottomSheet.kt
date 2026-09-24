package com.skydroid.fpv.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.skydroid.fpv.R
import com.skydroid.fpv.databinding.BottomSheetGalleryBinding

/**
 * Bottom Sheet displaying recorded flight videos and snapshots saved in Scoped Storage.
 */
class GalleryBottomSheet : BottomSheetDialogFragment() {

    data class MediaItem(
        val uri: Uri,
        val isVideo: Boolean,
        val durationFormatted: String
    )

    private var _binding: BottomSheetGalleryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadMediaFiles()
    }

    private fun loadMediaFiles() {
        val mediaList = mutableListOf<MediaItem>()
        val contentResolver = requireContext().contentResolver

        // Load Videos from MediaStore
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            videoUri,
            videoProjection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val durationMs = cursor.getLong(durationColumn)
                val uri = ContentUris.withAppendedId(videoUri, id)

                val seconds = (durationMs / 1000) % 60
                val minutes = (durationMs / (1000 * 60)) % 60
                val durationFormatted = String.format("%02d:%02d", minutes, seconds)

                mediaList.add(MediaItem(uri, true, durationFormatted))
            }
        }

        // Load Snapshots from MediaStore
        val imageProjection = arrayOf(MediaStore.Images.Media._ID)
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            imageUri,
            imageProjection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(imageUri, id)
                mediaList.add(MediaItem(uri, false, "PHOTO"))
            }
        }

        binding.tvMediaCount.text = "${mediaList.size} files"

        if (mediaList.isEmpty()) {
            binding.tvEmptyGallery.visibility = View.VISIBLE
            binding.recyclerGallery.visibility = View.GONE
        } else {
            binding.tvEmptyGallery.visibility = View.GONE
            binding.recyclerGallery.visibility = View.VISIBLE
            binding.recyclerGallery.adapter = GalleryAdapter(mediaList) { item ->
                openMedia(item)
            }
        }
    }

    private fun openMedia(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.isVideo) "video/mp4" else "image/jpeg")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(Intent.createChooser(intent, "Open Flight Recording"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class GalleryAdapter(
        private val items: List<MediaItem>,
        private val onItemClick: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.img_thumbnail)
            val mediaType: ImageView = view.findViewById(R.id.img_media_type)
            val duration: TextView = view.findViewById(R.id.tv_duration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_media, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.duration.text = item.durationFormatted
            holder.mediaType.setImageResource(
                if (item.isVideo) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_camera
            )

            // Load thumbnail using ContentResolver
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val size = android.util.Size(200, 200)
                    val bitmap = if (item.isVideo) {
                        holder.itemView.context.contentResolver.loadThumbnail(item.uri, size, null)
                    } else {
                        holder.itemView.context.contentResolver.loadThumbnail(item.uri, size, null)
                    }
                    holder.thumbnail.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {}

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
