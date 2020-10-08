package com.jackz314.puzzlesolver

import com.jackz314.puzzlesolver.TorusPuzzleSolver.Companion.Dir
import com.jackz314.puzzlesolver.TorusPuzzleSolver.Companion.Dir.*
import com.jackz314.puzzlesolver.TorusPuzzleSolver.Companion.Dir.R
import kotlin.math.abs

typealias P = Pair<Dir, Int>

class TorusPuzzleSolver(list: MutableList<MutableList<Int>>) {

    companion object{
        enum class Dir{
            L, R, U, D
        }
    }

    private val a = list
    private val b = a.flatten().sorted().toMutableList()
    private val len = a.size
    private val lastIdx = len-1
    private val r = mutableListOf<P>()

    fun solve(): List<P>{
        rotate()
        return r
    }

    private fun vertical(col: Int, _target: Int = -1){
        var target = _target
        target = target mod len
        if(len - target < target){// move down
            for (k in 0 until len - target) {
                if(r.isNotEmpty() && r(-1) == P(U, col))
                    r.removeLast()
                else
                    r.add(P(D, col))
                target = a(-1)(col)
                for (i in 0 until len-1)
                    a(-1 - i)[col] = a(-2 - i)[col]
                a[0][col] = target
            }
        }else{ // move up
            for (k in 0 until target) {
                if(r.isNotEmpty() && r(-1) == P(D, col))
                    r.removeLast()
                else
                    r.add(P(U, col))
                target = a[0][col]
                for (i in 0 until len-1)
                    a[i][col] = a[i + 1][col]
                a(-1)[col] = target
            }
        }
    }

    private fun horizontal(row: Int, _target: Int = -1){
        var target = _target
        target = target mod len
        if(len - target < target){// move right
            for (k in 0 until len - target) {
                if(r.isNotEmpty() && r(-1) == P(L, row))
                    r.removeLast()
                else
                    r.add(P(R, row))
//                a[row] = (a[row][-1..len] + a[row][0..-1]).toMutableList()
                a[row] = (a[row][-1..len] + a[row][0 until len]).toMutableList()
            }
        }else{ // move up
            for (k in 0 until target) {
                if(r.isNotEmpty() && r(-1) == P(R, row))
                    r.removeLast()
                else
                    r.add(P(L, row))
                a[row] = (a[row][1..len] + a[row][0..1]).toMutableList()
            }
        }
    }

    private fun rotate(){
        for (i in 0 until len - 1) {
            for (j in 0 until len) {
                val c = b.removeAt(0)
                var e = a.flatten().indexOf(c)
                if(e / len == i){
                    if(j == 0)
                        horizontal(i, e - j)
                    else if(j < e mod len){
                        if(i != 0){
                            vertical(e mod len, 1)
                            horizontal(i, j - e)
                            vertical(e mod len)
                            horizontal(i, e - j)
                        }else{
                            vertical(e)
                            horizontal(1, e - j)
                            vertical(j, 1)
                        }
                    }
                }else{
                    if(j == e mod len){
                        horizontal(e / len)
                        ++e
                        if(e mod len == 0) e -= len // e = 0
                    }
                    if(i != 0)
                        vertical(j, i - e / len)
                    horizontal(e / len, e - j)
                    vertical(j, e / len - i)
                }
            }
        }
        val c = a(-1).map{ x -> b.indexOf(x)}.toMutableList()
        val cNew = mutableListOf<Int>()
        for (i in 0 until len) {
            var sum1 = 0
            for(j in 0 until len) for (k in 0 until j) sum1 += if(c((i + j) mod len) < c((i + k) mod len)) 1 else 0
            //if sum1 is odd, add total size, otherwise add sum2
            cNew.add(if (sum1 mod 2 != 0) len * len else (0 until len).sumOf { j -> abs(c((i + j) mod len) - j) })
        }
        var e = listOf(cNew.asReversed().indexOf(cNew.minOrNull()).inv(),
            cNew.indexOf(cNew.minOrNull()))
            .minByOrNull { abs(it) }!!
        horizontal(len - 1, e)//last row
        for (j in 0 until len - 2){
            e = a(-1).indexOf(b[j])
            if(e > j){
                var c1 = b.indexOf(a(-1)[j])
                if(c1 == e){
                    if(e-j == 1) c1 = j + 2
                    else c1 = j + 1
                }
                vertical(e)
                horizontal(len - 1, j - e)
                vertical(e, 1)
                horizontal(len - 1, c1 - j)
                vertical(e)
                horizontal(len - 1, e - c1)
                vertical(e, 1)
            }
        }
    }
}

//mathematical mod operator, "circular" like python's, never gives negative result
private infix fun Int.mod(y: Int): Int {
    val result = this % y
    return if (result < 0) result + y else result
}

//"safe" index
private infix fun Int.si(size: Int): Int = if (this >= 0) this else size+this

//private operator fun <E> List<E>.get(range: IntRange) = subList(range.first si size, range.last si size)
private operator fun <E> MutableList<E>.get(range: IntRange) = subList(
    range.first si size,
    range.last si size
)

//access with list(index)
private operator fun <E> List<E>.invoke(range: IntRange) = subList(
    range.first si size,
    range.last si size
)

private operator fun <T> List<T>.invoke(i: Int): T = get(i si size)