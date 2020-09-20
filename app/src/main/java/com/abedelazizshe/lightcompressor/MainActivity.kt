package com.abedelazizshe.lightcompressor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Created by AbedElaziz Shehadeh on 26 Jan, 2020
 * elaziz.shehadeh@gmail.com
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_SELECT_VIDEO = 0
    }

    private lateinit var path: String
    private val USE_VIDEO_PICKER = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setReadStoragePermission()

        fab.setOnClickListener {
            pickVideo()
        }

        cancel.setOnClickListener {
            VideoCompressor.cancel()
        }

        videoLayout.setOnClickListener { VideoPlayerActivity.start(this, path) }
    }

    //Pick a video file from device
    private fun pickVideo() {
        return if (USE_VIDEO_PICKER) {
            val intent = Intent()
            intent.apply {
                type = "video/*"
                action = Intent.ACTION_PICK
            }
            startActivityForResult(Intent.createChooser(intent, "Select video"), REQUEST_SELECT_VIDEO)
        } else {
            /**
             * Alternate picker method that produces content://document.provider.. uris
             */
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.apply {
                type = "*/*"
            }
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
            startActivityForResult(
                Intent.createChooser(intent, "Select video"),
                REQUEST_SELECT_VIDEO,
                Bundle.EMPTY
            )
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        mainContents.visibility = View.GONE
        timeTaken.text = ""
        newSize.text = ""

        if (resultCode == Activity.RESULT_OK)
            if (requestCode == REQUEST_SELECT_VIDEO) {
                if (data != null && data.data != null) {
                    val uri = data.data

                    uri?.let {
                        mainContents.visibility = View.VISIBLE
                        GlideApp.with(applicationContext).load(uri).into(videoImage)

                        GlobalScope.launch {
                            // run in background as it can take a long time if the video is big,
                            // this implementation is not the best way to do it,
                            // todo(abed): improve threading
                            path = if (USE_VIDEO_PICKER) {
                                val job = async { getMediaPath(applicationContext, uri) }
                                job.await()
                            } else {
                                uri.toString()
                            }

                            val desFile = if (USE_VIDEO_PICKER) saveVideoFile(path) else {
                                File.createTempFile("encoded", ".mp4", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
                            }
                            desFile?.deleteOnExit()
                            desFile?.let {
                                var time = 0L
                                VideoCompressor.start(
                                    path,
                                    desFile.path,
                                    object : CompressionListener {
                                        override fun onProgress(percent: Float) {
                                            //Update UI
                                            if (percent <= 100 && percent.toInt() % 5 == 0)
                                                runOnUiThread {
                                                    progress.text = "${percent.toLong()}%"
                                                    progressBar.progress = percent.toInt()
                                                }
                                        }

                                        override fun onStart() {
                                            time = System.currentTimeMillis()
                                            val originalLength = if (USE_VIDEO_PICKER) File(path).length() else {
                                                applicationContext.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0
                                            }
                                            progress.visibility = View.VISIBLE
                                            progressBar.visibility = View.VISIBLE
                                            originalSize.text =
                                                "Original size: ${getFileSize(originalLength)}"
                                            progress.text = ""
                                            progressBar.progress = 0
                                        }

                                        override fun onSuccess() {
                                            val newSizeValue = desFile.length()

                                            newSize.text =
                                                "Size after compression: ${getFileSize(newSizeValue)}"

                                            time = System.currentTimeMillis() - time
                                            timeTaken.text =
                                                "Duration: ${DateUtils.formatElapsedTime(time / 1000)}"

                                            path = desFile.path

                                            Looper.myLooper()?.let {
                                                Handler(it).postDelayed({
                                                    progress.visibility = View.GONE
                                                    progressBar.visibility = View.GONE
                                                }, 50)
                                            }
                                        }

                                        override fun onFailure(failureMessage: String) {
                                            progress.text = failureMessage
                                            Log.wtf("failureMessage", failureMessage)
                                        }

                                        override fun onCancelled() {
                                            Log.wtf("TAG", "compression has been cancelled")
                                            // make UI changes, cleanup, etc
                                        }
                                    },
                                    VideoQuality.MEDIUM,
                                    isMinBitRateEnabled = true,
                                    keepOriginalResolution = false,
                                    context = if (!USE_VIDEO_PICKER) applicationContext else null
                                )
                            }
                        }
                    }
                }
            }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setReadStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveVideoFile(filePath: String?): File? {
        filePath?.let {
            val videoFile = File(filePath)
            val videoFileName = "${System.currentTimeMillis()}_${videoFile.name}"
            val folderName = Environment.DIRECTORY_MOVIES
            if (Build.VERSION.SDK_INT >= 29) {

                val values = ContentValues().apply {

                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        videoFileName
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Images.Media.RELATIVE_PATH, folderName)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection =
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val fileUri = applicationContext.contentResolver.insert(collection, values)

                fileUri?.let {
                    application.contentResolver.openFileDescriptor(fileUri, "w").use { descriptor ->
                        descriptor?.let {
                            FileOutputStream(descriptor.fileDescriptor).use { out ->
                                FileInputStream(videoFile).use { inputStream ->
                                    val buf = ByteArray(4096)
                                    while (true) {
                                        val sz = inputStream.read(buf)
                                        if (sz <= 0) break
                                        out.write(buf, 0, sz)
                                    }
                                }
                            }
                        }
                    }

                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    applicationContext.contentResolver.update(fileUri, values, null, null)

                    return File(getMediaPath(applicationContext, fileUri))
                }
            } else {
                val downloadsPath =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val desFile = File(downloadsPath, videoFileName)

                if (desFile.exists())
                    desFile.delete()

                try {
                    desFile.createNewFile()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                return desFile
            }
        }
        return null
    }
}
