package com.jackz314.puzzlesolver

import Catalano.Imaging.Concurrent.Filters.Dilatation
import Catalano.Imaging.Concurrent.Filters.Invert
import Catalano.Imaging.Concurrent.Filters.OtsuThreshold
import Catalano.Imaging.Concurrent.Filters.RosinThreshold
import Catalano.Imaging.FastBitmap
import Catalano.Imaging.IApplyInPlace
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.projection.MediaProjection
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.os.HandlerCompat
import androidx.core.text.isDigitsOnly
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.jackz314.puzzlesolver.MovableLayout.OnInterceptTouchListener
import com.jackz314.puzzlesolver.TorusPuzzleSolver.Companion.Dir
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "SolveService"
private const val ONGOING_NOTIFICATION_ID = 10086
private const val ONGOING_CHANNEL_ID = "pzslver_notif_chn"
private const val VIRTUAL_DISPLAY_FLAGS =
    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

class SolveService : Service() {

    companion object {
        @JvmStatic
        var running = false
    }

    private var randomMoveCnt = 0

    //screen capture stuff
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mRotation = 0
    private var mOrientationChangeCallback: OrientationChangeCallback? = null
    private var mStoreDir: String? = null
    private var mStoreCreateDirFailed = false
    private var mImgCnt: Int = 0
    private var mImageAvailableListener: ImageAvailableListener? = null

    private var mBitmap: Bitmap? = null
    private var mProcessedBitmap: Bitmap? = null

    private var stopService = false

    //overlay stuff
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: MovableLayout
    private var offsetX = 0F
    private var offsetY = 0F
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var solveBtn: ImageView
    private lateinit var statusText: TextView
    private lateinit var moveStateText: TextView
    private var solving = false
    private var resumeCapture = false
    private var mToast: Toast? = null

    private var mDuration = 10
    private var mDelay = 0
    private var mExpIdle = false

    //solve stuff
    private var minX = 0
    private var minY = 0
    private var xStart = -1F // location of starting element of the board horizontally
    private var yStart = -1F // location of starting element of the board vertically

    private var moveHistoryList = LinkedList<String>()

    private var mMoveMaker: MoveMaker? = null
    private lateinit var gestureService: GestureService

