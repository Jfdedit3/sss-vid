package com.jfdedit3.sssvid

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jfdedit3.sssvid.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var isPrepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI).orEmpty()
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE).orEmpty()

        if (videoUriString.isBlank()) {
            Toast.makeText(this, "Aucune vidéo à lire", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val videoUri = Uri.parse(videoUriString)
        binding.videoTitleText.text = if (videoTitle.isBlank()) "Lecteur sss-vid" else videoTitle
        binding.videoStatusText.text = "Chargement..."

        binding.rewindButton.setOnClickListener {
            if (isPrepared) {
                val target = (binding.videoView.currentPosition - 10_000).coerceAtLeast(0)
                binding.videoView.seekTo(target)
            }
        }

        binding.forwardButton.setOnClickListener {
            if (isPrepared) {
                val target = binding.videoView.currentPosition + 10_000
                binding.videoView.seekTo(target)
            }
        }

        binding.playPauseButton.setOnClickListener {
            if (!isPrepared) return@setOnClickListener
            if (binding.videoView.isPlaying) {
                binding.videoView.pause()
                binding.playPauseButton.text = "Lire"
                binding.videoStatusText.text = "En pause"
            } else {
                binding.videoView.start()
                binding.playPauseButton.text = "Pause"
                binding.videoStatusText.text = "Lecture"
            }
        }

        binding.videoView.apply {
            setVideoURI(videoUri)
            setOnPreparedListener { mediaPlayer: MediaPlayer ->
                isPrepared = true
                resizeVideoView(mediaPlayer.videoWidth, mediaPlayer.videoHeight)
                mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                    resizeVideoView(width, height)
                }
                binding.videoStatusText.text = "Lecture"
                binding.playPauseButton.text = "Pause"
                start()
            }
            setOnCompletionListener {
                binding.videoStatusText.text = "Lecture terminée"
                binding.playPauseButton.text = "Lire"
            }
            setOnErrorListener { _, _, _ ->
                binding.videoStatusText.text = "Erreur de lecture"
                Toast.makeText(this@VideoPlayerActivity, "Impossible de lire cette vidéo", Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    private fun resizeVideoView(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return

        binding.videoContainer.post {
            val containerWidth = binding.videoContainer.width
            val containerHeight = binding.videoContainer.height
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
            val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()

            val finalWidth: Int
            val finalHeight: Int

            if (videoRatio > containerRatio) {
                finalWidth = containerWidth
                finalHeight = (containerWidth / videoRatio).toInt()
            } else {
                finalHeight = containerHeight
                finalWidth = (containerHeight * videoRatio).toInt()
            }

            val params = FrameLayout.LayoutParams(finalWidth, finalHeight)
            params.gravity = android.view.Gravity.CENTER
            binding.videoView.layoutParams = params
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            binding.playPauseButton.text = "Lire"
            binding.videoStatusText.text = "En pause"
        }
    }

    override fun onDestroy() {
        binding.videoView.stopPlayback()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
    }
}
