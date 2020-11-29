package cn.dwvoid.beacon

import android.bluetooth.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

private val SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
private val DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

open class BeaconRssiPoller(protected val scope: CoroutineScope) : BluetoothGattCallback() {
    protected var device: BluetoothGatt? = null
    private var rssiRate = -1
    var onRssi: (Int) -> Unit = {}
    var onLost: () -> Unit = {}

    fun enableRssi(rate: Int) {
        rssiRate = rate
        if (rate > 0) startRssiRequest()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                device = gatt
            }
            BluetoothProfile.STATE_DISCONNECTED -> scope.launch { cbOnLost() }
        }
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        startRssiRequest()
        scope.launch { onRssi(rssi) }
    }

    private suspend fun requestRssi() {
        val rate = rssiRate
        if (rate > 0) {
            delay(rate.toLong())
            device?.readRemoteRssi()
        }
    }

    private fun cbOnLost() = onLost()

    private fun startRssiRequest() {
        scope.launch { requestRssi() }
    }

    fun close() = device?.close()
}

class BeaconPrimary(scope: CoroutineScope) : BeaconRssiPoller(scope) {
    private lateinit var characteristic: BluetoothGattCharacteristic
    private val command = CommandSlot()
    private var cmdSenderLive = false
    var onInit: () -> Unit = {}
    var onRead: (String) -> Unit = {}

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
        }
    }

    override fun onDescriptorWrite(g: BluetoothGatt, x: BluetoothGattDescriptor, s: Int) {
        super.onDescriptorWrite(g, x, s)
        if (x.uuid == DESCRIPTOR_UUID) scope.launch { cbOnInit() }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        characteristic = gatt.getService(SERVICE_UUID).getCharacteristic(CHARACTERISTIC_UUID)
        gatt.setCharacteristicNotification(characteristic, true)
        val desc = characteristic.getDescriptor(DESCRIPTOR_UUID)
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, x: BluetoothGattCharacteristic) {
        super.onCharacteristicChanged(gatt, x)
        if (x == characteristic) scope.launch { cbOnRead(characteristic.getStringValue(0)) }
    }

    override fun onCharacteristicWrite(x: BluetoothGatt, v: BluetoothGattCharacteristic, s: Int) {
        super.onCharacteristicWrite(x, v, s)
        synchronized(this) { if (cmdSenderLive) cmdSenderLive = writeCommand() }
    }

    private fun cbOnInit() = onInit()

    private fun cbOnRead(x: String) = onRead(x)

    fun write(v: String) {
        characteristic.setValue(v)
        device?.writeCharacteristic(characteristic)
    }

    private fun writeCommand(): Boolean {
        val x = command.get()
        if (x != null) {
            characteristic.value = x
            device?.writeCharacteristic(characteristic)
        }
        return x != null
    }

    fun cmd(x: Double, y: Double, z: Double) {
        synchronized(this) {
            command.push(x, y, z)
            if (!cmdSenderLive) writeCommand()
        }
    }
}