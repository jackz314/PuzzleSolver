package com.jackz314.puzzlesolver

import Catalano.Imaging.Concurrent.Filters.Dilatation
import Catalano.Imaging.Concurrent.Filters.Invert
import Catalano.Imaging.Concurrent.Filters.OtsuThreshold
import Catalano.Imaging.FastBitmap
import Catalano.Imaging.IApplyInPlace
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.PixelFormat
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.os.HandlerCompat
import androidx.core.text.isDigitsOnly
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.jackz314.puzzlesolver.MovableLayout.OnInterceptTouchListener
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


private const val TAG = "SolveService"
private const val ONGOING_NOTIFICATION_ID = 10086
private const val ONGOING_CHANNEL_ID = "pzslver_notif_chn"
private const val VIRTUAL_DISPLAY_FLAGS =
    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

class SolveService : Service() {

    companion object{
        @JvmStatic
        var running = false
    }

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
    private var process = true
    private var mToast: Toast? = null

    private var mDuration = 20

    //solve stuff
    private var minX = 0
    private var minY = 0
    private var xStart = -1 // location of starting element of the board horizontally
    private var yStart = -1 // location of starting element of the board vertically

    private var moveHistoryList = LinkedList<String>()

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
        mDuration = intent?.getIntExtra("DURATION", 20) ?: 20
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
    //            .setContentIntent(pendingIntent)
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
        solveBtn = overlayView.findViewById(R.id.solveBtn);
        solveBtn.setOnClickListener {
            if (solving) {
                exit()
            } else {
                mToast = Toast.makeText(this, "Solving, getting permission...", Toast.LENGTH_SHORT)
                    .apply { show() }
                tryStart()
            }
        }
        statusText = overlayView.findViewById(R.id.statusText)
        moveStateText = overlayView.findViewById(R.id.moveStateText)
        val thread = HandlerThread("CaptureThread").also { it.start() }
        mHandler = HandlerCompat.createAsync(thread.looper)
    }

    private fun setStatusText(str: String){
        Handler(Looper.getMainLooper()).post { statusText.text = str }
    }

    private fun tryStart(){
        setStatusText("Getting Permissions")
        if(resumeCapture || App.mediaProjection == null){
            Log.d(TAG, "tryStart: getting permissions")
            App.getScreenshotPermission(this, object : App.SCPermissionListener {
                override fun onSCPermissionResult(result: Boolean) {
                    //wait for a while before starting
                    if (result) start()
                }
            })
        }else start()
    }

    private fun start(){
        setStatusText("Starting")
        solveBtn.setImageResource(R.drawable.ic_stop_gray)
        solving = true
        overlayView.visibility = View.GONE // hide self momentarily
        mToast?.cancel()
        mToast = null
//        Toast.makeText(this, "GOT PERMISSION!", Toast.LENGTH_SHORT).show()
        mBitmap?.recycle()//dispose old one if one exists
        mProcessedBitmap?.recycle()
        Handler(mainLooper).postDelayed({
            if(resumeCapture){
                resumeCapture = false
                setStatusText("Scanning screen")
//                createVirtualDisplay()
                restartImageReader()
            }
            else {
                startCapture()
            }//resuming
        },250)//wait for UI to hide
    }

    private fun startCapture(){
        setStatusText("Scanning screen")
        createVirtualDisplay()
        startImageReaderListening()
        //create or confirm saving directory
        val externalFilesDir: File? = getExternalFilesDir(null)
        if (externalFilesDir != null) {
            mStoreDir = externalFilesDir.absolutePath.toString() + "/screenshots/"
            val storeDirectory = File(mStoreDir!!)
            if (!storeDirectory.exists()) {
                val success: Boolean = storeDirectory.mkdirs()
                if (!success) {
                    Log.e(TAG, "failed to create file storage directory.")
                    return
                }
            }
            Log.d(TAG, "start: GOT DIRECTORY AT: $mStoreDir")
        }

        // register orientation change callback
        mOrientationChangeCallback = OrientationChangeCallback(this)
        if (mOrientationChangeCallback!!.canDetectOrientation()) {
            mOrientationChangeCallback!!.enable()
        }

        // register media projection stop callback
        App.mediaProjection?.registerCallback(MediaProjectionStopCallback(), mHandler)
    }

    private fun exit(){
        Toast.makeText(this, "Stopped solving", Toast.LENGTH_SHORT).show()
//            solveBtn.setImageResource(R.drawable.ic_play_gray)
        stopService = true
        mHandler!!.post {
            App.mediaProjection?.stop()
        }
    }

    //stop solving and capturing, can resume later
    private fun stop(){
        setStatusText("Idle")
        moveStateText.text = ""
        moveHistoryList.clear()
        mHandler!!.post {
            App.mediaProjection?.stop()
        }
        overlayView.visibility = View.VISIBLE
        solveBtn.setImageResource(R.drawable.ic_play_gray)
        solving = false
    }

    private fun saveToFile(bitmap: Bitmap, name: String){
        try {
            val filename = "${name}_${++mImgCnt}.png"
            val fos = FileOutputStream("${mStoreDir.toString()}/$filename")
            bitmap.compress(CompressFormat.PNG, 100, fos)
            fos.close()
            Log.d(TAG, "captured image: $filename")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addToMoveState(str: String){
        if(moveHistoryList.size >= 5) moveHistoryList.removeFirst()
        moveHistoryList.add(str)
        val finalHistory = moveHistoryList.joinToString("\n")
        Handler(Looper.getMainLooper()).post { moveStateText.text = finalHistory }
    }

    private fun appendToMoveState(str: String){
        if(moveHistoryList.isEmpty()) return
        moveHistoryList[moveHistoryList.size - 1] += str
        val finalHistory = moveHistoryList.joinToString("\n")
        Handler(Looper.getMainLooper()).post { moveStateText.text = finalHistory }
    }

    private fun makeMoves(moves: List<P>, boardConfig: MoveMaker.BoardConfig) {
        setStatusText("Making Moves")
        Log.d(TAG, "makeMoves: Making moves: board config: $boardConfig")
        val mm = MoveMaker(
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
                    }

                    Handler(Looper.getMainLooper()).postDelayed({GestureService.getInstance()!!.click(730, 2430)},2000)
                    Handler(Looper.getMainLooper()).postDelayed({GestureService.getInstance()!!.click(730, 1950)},3000)
                    Handler(Looper.getMainLooper()).postDelayed({GestureService.getInstance()!!.click(730, 1667)},4000)
                    Handler(Looper.getMainLooper()).postDelayed({ resumeCapture = true; start() },5000)
                    /*GlobalScope.launch {
                        delay(500L)
                        Log.d(TAG, "allMovesComplete: DELAY 1")
                        GestureService.getInstance()!!.click(730, 2430)//temporary used to repeat puzzle
                        Log.d(TAG, "allMovesComplete: DELAY 2")
                        delay(500L)
                        Log.d(TAG, "allMovesComplete: DELAY 3")
                        GestureService.getInstance()!!.click(730, 1950)//temporary used to repeat puzzle
                        delay(500L)
                        Log.d(TAG, "allMovesComplete: DELAY 4")
                        GestureService.getInstance()!!.click(730, 1667)//temporary used to repeat puzzle
                        delay(500L)
                        Log.d(TAG, "allMovesComplete: DELAY 5")
                        Handler(Looper.getMainLooper()).post { start() }
                    }*/
                }

            }, mDuration)
        mm.makeMoves()
    }

    private fun solve(list: List<List<Text.Element>>){
        setStatusText("Solving Puzzle")
        Log.d(TAG, "solve: solving: \n${list.map { it.map { it.text } }.joinToString("\n")}")
        val solver = TorusPuzzleSolver(list.map{row->row.map{it.text.toInt()}.toMutableList()}.toMutableList())
        val solution = solver.solve()
        Log.d(TAG, "solve: GOT SOLUTION (${solution.size} moves): ${solution.map { "'" + it.first.name + it.second + "'"}}")

        //compute average block size
        val firstRowPoints = list[0].map{it.cornerPoints!![0]}
        //        firstRowPoints.drop(1).forEachIndexed { i, point -> totalDistance += point.x - firstRowPoints[i].x }
        val avgBlockSideLen = (firstRowPoints.last().x - firstRowPoints.first().x).toFloat() / (list.size-1)
        makeMoves(solution, MoveMaker.BoardConfig(list.size, avgBlockSideLen, xStart.toFloat(), yStart.toFloat()))
    }

    private fun applyFiltersInPlace(bitmap: Bitmap, vararg filters: IApplyInPlace){
        val fb = FastBitmap(bitmap)
        fb.toGrayscale()
        filters.forEach { it.applyInPlace(fb) }
        fb.toBitmap()//save all filters back to bitmap
    }

    private fun process(bitmap: Bitmap) {
        Log.e(TAG, "process: PROCESSING")
        setStatusText("Processing Image")
        //adaptive thresholding and stuff
        mProcessedBitmap = bitmap.copy(bitmap.config, true)
        applyFiltersInPlace(mProcessedBitmap!!, OtsuThreshold(), Dilatation(2), Invert())
        //make a copy so the bitmap doesn't get recycled before OCR'd
//        processedBM = processedBM.copy(processedBM.config, true)
        saveToFile(mProcessedBitmap!!, "puzzle_solver_modified")

        setStatusText("Finding Puzzle")

        //recognize text
        val img = InputImage.fromBitmap(mProcessedBitmap!!, 0)//rotate 0 degrees
        val recognizer = TextRecognition.getClient()
        val resultTask = recognizer.process(img)
            .addOnSuccessListener { result ->
                mProcessedBitmap?.recycle()
                // Task completed successfully
                val resultText = result.text//full text
                Log.d(TAG, "process: OCR Done:\n$resultText")
                val (puzzleNums, puzzleType) = parsePuzzleFromText(result)
                if(puzzleNums.size < 8){ // minimum 8 numbers not matched, fail
                    Log.e(TAG,"process: OCR Missed number or something is wrong, size of puzzle is wrong: ${puzzleNums.size}")
                    Toast.makeText(this, "Failed to detect the puzzle, try again later :(", Toast.LENGTH_SHORT).show()
                    Handler(Looper.getMainLooper()).post {stop()}
                    return@addOnSuccessListener
                }
//                Log.d(TAG, "process: first point: ${puzzleNums[0].cornerPoints.contentToString()} ${puzzleNums[0].boundingBox}")
                xStart = puzzleNums.minByOrNull{it.cornerPoints?.get(0)?.x ?: 0}//get min x element
                    ?.also {minX = it.cornerPoints?.get(0)?.x ?: 0}//store minX for potential use later
                    ?.run {cornerPoints?.let { (it[0].x + it[1].x)/2 }} ?: 0 //get middle point of this element as xStart
                yStart = puzzleNums.minByOrNull{it.cornerPoints?.get(0)?.y ?: 0}//get min y element
                    ?.also {minY = it.cornerPoints?.get(0)?.y ?: 0}//store minY for potential use later
                    ?.run {cornerPoints?.let { (it[0].y+it[3].y)/2 }} ?: 0 //get middle point of this element as yStart
                val puzzleSize = sqrt(puzzleNums.size.toDouble())
                if(puzzleSize.toInt().compareTo(puzzleSize) != 0){//result might be close, try again by cropping
                    Log.w(TAG, "process: Detected possible puzzle, but something went wrong, further processing: ${puzzleNums.size}")
                    setStatusText("Reprocessing Image")
                    val str = puzzleNums.withIndex()
                        .joinToString(" ") { (i, text) -> if (i % 6 != 5) text.text else text.text + "\n" }
                    Log.d(TAG, "process: puzzle nums: \n$str")
//                    Toast.makeText(this, "Detected possible puzzle, but something went wrong, further processing", Toast.LENGTH_SHORT).show()
                    //crop
//                    var minX: Int = puzzleNums.minOfOrNull{it.cornerPoints?.get(0)?.x ?: 0} ?: 0//min x top left corner
//                    var minY: Int = puzzleNums.minOfOrNull{it.cornerPoints?.get(0)?.y ?: 0} ?: 0//min y top left corner
                    var maxX: Int = puzzleNums.maxOfOrNull{it.cornerPoints?.get(2)?.x ?: mBitmap!!.width} ?: mBitmap!!.width//max x bottom right corner
                    var maxY: Int = puzzleNums.maxOfOrNull{it.cornerPoints?.get(2)?.y ?: mBitmap!!.height} ?: mBitmap!!.height//max y bottom right corner
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
                            Log.d(TAG, "process: cropped OCR Done:\n${resultC.text}")
                            val (puzzleNumsC, puzzleTypeC) = parsePuzzleFromText(resultC)
                            val puzzleSizeC = sqrt(puzzleNumsC.size.toDouble())
                            val strC = puzzleNumsC.withIndex()
                                .joinToString(" ") { (i, text) -> if (i % 6 != 5) text.text else text.text + "\n" }
                            Log.d(TAG, "process: puzzle nums: \n$strC")
                            if(puzzleNumsC.size < 8 || puzzleSizeC.toInt().compareTo(puzzleSizeC) != 0){//doesn't work, stop
                                Log.e(TAG,"process: cropped OCR Missed number or something is wrong, size of puzzle is wrong: ${puzzleNumsC.size}")
                                Toast.makeText(this, "Failed to detect the puzzle, try again later :(", Toast.LENGTH_SHORT).show()
                                Handler(Looper.getMainLooper()).post {stop()}
                            }else{//SUCCESS
                                Toast.makeText(this, "Puzzle detected, solving...", Toast.LENGTH_SHORT).show()
                                solve(puzzleNumsC.chunked(puzzleSizeC.toInt()))
                            }
                        }
                } else{//success
                    val str = puzzleNums.withIndex()
                        .joinToString(" ") { (i, text) -> if (i % puzzleSize != puzzleSize - 1) text.text else text.text + "\n" }
                    Log.d(TAG, "process: puzzle type: $puzzleType")
                    Log.d(TAG, "process: puzzle nums: \n$str")
                    Toast.makeText(this, "Puzzle detected, solving...", Toast.LENGTH_SHORT).show()
                    solve(puzzleNums.chunked(puzzleSize.toInt()))
                }
            }.addOnFailureListener { e ->
                // Task failed with an exception
                Log.e(TAG, "process: OCR failed.", e)
                Toast.makeText(this, "OCR Failed, try again later :(", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).post {stop()}
            }
