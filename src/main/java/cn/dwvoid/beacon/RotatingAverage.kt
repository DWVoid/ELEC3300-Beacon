package cn.dwvoid.beacon

internal class RotatingAverage  {
    private val value = DoubleArray(256)
    private val time = LongArray(256)
    private var tail: Long = 0
    private var head: Long = 0

    fun add(toAdd: Double) {
        if ((head - tail) < 256) {
            val idx = (head++ and 0xFF).toInt()
            time[idx] = System.currentTimeMillis()
            value[idx] = toAdd
        }
    }

    private fun trim() {
        val limit = System.currentTimeMillis() - 150
        while (tail < head) {
            val idx = (tail and 0xFF).toInt()
            if (time[idx] < limit) ++tail else return
        }
    }

    fun average(): Double {
        trim()
        var sum = 0.0
        var x = tail
        while (x < head) sum += value[(x++ and 0xFF).toInt()]
        val count = head - tail
        return if (count > 0) sum / count else 0.0
    }
}