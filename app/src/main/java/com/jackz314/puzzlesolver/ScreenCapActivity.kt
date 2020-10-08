package com.jackz314.puzzlesolver

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

private const val TAG = "ScreenCapActivity"

private const val REQUEST_MEDIA_PROJECTION = 1

class ScreenCapActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_cap)
        if(!App.requestingSCPermission){
            finish()
            return
        }
        App.requestingSCPermission = false
        App.mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.i(TAG, "Requesting confirmation")
        // This initiates a prompt dialog for the user to confirm screen projection.
        startActivityForResult(App.mediaProjectionManager!!.createScreenCaptureIntent(),REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (REQUEST_MEDIA_PROJECTION == requestCode) {
            if (Activity.RESULT_OK == resultCode) {
                App.setScreenshotPermission(data?.clone() as Intent)
            }else if (Activity.RESULT_CANCELED == resultCode) {
//                App.setScreenshotPermission(null)
                Toast.makeText(this, "Screenshot permission denied :(", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}