//        Log.d(TAG, "process: after OCR SSSS")
        //after done processing, resume listener to check for finish status
//        overlayView.visibility = View.INVISIBLE // hide self momentarily
//        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
    }

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

    private fun restartImageReader(){
        mImageReader?.close()
        @SuppressLint("WrongConstant")//something is wrong with the linter
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        startImageReaderListening()
        mVirtualDisplay!!.surface = mImageReader!!.surface
    }

    private fun startImageReaderListening(){
        Log.d(TAG, "startImageReaderListening: started listening")
        if(mImageAvailableListener == null) mImageAvailableListener = ImageAvailableListener()
        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
    }

    private fun createVirtualDisplay() {
        // display metrics
        if(App.mediaProjection == null) {//likely terminated, stop
            Log.d(TAG, "createVirtualDisplay: mediaProjection is null")
            return
        }
        if(mImageReader == null){
            val displayMetrics = DisplayMetrics()
            display?.getRealMetrics(displayMetrics)
            mHeight = displayMetrics.heightPixels
            mWidth = displayMetrics.widthPixels
            mDensity = displayMetrics.densityDpi
            Log.d(TAG, "createVirtualDisplay: DIMS: $mHeight, $mWidth");
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
            Log.d(TAG, "onImageAvailable: Image Available!")
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    reader.setOnImageAvailableListener(null, null)//stop listening for new images until called later
//                    Log.d(TAG, "onImageAvailable: Resume visibility")
                    Handler(Looper.getMainLooper()).post{overlayView.visibility = View.VISIBLE}
                    val planes: Array<Image.Plane> = image.planes
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
                    process = false
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
        super.onDestroy()
        if (this::overlayView.isInitialized) windowManager.removeView(overlayView)
    }
}