package com.jackz314.puzzlesolver

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout

private const val TAG = "MovableLayout"

class MovableLayout: ConstraintLayout {

    constructor(context:Context) : super(context) {}
    constructor(context:Context, attrs:AttributeSet) : super(context, attrs) {}
    constructor(context:Context, attrs:AttributeSet, defStyleAttr:Int) : super(context, attrs, defStyleAttr) {}
    constructor(context:Context, attrs:AttributeSet, defStyleAttr:Int, defStyleRes:Int) : super(context, attrs, defStyleAttr, defStyleRes) {}

    private var interceptListener: OnInterceptTouchListener? = null

    public fun setInterceptTouchListener(listener: OnInterceptTouchListener){
        interceptListener = listener
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
//        Log.d(TAG, "onInterceptTouchEvent: Event: ${e.action}")
        return interceptListener?.onInterceptTouchEvent(e) ?: super.onInterceptTouchEvent(e)
    }

    public interface OnInterceptTouchListener{
        fun onInterceptTouchEvent(e: MotionEvent): Boolean
    }

}