    // Binder given to clients
    private val binder = SolveServiceBinder()

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class SolveServiceBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): SolveService = this@SolveService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Capture Notifications"
            val descriptionText = "Channel used for capturing the screen"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(ONGOING_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mDuration = intent?.getIntExtra(MainActivity.durationPref, mDuration) ?: mDuration
        mDelay = intent?.getIntExtra(MainActivity.delayPref, mDelay) ?: mDelay
        mExpIdle = intent?.getBooleanExtra(MainActivity.expIdlePref, false) ?: false
        setup()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setup() {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle("Puzzle Solver Running")
            .setContentText("Your screen is being captured for puzzle solving")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setTicker("Puzzle Solver Running")
            .build()


        startForeground(ONGOING_NOTIFICATION_ID, notification)
        running = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView =
            LayoutInflater.from(this).inflate(R.layout.overlay_layout, null) as MovableLayout
        //        overlayView.setImageResource(R.drawable.ic_play_gray)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        windowManager.addView(overlayView, params)
        overlayView.setInterceptTouchListener(object : OnInterceptTouchListener {
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        //                    val currParam: WindowManager.LayoutParams = v.layoutParams as WindowManager.LayoutParams
                        offsetX = e.rawX - params.x
                        offsetY = e.rawY - params.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (e.rawX - offsetX).toInt()
                        params.y = (e.rawY - offsetY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                        //                        Log.d(TAG, "onInterceptTouchEvent: MOVING")
                        //                        return true
                    }
                }
                return false
            }
        })
        overlayView.setOnTouchListener { v, e ->//drag and drop
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    //                    val currParam: WindowManager.LayoutParams = v.layoutParams as WindowManager.LayoutParams
                    offsetX = e.rawX - params.x
                    offsetY = e.rawY - params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - offsetX).toInt()
                    params.y = (e.rawY - offsetY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    //                    Log.d(TAG, "onTouchEvent: MOVING")
                    return@setOnTouchListener true
                }
            }
            false
            //            v?.onTouchEvent(e) ?: true
        }
        solveBtn = overlayView.findViewById(R.id.solveBtn)
        solveBtn.setOnClickListener {
            if (solving) {
                exit()
            } else {
                mToast = Toast.makeText(this, "Solving, getting permission...", Toast.LENGTH_SHORT)
                    .apply {show()}
                tryStart()
            }
        }
        statusText = overlayView.findViewById(R.id.statusText)
        moveStateText = overlayView.findViewById(R.id.moveStateText)
        val thread = HandlerThread("CaptureThread").also {it.start()}
        mHandler = HandlerCompat.createAsync(thread.looper)
    }

    private fun setStatusText(str: String) {
        Handler(Looper.getMainLooper()).post {statusText.text = str}
    }

    private fun tryStart() {
        setStatusText("Getting Permissions")
        if (GestureService.getInstance() == null) {
            Log.d(TAG, "tryStart: gesture service not available yet, waiting...")
            GestureService.setInstanceListener(object : GestureService.ServiceInstanceAvailableListener {
                override fun onAvailable() {
                    Log.d(TAG, "onAvailable: gesture service now available")
                    gestureService = GestureService.getInstance()!!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {//use accessibility takeScreenshot
                        takeAccessibilityScreenshot()
                    } else {
                        getScreenCapPermissionAndStart()
                    }
                }
            })
        } else {
            gestureService = GestureService.getInstance()!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {//use accessibility takeScreenshot
                takeAccessibilityScreenshot()
            } else {
                getScreenCapPermissionAndStart()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeAccessibilityScreenshot() {
        if (!running) return
        prepareStart()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!running) return@postDelayed
            Log.d(TAG, "takeAccessibilityScreenshot: start!")
            gestureService.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (!running) return
                        Log.d(TAG, "onSuccess: taken screenshot with accessibility service")
                        mBitmap =
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                                ?.run {copy(Bitmap.Config.ARGB_8888, false)}
                        if (mBitmap == null) {
                            Log.e(
                                TAG,
                                "onFailure: Take screenshot with accessibility service failed: bitmap is null"
                            )
                            getScreenCapPermissionAndStart() // fallback
                            return
                        }
                        //                            Handler(Looper.getMainLooper()).post{overlayView.visibility = View.VISIBLE}
                        overlayView.visibility = View.VISIBLE
                        // write bitmap to a file
                        saveToFile(mBitmap!!, "puzzle_solver_screen")
                        //process
                        process(mBitmap!!)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!running) return
                        Log.e(
                            TAG,
                            "onFailure: Take screenshot with accessibility service failed: $errorCode"
                        )
                        getScreenCapPermissionAndStart() // fallback
                    }

                })
        }, 200)//wait for overlay UI to hide
    }

    private fun getScreenCapPermissionAndStart() {
        if (!running) return
        if (App.mediaProjection == null) {
            Log.d(TAG, "tryStart: getting permissions")
            App.getScreenshotPermission(this, object : App.SCPermissionListener {
                override fun onSCPermissionResult(result: Boolean) {
                    //wait for a while before starting
                    if (!running) return
                    if (result) {
                        prepareStart()
                        startScreenCap()
                    }
                }
            })
        } else {
            prepareStart()
            startScreenCap()
        }
    }

    private fun prepareStart() {
        setStatusText("Starting")
        solveBtn.setImageResource(R.drawable.ic_stop_gray)
        solving = true
        overlayView.visibility = View.GONE // hide self momentarily
        mToast?.cancel()
        mToast = null
//        Toast.makeText(this, "GOT PERMISSION!", Toast.LENGTH_SHORT).show()
        mBitmap?.recycle()//dispose old one if one exists
        mBitmap = null
        mProcessedBitmap?.recycle()
        mProcessedBitmap = null
    }

    private fun startScreenCap() {
        if (!running) return
        Handler(mainLooper).postDelayed({
            if (!running) return@postDelayed
            if (resumeCapture) {
                resumeCapture = false
                setStatusText("Scanning screen")
                //                createVirtualDisplay()
                Log.d(TAG, "start: resuming")
                restartImageReader()
            } else {
                startCapture()
            }//resuming
        }, 250)//wait for other UI to hide
    }

    private fun startCapture() {
        if (!running) return
        Log.d(TAG, "startCapture: starting capture")
        setStatusText("Scanning screen")
        createVirtualDisplay()
        startImageReaderListening()

        // register orientation change callback
        mOrientationChangeCallback = OrientationChangeCallback(this)
        if (mOrientationChangeCallback!!.canDetectOrientation()) {
            mOrientationChangeCallback!!.enable()
        }

        // register media projection stop callback
        App.mediaProjection?.registerCallback(MediaProjectionStopCallback(), mHandler)
    }

    fun exit() {
        running = false
        Toast.makeText(this, "Stopped solving", Toast.LENGTH_SHORT).show()
//            solveBtn.setImageResource(R.drawable.ic_play_gray)
        mMoveMaker?.cancelMoves()
        stopService = true
        mBitmap?.recycle()
        mProcessedBitmap?.recycle()
        if (App.mediaProjection != null) mHandler!!.post {
            App.mediaProjection?.stop()
        } else {
            stopSelf()
        }
    }

    //stop solving and capturing, can resume later
    private fun stop() {
        setStatusText("Idle")
        moveStateText.text = ""
        moveHistoryList.clear()
        mHandler!!.post {
            App.mediaProjection?.stop()
        }
        mBitmap?.recycle()
        mBitmap = null
        mProcessedBitmap?.recycle()
        mProcessedBitmap = null
        overlayView.visibility = View.VISIBLE
        solveBtn.setImageResource(R.drawable.ic_play_gray)
        solving = false
    }

    private fun saveToFile(bitmap: Bitmap, name: String) {
        GlobalScope.launch(Dispatchers.IO) {
            //create or confirm saving directory
            if (!mStoreCreateDirFailed && mStoreDir == null) {
                val externalFilesDir: File? = getExternalFilesDir(null)
                if (externalFilesDir != null) {
                    mStoreDir = externalFilesDir.absolutePath.toString() + "/screenshots/"
                    val storeDirectory = File(mStoreDir!!)
                    if (!storeDirectory.exists()) {
                        val success: Boolean = storeDirectory.mkdirs()
                        if (!success) {
                            Log.e(TAG, "failed to create file storage directory.")
                            return@launch
                        }
                    }
                    Log.d(TAG, "startCapture: GOT DIRECTORY AT: $mStoreDir")
                } else mStoreCreateDirFailed = true
            }
            if (mStoreCreateDirFailed) return@launch
            try {
//            val filename = "${name}_${++mImgCnt}.png"
                val filename = "${name}.png"
                val fos = FileOutputStream("${mStoreDir.toString()}/$filename")
                bitmap.compress(CompressFormat.PNG, 100, fos)
                fos.close()
                Log.d(TAG, "captured image: $filename")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addToMoveState(str: String) {
        if (moveHistoryList.size >= 5) moveHistoryList.removeFirst()
        moveHistoryList.add(str)
        val finalHistory = moveHistoryList.joinToString("\n")
        Handler(Looper.getMainLooper()).post {moveStateText.text = finalHistory}
    }

    private fun appendToMoveState(str: String) {
        if (moveHistoryList.isEmpty()) return
        moveHistoryList[moveHistoryList.size - 1] += str
        val finalHistory = moveHistoryList.joinToString("\n")
        Handler(Looper.getMainLooper()).post {moveStateText.text = finalHistory}
    }

    private fun makeMoves(moves: List<P>, boardConfig: MoveMaker.BoardConfig) {
        if (!running) return
        setStatusText("Making Moves")
        Log.d(TAG, "makeMoves: Making moves: board config: $boardConfig")
        mMoveMaker = MoveMaker(
            moves.toMutableList(),
            boardConfig,
            object : MoveMaker.MovingStateListener {

                override fun movePrepared(move: P, cnt: Int) {
                    addToMoveState("Move ${move.first.name + move.second + (if (cnt == 1) "" else " (${cnt}x)")}...")
                }

                override fun moveComplete(move: P, cnt: Int) {
//                addToMoveState("Made move ${move.first.name + move.second + (if(cnt == 1) "" else " ($cnt times)")}")
                    appendToMoveState("complete")
                }

                override fun moveCancelled(move: P, cnt: Int) {
//                addToMoveState("Cancelled ${move.first.name + move.second + (if(cnt == 1) "" else " ($cnt times)")}")
                    appendToMoveState("cancelled")
                }

                override fun allMovesComplete() {
                    addToMoveState("All Done!")
                    Handler(Looper.getMainLooper()).post {
                        moveStateText.text = ""
                        moveHistoryList.clear()
                        toast("Solved!")
                    }
                    if (!running || !mExpIdle) {
                        stop()
                        return
                    }
                    setStatusText("Opening Next Puzzle")
                    GlobalScope.launch {
                        delay(3000)
                        if (!running) return@launch
                        if (clickBtn("Great! Claim", 730, 2430)) {
                            delay(400)
                            if (!running) return@launch
                            clickBtn("Play Torus Puzzle", 730, 1950)
                            delay(400)
                            if (!running) return@launch
                            clickBtn("Hard - ", 730, 1667)
                        }
                        delay(1000)
                        if (!running) return@launch
                        if (gestureService.rootInActiveWindow.findAccessibilityNodeInfosByText("I Give Up!")
                                .none {it.className.contains("Button")}) {//not in the right state, try again
                            Log.d(TAG, "allMovesComplete: not in the right state, clicking again")
                            if (gestureService.rootInActiveWindow.findAccessibilityNodeInfosByText("Play Torus Puzzle")
                                    .any {it.className.contains("Button")}) {
                                clickBtn("Play Torus Puzzle", 730, 1950)
                                delay(1000)
                                clickBtn("Hard - ", 730, 1667)
                                delay(1000)
                                continueScreenCapture()
                            } else {
                                Log.e(TAG, "allMovesComplete: In weird state, no play btn available")
                            }
                        } else {
                            continueScreenCapture()
                        }
                    }
                    /*Handler(Looper.getMainLooper()).postDelayed({
                        if (!running) return@postDelayed
                        clickBtn("Great! Claim", 730, 2430)
                    }, 4000)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!running) return@postDelayed
                        clickBtn("Play Torus Puzzle", 730, 1950)
                    }, 4100)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!running) return@postDelayed
                        clickBtn("Hard - ", 730, 1667)
                    }, 4200)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!running) return@postDelayed
                        if (gestureService.rootInActiveWindow.findAccessibilityNodeInfosByText("I Give Up!")
                                .none {it.className.contains("Button")}) {//not in the right state, try again
                            Log.d(TAG, "allMovesComplete: not in the right state, clicking again")
                            if (gestureService.rootInActiveWindow.findAccessibilityNodeInfosByText("Play Torus Puzzle")
                                    .any {it.className.contains("Button")}) {
                                clickBtn("Play Torus Puzzle", 730, 1950)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    clickBtn("Hard - ", 730, 1667)
                                }, 1000)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    continueScreenCapture()
                                }, 2000)
                            } else {
                                Log.e(TAG, "allMovesComplete: In weird state, no play btn available")
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {//use accessibility takeScreenshot
                                takeAccessibilityScreenshot()
                            } else {
                                resumeCapture = true
                                prepareStart()
                            }
                        }
                    }, 6000)*/
                }

            }, mDuration, mDelay
        )
        mMoveMaker!!.makeMoves()
    }

    private fun clickBtn(name: String, defaultX: Int, defaultY: Int, skipDefault: Boolean = true): Boolean {
        val btnList =
            gestureService.rootInActiveWindow.findAccessibilityNodeInfosByText(name)
        if (btnList.isNotEmpty() && btnList[0].isVisibleToUser) {
            Log.d(TAG, "clickBtn: found btn '$name', clicking: $btnList")
            btnList[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        } else {
            if (!skipDefault) {
                Log.d(TAG, "clickBtn: btn '$name' not found, using default coords")
                gestureService.click(defaultX, defaultY)
            }
            return false
        }
    }

    //keep doing screen capture when it's initialized already
    private fun continueScreenCapture() {
        if (!running) return
        Handler(Looper.getMainLooper()).post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {//use accessibility takeScreenshot
                takeAccessibilityScreenshot()
            } else {
                resumeCapture = true
                prepareStart()
                startScreenCap()
            }
        }
    }

    private fun solve(list: List<List<Int>>): List<P> {
        setStatusText("Solving Puzzle")
        return TorusPuzzleSolver(list).solve()
            .also {solution ->
                Log.d(
                    TAG,
                    "solve: GOT SOLUTION (${solution.size} moves): ${solution.map {"'" + it.first.name + it.second + "'"}}"
                )
            }
    }

    private fun textElementsToInts(list: List<List<Text.Element>>) =
        list.map {row -> row.map {it.text.toInt()}}

    private fun solveWithText(list: List<List<Text.Element>>) {
        Log.d(TAG, "solve with text: \n${list.map {it.map {it.text}}.joinToString("\n")}")
        val solution = solve(textElementsToInts(list))

        //compute average block size
        val firstRowPoints = list[0].map {it.cornerPoints!![0]}
        //        firstRowPoints.drop(1).forEachIndexed { i, point -> totalDistance += point.x - firstRowPoints[i].x }
        val avgBlockSideLen =
            (firstRowPoints.last().x - firstRowPoints.first().x).toFloat() / (list.size - 1)
        makeMoves(solution, MoveMaker.BoardConfig(list.size, avgBlockSideLen, xStart, yStart))
    }

    private fun applyFiltersInPlace(bitmap: Bitmap, vararg filters: IApplyInPlace) {
        val fb = FastBitmap(bitmap)
        fb.toGrayscale()
        filters.forEach {it.applyInPlace(fb)}
        fb.toBitmap()//save all filters back to bitmap
    }

    private fun verifyPuzzleNum(text: String?) =
        if (text != null && text.isNotBlank() && text.isDigitsOnly() && text.toInt() in 1..100) text.toInt() else null

    private suspend fun processOCR(bitmap: Bitmap, boardSize: Int) =
        suspendCancellableCoroutine<Pair<MutableList<Text.Element>, String>> {
            val img = InputImage.fromBitmap(bitmap, 0)//rotate 0 degrees
            val recognizer = TextRecognition.getClient()
            recognizer.process(img)
                .addOnSuccessListener {result ->
                    // Task completed successfully
                    val resultText = result.text//full text
//                    Log.d(TAG, "process: OCR Done:\n$resultText")
                    val (puzzleNums, puzzleType) = parsePuzzleFromText(result)
                    if (puzzleNums.size < 8) {
                        it.resumeWithException(IllegalStateException(
                            "[Parse Error] OCR Missed number or something is wrong, size of puzzle is too small: ${puzzleNums.size}"))
                    } else if (boardSize * boardSize != puzzleNums.size) {
                        val intPuzzleNums = puzzleNums.map {it.text.toIntOrNull()}
                        it.resumeWithException(InvalidPropertiesFormatException(
                            "[Parse Error] OCR Missed number or something is wrong, size of puzzle is wrong: ${puzzleNums.size}, " +
                                (if (puzzleNums.size > 0) "missing ${(1..36).filter {!intPuzzleNums.contains(it)}}"
                                else "extra ${puzzleNums.groupingBy {it}.eachCount().filter {it.value > 1}}")))
                    } else {//success
                        it.resume(Pair(puzzleNums, puzzleType))
                    }
                }.addOnFailureListener {e ->
                    // Task failed with an exception
                    it.resumeWithException(e)
                }
        }

    private fun process(bitmap: Bitmap) {
        Log.e(TAG, "process: PROCESSING")
//        setStatusText("Processing Image")

        //recognize text
        setStatusText("Finding Puzzle")
        if (mExpIdle) {
            try {
                val boardNode = gestureService
                    .rootInActiveWindow.findAccessibilityNodeInfosByText("Best Time")[0]
                    .parent.parent.parent.getChild(2)
                Log.d(TAG, "process: board node: $boardNode")
                val boardSize = sqrt(boardNode.childCount.toDouble()).toInt()
                if (boardNode.childCount < 8 || boardSize * boardSize != boardNode.childCount) throw IllegalArgumentException(
                    "Board size is not right: ${boardNode.childCount}"
                )
                val boardBounds = Rect().also {boardNode.getBoundsInScreen(it)}
                val blockSideLen = (boardBounds.right - boardBounds.left).toFloat() / boardSize
                xStart = boardBounds.left + blockSideLen / 2
                yStart = boardBounds.top + blockSideLen / 2
                val boardBm =
                    Bitmap.createBitmap(bitmap, boardBounds.left, boardBounds.top, boardBounds.width(), boardBounds.height())
                saveToFile(boardBm, "BOARD")
                //adaptive thresholding and stuff
                mBitmap?.recycle()
                mBitmap = boardBm
                mProcessedBitmap = boardBm.copy(boardBm.config, true)
//                applyFiltersInPlace(mProcessedBitmap!!, Invert())
                //rosin seems to work the best because it processes images with simple backgrounds better. And it's fast
                applyFiltersInPlace(mProcessedBitmap!!, RosinThreshold(), Dilatation(1), Invert())
                //make a copy so the bitmap doesn't get recycled before OCR'd
//        processedBM = processedBM.copy(processedBM.config, true)
                saveToFile(mProcessedBitmap!!, "puzzle_solver_modified")

                GlobalScope.launch {
//                    val filterList =
//                        listOf(RosinThreshold(), OtsuThreshold(), HysteresisThreshold(), DifferenceEdgeDetector())
                    try {
                        val (puzzleNums, puzzleType) = processOCR(mProcessedBitmap!!, boardSize)
                        mProcessedBitmap?.recycle()
                        val str = puzzleNums.withIndex()
                            .joinToString(" ") {(i, text) -> if (i % boardSize != boardSize - 1) text.text else text.text + "\n"}
                        Log.d(TAG, "process: puzzle type: $puzzleType")
                        Log.d(TAG, "process: puzzle nums: \n$str")
                        toast("Puzzle detected, solving...")
                        val solution = solve(puzzleNums.map {it.text.toInt()}.chunked(boardSize))
                        makeMoves(solution, MoveMaker.BoardConfig(boardSize, blockSideLen, xStart, yStart))
                    } catch (e: Exception) {
                        mProcessedBitmap?.recycle()
                        Log.e(TAG, "process: OCR failed: ${e.message}")
                        if (e is InvalidPropertiesFormatException) {//try again
                            Log.d(TAG, "process: OCR result is close, trying again...")
                            mProcessedBitmap = mBitmap!!.copy(mBitmap!!.config, true)
                            applyFiltersInPlace(mProcessedBitmap!!, RosinThreshold(), Dilatation(2), Invert())
                            saveToFile(mProcessedBitmap!!, "puzzle_solver_modified_again")
                            try {
                                val (puzzleNums, puzzleType) = processOCR(mProcessedBitmap!!, boardSize)
                                mProcessedBitmap?.recycle()
                                val str = puzzleNums.withIndex()
                                    .joinToString(" ") {(i, text) -> if (i % boardSize != boardSize - 1) text.text else text.text + "\n"}
                                Log.d(TAG, "process: puzzle type: $puzzleType")
                                Log.d(TAG, "process: puzzle nums: \n$str")
                                toast("Puzzle detected, solving...")
                                val solution =
                                    solve(puzzleNums.map {it.text.toInt()}.chunked(boardSize))
                                makeMoves(solution, MoveMaker.BoardConfig(boardSize, blockSideLen, xStart, yStart))
                            } catch (e: Exception) {
                                Log.e(TAG, "process: OCR failed again: ${e.message}")
                                //last try, make random moves on the board
                                if (++randomMoveCnt < 5 && e is InvalidPropertiesFormatException) {
                                    val result =
                                        makeRandomMoves(MoveMaker.BoardConfig(boardSize, blockSideLen, xStart, yStart))
                                    if (result) {
                                        continueScreenCapture()//start from screenshot again
                                    } else {
                                        Log.e(TAG, "process: random moves failed")
                                        stopAndShowError(e)
                                    }
                                } else stopAndShowError(e)
                            }
                        } else stopAndShowError(e)
                    }
                }

                /*class OCRTaskCallable(val task: Task<Text>, val bounds: Rect) : Callable<Pair<Text?,Rect>> {
                    override fun call(): Pair<Text?,Rect> {
                        try {
                            return Pair(Tasks.await(task), bounds)
                        } catch (e: ExecutionException) {
                            Log.e(TAG, "call: ocr task failed", e)
                        } catch (e: InterruptedException) {
                            Log.d(TAG, "call: ocr task interrupted")
                        }
                        return Pair(null, bounds)
                    }
                }
                val executor = Executors.newSingleThreadExecutor()
                val taskList = mutableListOf<Future<Pair<Text?, Rect>>>()
                for(i in 0 until boardNode.childCount){
                    val blockBounds = Rect().also{boardNode.getChild(i).getBoundsInScreen(it)}
//                    Log.d(TAG, "process: block bounds: $blockBounds")
                    val blockBm = Bitmap.createBitmap(
                        mProcessedBitmap!!,
                        blockBounds.left,
                        blockBounds.top,
                        blockBounds.width(),
                        blockBounds.height()
                    )
                    saveToFile(blockBm, "B$i")
                    val img = InputImage.fromBitmap(blockBm, 0)//rotate 0 degrees
                    val recognizer = TextRecognition.getClient()
                    taskList.add(executor.submit(OCRTaskCallable(recognizer.process(img), blockBounds)))
                }
                executor.shutdown()
                Thread{
                    Thread.yield()
                    executor.awaitTermination(30, TimeUnit.SECONDS)
                    if(mBitmap == null) return@Thread
                    if(!executor.isTerminated){
                        Log.d(TAG, "process: waited too long, terminate")
                        Handler(Looper.getMainLooper()).post{
                            Toast.makeText(applicationContext, "OCR Timed out, try again later :(", Toast.LENGTH_SHORT).show()
                            stop()
                        }
                        return@Thread
                    }
                    val puzzleNums = Array(boardSize*boardSize){0}
                    val failedBounds = mutableListOf<Pair<Int,Rect>>()
                    taskList.forEachIndexed {i, task->
                        val (text, bounds) = task.get()
                        if(text == null) failedBounds.add(Pair(i,bounds))
                        val num = verifyPuzzleNum(text?.text)
                        if(num == null) failedBounds.add(Pair(i,bounds))
                        else puzzleNums[i] = num
                    }
                    if(failedBounds.isNotEmpty()){
                        Log.e(TAG, "process: failed OCR tasks: (${failedBounds.size}) $failedBounds")
                        Handler(Looper.getMainLooper()).post{Toast.makeText(this,"OCR partially failed, trying again...",Toast.LENGTH_SHORT).show()}
                        failedBounds.forEach {(i, bounds)->
                            if(mBitmap == null) return@Thread
                            val blockBm = Bitmap.createBitmap(
                                mBitmap!!,
                                bounds.left,
                                bounds.top,
                                bounds.width(),
                                bounds.height()
                            )
                            val img = InputImage.fromBitmap(blockBm, 0)//rotate 0 degrees
                            val recognizer = TextRecognition.getClient()
                            try {
                                val text = Tasks.await(recognizer.process(img)).text
                                val num = verifyPuzzleNum(text)
                                if(num == null) {
                                    Log.e(TAG, "process: ocr number not right: $text")
                                    Handler(Looper.getMainLooper()).post{
                                        Toast.makeText(applicationContext, "OCR Failed, try again later :(", Toast.LENGTH_SHORT).show()
                                        stop()
                                    }
                                }
                                else puzzleNums[i] = num
                            } catch (e: Exception) {
                                Log.e(TAG, "process: ocr task exception ", e)
                                Handler(Looper.getMainLooper()).post{
                                    Toast.makeText(applicationContext, "OCR Failed, try again later :(", Toast.LENGTH_SHORT).show()
                                    stop()
                                }
                                return@Thread
                            }
                        }
                    }else{
                        Log.d(TAG, "process: OCR got puzzle numbers!\n${puzzleNums.withIndex()
                            .joinToString(" ") { (i, num) -> if (i % boardSize != boardSize - 1) num.toString() else num.toString() + "\n" }}")
                        Handler(Looper.getMainLooper()).post{Toast.makeText(this,"Puzzle detected, solving...",Toast.LENGTH_SHORT).show()}
                        val solution = solve(puzzleNums.toList().chunked(boardSize))
                        makeMoves(solution, MoveMaker.BoardConfig(boardSize, blockSideLen, xStart, yStart))
                    }
                }.start()*/

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this,
                    "Something went wrong, fallback to regular OCR",
                    Toast.LENGTH_SHORT
                ).show()
                parsePuzzleOCR(bitmap)
            }

        } else {
            parsePuzzleOCR(bitmap)
        }
//        Log.d(TAG, "process: after OCR SSSS")
        //after done processing, resume listener to check for finish status
//        overlayView.visibility = View.INVISIBLE // hide self momentarily
//        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
    }

    private fun stopAndShowError(e: Exception) {
        if (e.message?.contains("[Parse Error]") == true) {
            toast("Failed to detect the puzzle, try again later :(")
        } else {
            toast("OCR Failed, try again later :(")
        }
        Handler(Looper.getMainLooper()).post {stop()}
    }

    private fun toast(str: String) {
        Handler(mainLooper).post {Toast.makeText(applicationContext, str, Toast.LENGTH_SHORT).show()}
    }

    private fun parsePuzzleOCR(bitmap: Bitmap) {
        //adaptive thresholding and stuff
        mProcessedBitmap = bitmap.copy(bitmap.config, true)
        applyFiltersInPlace(mProcessedBitmap!!, RosinThreshold(), Dilatation(2), Invert())
        //make a copy so the bitmap doesn't get recycled before OCR'd
//        processedBM = processedBM.copy(processedBM.config, true)
        saveToFile(mProcessedBitmap!!, "puzzle_solver_modified")
        val img = InputImage.fromBitmap(mProcessedBitmap!!, 0)//rotate 0 degrees
        val recognizer = TextRecognition.getClient()
        recognizer.process(img)
            .addOnSuccessListener {result ->
                mProcessedBitmap?.recycle()
                // Task completed successfully
                val resultText = result.text//full text
//                Log.d(TAG, "process: OCR Done:\n$resultText")
                val (puzzleNums, puzzleType) = parsePuzzleFromText(result)
                if (puzzleNums.size < 8) { // minimum 8 numbers not matched, fail
                    Log.e(
                        TAG,
                        "process: OCR Missed number or something is wrong, size of puzzle is wrong: ${puzzleNums.size}"
                    )
                    Toast.makeText(
                        this,
                        "Failed to detect the puzzle, try again later :(",
                        Toast.LENGTH_SHORT
                    ).show()
                    Handler(Looper.getMainLooper()).post {stop()}
                    return@addOnSuccessListener
                }
                //                Log.d(TAG, "process: first point: ${puzzleNums[0].cornerPoints.contentToString()} ${puzzleNums[0].boundingBox}")
                xStart =
                    (puzzleNums.minByOrNull {it.cornerPoints?.get(0)?.x ?: 0}//get min x element
                        ?.also {
                            minX = it.cornerPoints?.get(0)?.x ?: 0
                        }//store minX for potential use later
                        ?.run {cornerPoints?.let {(it[0].x + it[1].x) / 2}}
                        ?: 0).toFloat()  //get middle point of this element as xStart
                yStart =
                    (puzzleNums.minByOrNull {it.cornerPoints?.get(0)?.y ?: 0}//get min y element
                        ?.also {
                            minY = it.cornerPoints?.get(0)?.y ?: 0
                        }//store minY for potential use later
                        ?.run {cornerPoints?.let {(it[0].y + it[3].y) / 2}}
                        ?: 0).toFloat() //get middle point of this element as yStart
                val puzzleSize = sqrt(puzzleNums.size.toDouble())
                if (puzzleSize.toInt()
                        .compareTo(puzzleSize) != 0
                ) {//result might be close, try again by cropping
                    Log.w(
                        TAG,
                        "process: Detected possible puzzle, but something went wrong, further processing: ${puzzleNums.size}"
                    )
                    setStatusText("Reprocessing Image")
                    val str = puzzleNums.withIndex()
                        .joinToString(" ") {(i, text) -> if (i % 6 != 5) text.text else text.text + "\n"}
                    Log.d(TAG, "process: puzzle nums: \n$str")
                    //                    Toast.makeText(this, "Detected possible puzzle, but something went wrong, further processing", Toast.LENGTH_SHORT).show()
                    //crop
                    //                    var minX: Int = puzzleNums.minOfOrNull{it.cornerPoints?.get(0)?.x ?: 0} ?: 0//min x top left corner
                    //                    var minY: Int = puzzleNums.minOfOrNull{it.cornerPoints?.get(0)?.y ?: 0} ?: 0//min y top left corner
                    var maxX: Int =
                        puzzleNums.maxOfOrNull {it.cornerPoints?.get(2)?.x ?: mBitmap!!.width}
                            ?: mBitmap!!.width//max x bottom right corner
                    var maxY: Int =
                        puzzleNums.maxOfOrNull {it.cornerPoints?.get(2)?.y ?: mBitmap!!.height}
                            ?: mBitmap!!.height//max y bottom right corner
                    val margin = 20 //leave margin
                    minX = max(0, minX - margin)
                    minY = max(0, minY - margin)
                    maxX = min(mBitmap!!.width, maxX + margin)
                    maxY = min(mBitmap!!.height, maxY + margin)
                    val width = maxX - minX
                    val height = maxY - minY
                    val croppedBm = Bitmap.createBitmap(mBitmap!!, minX, minY, width, height)
                    applyFiltersInPlace(croppedBm, OtsuThreshold(), Dilatation(2), Invert())
                    mProcessedBitmap = croppedBm
                    saveToFile(mProcessedBitmap!!, "puzzle_solver_modified_cropped")
                    val imgC = InputImage.fromBitmap(mProcessedBitmap!!, 0)//rotate 0 degrees
                    val recognizerC = TextRecognition.getClient()
                    recognizerC.process(imgC)
                        .addOnSuccessListener { resultC ->
//                            Log.d(TAG, "process: cropped OCR Done:\n${resultC.text}")
                            val (puzzleNumsC, puzzleTypeC) = parsePuzzleFromText(resultC)
                            val puzzleSizeC = sqrt(puzzleNumsC.size.toDouble())
                            val strC = puzzleNumsC.withIndex()
                                .joinToString(" ") {(i, text) -> if (i % 6 != 5) text.text else text.text + "\n"}
                            Log.d(TAG, "process: puzzle nums: \n$strC")
                            if (puzzleNumsC.size < 8 || puzzleSizeC.toInt()
                                    .compareTo(puzzleSizeC) != 0
                            ) {//doesn't work, stop
                                Log.e(
                                    TAG,
                                    "process: cropped OCR Missed number or something is wrong, size of puzzle is wrong: ${puzzleNumsC.size}"
                                )
                                Toast.makeText(
                                    this,
                                    "Failed to detect the puzzle, try again later :(",
                                    Toast.LENGTH_SHORT
                                ).show()
                                Handler(Looper.getMainLooper()).post {stop()}
                            } else {//SUCCESS
                                Toast.makeText(this, "Puzzle detected, solving...", Toast.LENGTH_SHORT).show()
                                solveWithText(puzzleNumsC.chunked(puzzleSizeC.toInt()))
                            }
                        }
                } else {//success
                    val str = puzzleNums.withIndex()
                        .joinToString(" ") {(i, text) -> if (i % puzzleSize != puzzleSize - 1) text.text else text.text + "\n"}
                    Log.d(TAG, "process: puzzle type: $puzzleType")
                    Log.d(TAG, "process: puzzle nums: \n$str")
                    Toast.makeText(this, "Puzzle detected, solving...", Toast.LENGTH_SHORT).show()
                    solveWithText(puzzleNums.chunked(puzzleSize.toInt()))
                }
            }.addOnFailureListener {e ->
                // Task failed with an exception
                Log.e(TAG, "process: OCR failed.", e)
                Toast.makeText(this, "OCR Failed, try again later :(", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).post {stop()}
            }
    }

    private suspend fun makeRandomMoves(boardConfig: MoveMaker.BoardConfig, cnt: Int = 10) =
        suspendCancellableCoroutine<Boolean> {
            val boardSize = boardConfig.size
            val rand = ThreadLocalRandom.current()
            val moves = mutableListOf<P>()
            val dirVals = Dir.values()
            for (i in 1..cnt) {
                val dir = dirVals[rand.nextInt(dirVals.size)]
                val loc = rand.nextInt(boardSize)
                for (c in 1..rand.nextInt(1, boardSize))//how many "same moves"//how long to swipe
                    moves.add(Pair(dir, loc))
            }
            val mm = MoveMaker(moves, boardConfig, object : MoveMaker.MovingStateListener {
                override fun movePrepared(move: P, cnt: Int) {
                    Log.d(TAG, "random move prepared: $move, $cnt")
                }

                override fun moveComplete(move: P, cnt: Int) {
                    Log.d(TAG, "random move complete: $move, $cnt")
                }

                override fun moveCancelled(move: P, cnt: Int) {
                    Log.d(TAG, "random move cancelled: $move, $cnt")
                }

                override fun allMovesComplete() {
                    Log.d(TAG, "random moves all done!")
                    it.resume(true)
                }

            })
            mm.makeMoves()
        }

    @Suppress("UNUSED_VARIABLE")
    private fun parsePuzzleFromText(result: Text): Pair<MutableList<Text.Element>, String> {
        setStatusText("Parsing Puzzle")
        val puzzleNums = mutableListOf<Text.Element>()
        var puzzleType = ""
        var yBound = 0
        for (block in result.textBlocks) {
            val blockText = block.text
            val blockCornerPoints = block.cornerPoints
            val blockFrame = block.boundingBox
//            Log.d(TAG, "process: block text: $blockText, ${block.lines.map {it.text}}")
            for (line in block.lines) {
                val lineText = line.text
                val lineCornerPoints = line.cornerPoints
                val lineFrame = line.boundingBox
                //                        Log.d(TAG, "process: line text: $lineText, ${line.elements.map {it.text}}")
                for (element in line.elements) {
                    val elementText = element.text
                    val elementCornerPoints = element.cornerPoints
                    val elementFrame = element.boundingBox
                    if (elementText.contains("puzzle", true)) puzzleType = elementText
                    if (element.cornerPoints?.get(0)?.y ?: mHeight > yBound && elementText.isDigitsOnly()
                        && elementText.toInt() in 1..100) puzzleNums.add(element)
                    else yBound = element.cornerPoints?.get(2)?.y ?: 0
                }
            }
        }
        return Pair(puzzleNums, puzzleType)
    }

    private fun restartImageReader() {
        mVirtualDisplay?.release()
        mImageReader?.close()
        @SuppressLint("WrongConstant")//something is wrong with the linter
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        mVirtualDisplay = App.mediaProjection!!.createVirtualDisplay(
            "Puzzle Solver Screen Capture",
            mWidth,
            mHeight,
            mDensity,
            VIRTUAL_DISPLAY_FLAGS,
            mImageReader!!.surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    super.onPaused()
                    Log.d(TAG, "onPaused: virtual display paused")
                }

                override fun onResumed() {
                    super.onResumed()
                    Log.d(TAG, "onResumed: virtual display resumed")
                }

                override fun onStopped() {
                    super.onStopped()
                    Log.d(TAG, "onStopped: virtual display stopped")
                }
            },
            null
        )
        startImageReaderListening()
    }

    private fun startImageReaderListening(){
        Log.d(TAG, "startImageReaderListening: started listening")
//        if(mImageAvailableListener == null) mImageAvailableListener = ImageAvailableListener()
        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
    }

    private fun createVirtualDisplay() {
        // display metrics
        if (App.mediaProjection == null) {//likely terminated, stop
            Log.d(TAG, "createVirtualDisplay: mediaProjection is null")
            return
        }
        if (mImageReader == null) {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION") // android sucks and doesn't have a way to get real metrics in a service other than this (or passing from activities)
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
//            val displayMetrics = resources.displayMetrics
            mHeight = displayMetrics.heightPixels
            mWidth = displayMetrics.widthPixels
            mDensity = displayMetrics.densityDpi
            Log.d(TAG, "createVirtualDisplay: DIMS: $mHeight, $mWidth")
            // start capture reader
            @SuppressLint("WrongConstant")//something is wrong with the linter
            mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        }
        mVirtualDisplay = App.mediaProjection!!.createVirtualDisplay(
            "Puzzle Solver Screen Capture",
            mWidth,
            mHeight,
            mDensity,
            VIRTUAL_DISPLAY_FLAGS,
            mImageReader!!.surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    super.onPaused()
                    Log.d(TAG, "onPaused: virtual display paused")
                }

                override fun onResumed() {
                    super.onResumed()
                    Log.d(TAG, "onResumed: virtual display resumed")
                }

                override fun onStopped() {
                    super.onStopped()
                    Log.d(TAG, "onStopped: virtual display stopped")
                }
            },
            null
        )
    }

    private inner class ImageAvailableListener : OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            var image: Image? = null
            var bitmap: Bitmap? = null
            try {
                Log.d(TAG, "onImageAvailable: Image Available!")
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes: Array<Image.Plane> = image.planes
                    if (planes[0].buffer == null) return
                    reader.setOnImageAvailableListener(null, null)//stop listening for new images until called later
//                    Log.d(TAG, "onImageAvailable: Resume visibility")
                    Handler(Looper.getMainLooper()).post {overlayView.visibility = View.VISIBLE}
                    val buffer: ByteBuffer = planes[0].buffer
                    val pixelStride: Int = planes[0].pixelStride
                    val rowStride: Int = planes[0].rowStride
                    val rowPadding: Int = rowStride - pixelStride * mWidth

                    // create bitmap
                    bitmap = Bitmap.createBitmap(
                        mWidth + rowPadding / pixelStride, // row padding stuff
                        mHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    //crop off padding
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, mWidth, mHeight)

//                    mBitmap?.recycle() // dispose the old one
                    mBitmap = bitmap.copy(bitmap.config, false)

                    // write bitmap to a file
                    saveToFile(bitmap, "puzzle_solver_screen")

                    //process
                    process(mBitmap!!)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                bitmap?.recycle()
                image?.close()
            }
        }
    }

    private inner class OrientationChangeCallback(context: Context?) :
        OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val rotation: Int? = display?.rotation
            if (rotation != null && rotation != mRotation) {
                mRotation = rotation
                try {
                    // clean up
                    mVirtualDisplay?.release()
                    mImageReader?.close()

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay()
                    startImageReaderListening()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "stopping projection.")
            mHandler?.post {
                mVirtualDisplay?.release()
                mVirtualDisplay = null
                mImageReader?.close()
                mImageReader = null
                mOrientationChangeCallback?.disable()
                App.mediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
                App.mediaProjection = null
                if(stopService) stopSelf()//stop service
            }
        }
    }

    override fun onDestroy() {
        running = false
        GestureService.setInstanceListener(null)
        super.onDestroy()
        if (this::overlayView.isInitialized) windowManager.removeView(overlayView)
    }
}