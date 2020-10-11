package com.jackz314.puzzlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.util.Log
import com.jackz314.puzzlesolver.TorusPuzzleSolver.Companion.Dir.*
import com.jackz314.puzzlesolver.TorusPuzzleSolver.Companion.Dir.R
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "MoveMaker"

class MoveMaker(
    private val moves: MutableList<P>,
    private val boardConfig: BoardConfig,
    private val listener: MovingStateListener,
    private val duration: Int = 10,
    private val delay: Int = 0,
    private val swipeDistance: Float = boardConfig.blockSideLen,
) {

    data class BoardConfig(val size: Int, val blockSideLen: Float, val xStart: Float, val yStart: Float,
                           val xEnd: Float = xStart + (size - 1) * blockSideLen,
                           val yEnd: Float = yStart + (size - 1) * blockSideLen) {
        val colLocations = Array(size) {xStart + it * blockSideLen}
        val rowLocations =
            Array(size) {yStart + it * blockSideLen}//contains horizontal coordinates of each column (x locations)

        override fun toString(): String =
            "BoardConfig(size=$size, blockSideLen=$blockSideLen, xStart=$xStart, yStart=$yStart, xEnd=$xEnd, yEnd=$yEnd\n" +
                "colLocations=${colLocations.contentToString()}, rowLocations=${rowLocations.contentToString()})"
    }

    private val shift = 0

    private var moveJob: Job? = null

    // if null that means something is wrong, accessibility service not available in this app
    private val gestureService = GestureService.getInstance()!!/*.apply{
        setCallback(object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            Log.v(TAG, "onCompleted: Gesture complete: $gestureDescription\n" +
                    "move: ${moves[moveIdx]}, count: $moveCnt")
            listener.moveComplete(moves[moveIdx],moveCnt)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            Log.v(TAG, "onCancelled: Gesture cancelled: $gestureDescription\n" +
                    "move: ${moves[moveIdx]}, count: $moveCnt")
            listener.moveCancelled(moves[moveIdx],moveCnt)
        }
    })}*/

    //repeat the move cnt times
    private suspend fun makeMove(move: P, _cnt: Int = 1) = suspendCancellableCoroutine<Unit> {
        val cnt = _cnt % boardConfig.size
        if (cnt == 0) {
            it.resume(Unit); return@suspendCancellableCoroutine
        } // no need to move anything

        //cancelled, stop here
        if (moveJob == null) return@suspendCancellableCoroutine

        listener.movePrepared(move, cnt)

        //set up callback first
        gestureService.setCallback(object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
//                Log.v(TAG, "onCompleted: Gesture complete: move: $move, count: $cnt")
                listener.moveComplete(move, cnt)
                if (it.isActive) it.resume(Unit)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "onCancelled: Gesture cancelled: move: $move, count: $cnt")
                listener.moveCancelled(move, cnt)
                if (it.isActive) it.resume(Unit)
            }
        })

//        moveIdx += cnt
//        moveCnt = cnt
        val (dir, loc) = move
        when (dir) {
            U, D -> {//start swiping from the first/last element of the column, by count*blockSideLen amount
                val x = boardConfig.colLocations[loc]
                val y = if (dir == D) boardConfig.yStart else boardConfig.yEnd
                val dx = 0F
                val dy =
                    if (dir == D) boardConfig.blockSideLen * cnt else -boardConfig.blockSideLen * cnt
//                Log.d(TAG, "makeMove: $move - $cnt, $x, $y, $dx, $dy")
                gestureService.swipe(x + shift, y + shift, dx, dy, duration * cnt)
            }
            L, R -> {//start swiping from the first/last element of the row, by count*blockSideLen amount
                val x = if (dir == R) boardConfig.xStart else boardConfig.xEnd
                val y = boardConfig.rowLocations[loc]
                val dx =
                    if (dir == R) boardConfig.blockSideLen * cnt else -boardConfig.blockSideLen * cnt
                val dy = 0F
//                Log.d(TAG, "makeMove: $move - $cnt, $x, $y, $dx, $dy")
                gestureService.swipe(x + shift, y + shift, dx, dy, duration * cnt)
            }
        }
    }

    fun addMove(move: P){
        moves.add(move)
    }

    fun makeMoves() {
        moveJob = GlobalScope.launch {
            gestureService.setDelay(delay)
            var i = 0
            while (i < moves.size) {
                val a = moves[i]
                var cnt = 1
                if (i < moves.size - 1) while (i < moves.size - 1 && a == moves[++i]) ++cnt // stack same moves to execute together
                else ++i
//                Log.d(TAG, "makeMoves: MOVING: ${i-1} ${moves[i-1]} $cnt")
                makeMove(a, cnt)
            }
            Log.d(TAG, "makeMoves: DONE!")
            gestureService.setCallback(null)
            listener.allMovesComplete()
        }
    }

    fun cancelMoves() {
        moveJob?.cancel()
        moveJob = null
    }

    interface MovingStateListener {
        fun movePrepared(move: P, cnt: Int)
        fun moveComplete(move: P, cnt: Int)
        fun moveCancelled(move: P, cnt: Int)
        fun allMovesComplete()
    }
}