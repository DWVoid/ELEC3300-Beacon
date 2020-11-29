package cn.dwvoid.beacon

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CommandSlot {
    private var v = false
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0

    fun push(x: Double, y: Double, z: Double) {
        this.v = true
        this.x = x
        this.y = y
        this.z = z
    }

    fun get() = if (this.v) command() else null

    private fun command(): ByteArray {
        v = false
        val rx = (x * 1000.0).toInt().toShort()
        val ry = (y * 1000.0).toInt().toShort()
        val rz = (z * 1000.0).toInt().toShort()
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('C'.toByte()).put('X'.toByte())
        buffer.putShort(rx).putShort(ry).putShort(rz)
        return buffer.array()
    }
}