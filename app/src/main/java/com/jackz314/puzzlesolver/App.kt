package com.jackz314.puzzlesolver

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log

private const val TAG = "App"

class App: Application() {

    interface SCPermissionListener{
        fun onSCPermissionResult(result: Boolean)
    }

    override fun onTerminate() {
        super.onTerminate()
        mediaProjection?.stop()
        mediaProjection = null
        mediaProjectionManager = null
        screenshotPermission = null
        listener = null
    }

    companion object{
        var requestingSCPermission = false
        private var screenshotPermission: Intent? = null
        var mediaProjectionManager: MediaProjectionManager? = null
        var mediaProjection: MediaProjection? = null
        private var listener: SCPermissionListener? = null

        fun getScreenshotPermission(context: Context, listener: SCPermissionListener?) {
            if(listener != null) this.listener = listener
            try {
                if (hasScreenshotPermission()) {
                    setMediaProjection()
                    listener?.onSCPermissionResult(true)//success
                } else {
                    openScreenshotPermissionRequester(context)
                }
            } catch (ignored: RuntimeException) {
                openScreenshotPermissionRequester(context)
            }
        }

        private fun setMediaProjection() {
            if (mediaProjection != null) {
                mediaProjection!!.stop()
                mediaProjection = null
            }
            mediaProjection = mediaProjectionManager?.getMediaProjection(
                Activity.RESULT_OK,
                screenshotPermission!!.clone() as Intent
            )
        }

        private fun hasScreenshotPermission(): Boolean {
            return screenshotPermission != null
        }

        private fun openScreenshotPermissionRequester(context: Context) {
            val intent = Intent(context, ScreenCapActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            requestingSCPermission = true
            Log.d(TAG, "openScreenshotPermissionRequester: Starting Permission Activity")
            context.startActivity(intent)
        }

        fun setScreenshotPermission(permissionIntent: Intent) {
            screenshotPermission = permissionIntent
            setMediaProjection()
            listener?.onSCPermissionResult(true)
        }
    }
}