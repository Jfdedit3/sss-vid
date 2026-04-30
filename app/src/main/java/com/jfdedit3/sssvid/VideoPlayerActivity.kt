package com.jfdedit3.sssvid

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jfdedit3.sssvid.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding

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

        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoView)

        binding.videoView.apply {
            setMediaController(mediaController)
            setVideoURI(videoUri)
            setOnPreparedListener { mediaPlayer: MediaPlayer ->
                mediaPlayer.setOnVideoSizeChangedListener { _, _, _ ->
                    mediaController.setAnchorView(binding.videoView)
                }
                binding.videoStatusText.text = "Lecture"
                start()
            }
            setOnCompletionListener {
                binding.videoStatusText.text = "Lecture terminée"
            }
            setOnErrorListener { _, _, _ ->
                binding.videoStatusText.text = "Erreur de lecture"
                Toast.makeText(this@VideoPlayerActivity, "Impossible de lire cette vidéo", Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
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
