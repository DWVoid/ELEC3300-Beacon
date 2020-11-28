package cn.dwvoid.beacon

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.pow


private const val REQUEST_ENABLE_BT = 31128
private const val SCAN_PERIOD: Long = 5000
private val SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
private val DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private class RotatingAverage  {
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

private class MyConnectionError(val reason: String) : Throwable()

open class BeaconRssiPoller(protected val scope: CoroutineScope) : BluetoothGattCallback() {
    protected var device: BluetoothGatt ? = null
    var onRssi: (Int) -> Unit = {}
    var onLost: () -> Unit = {}

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                device = gatt
                startRssiRequest()
            }
            BluetoothProfile.STATE_DISCONNECTED -> scope.launch { cbOnLost() }
        }
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        startRssiRequest()
        scope.launch { onRssi(rssi) }
    }

    private suspend fun requestRssi() {
        delay(2)
        device?.readRemoteRssi()
    }

    private fun cbOnLost() = onLost()

    private fun startRssiRequest() {
        scope.launch { requestRssi() }
    }

    fun close() = device?.close()
}

class BeaconPrimary(scope: CoroutineScope) : BeaconRssiPoller(scope) {
    private lateinit var characteristic: BluetoothGattCharacteristic
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

    private fun cbOnInit() = onInit()

    private fun cbOnRead(x: String) = onRead(x)

    fun write(v: String) {
        characteristic.setValue(v)
        device?.writeCharacteristic(characteristic)
    }

    fun write(v: ByteArray) {
        characteristic.value = v
        device?.writeCharacteristic(characteristic)
    }
}

class BeaconLocator(
    private val l: BeaconRssiPoller,
    private val r: BeaconRssiPoller,
    private val c: BeaconPrimary,
    private val lPower: Int,
    private val rPower: Int,
    private val cPower: Int,
    private val scope: CoroutineScope
) {
    private var lRssi = RotatingAverage()
    private var rRssi = RotatingAverage()
    private var cRssi = RotatingAverage()
    private var lastClk = (-1).toLong()

    init {
        c.onLost = {
            scope.cancel()
            c.close()
            l.close()
            r.close()
        }
        l.onLost = c.onLost
        r.onLost = c.onLost
    }

    fun close() {
        scope.cancel()
        c.close()
        l.close()
        r.close()
    }

    fun rssiAttach() {
        l.onRssi = { lRssi.add(convert(it, lPower)) }
        r.onRssi = { rRssi.add(convert(it, rPower)) }
        c.onRssi = {
            cRssi.add(convert(it, cPower))
            evaluate()
        }
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()
        try {
            if ((now - lastClk) > 50) {
                lastClk = now
                val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put('C'.toByte()).put('X'.toByte())
                val x = (lRssi.average() * 1000.0).toInt().toShort()
                val y = (cRssi.average() * 1000.0).toInt().toShort()
                val z = (rRssi.average() * 1000.0).toInt().toShort()
                buffer.putShort(x).putShort(y).putShort(z)
                c.write(buffer.array())
            }
        }
        catch(x: Throwable) {
            close()
        }
    }

    private fun convert(x: Int, power: Int) = 1.0 / (10.0.pow((x - power).toDouble() / 20.0))
}

@Suppress("ThrowableNotThrown")
class BeaconTrackerInitializer(
    name: String,
    private val context: Context,
    private val scanner: BluetoothLeScanner
) {
    private val dispatch = Handler(Looper.getMainLooper()).asCoroutineDispatcher()
    private val scope = CoroutineScope(Job() + dispatch)
    private lateinit var data: BluetoothDevice
    private lateinit var beaconL: BluetoothDevice
    private lateinit var beaconR: BluetoothDevice
    private var dataPower: Int = 0
    private var leftPower: Int = 0
    private var rightPower: Int = 0
    private val initTask: Job
    private val async = CompletableDeferred<BeaconLocator>()

    private fun createCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            predicate(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { predicate(it) }
        }

        var predicate: (ScanResult) -> Unit = {}
    }

    private suspend fun primary(name: String) {
        val task = CompletableDeferred<Unit>()
        val callback = createCallback()
        callback.predicate = {
            if (!task.isCompleted && it.device.name == name) {
                data = it.device
                dataPower = it.txPower
                task.complete(Unit)
            }
        }
        scanOnCallback(callback, task)
    }

    private suspend fun secondary(left: String, right: String) {
        val task = CompletableDeferred<Unit>()
        val callback = createCallback()
        callback.predicate = {
            if (it.device.name == left) {
                beaconL = it.device
                leftPower = it.txPower
            }
            if (it.device.name == right) {
                beaconR = it.device
                rightPower = it.txPower
            }
            if (::beaconL.isInitialized && ::beaconR.isInitialized) task.complete(Unit)
        }
        scanOnCallback(callback, task)
    }

    private suspend fun scanOnCallback(callback: ScanCallback, task: CompletableDeferred<Unit>) {
        // start scanner and stop after a predefined period
        try {
            scanner.startScan(callback)
            withTimeout(SCAN_PERIOD) { task.await() }
        } catch (x: Throwable) {
            throw MyConnectionError("device_not_found")
        } finally {
            scanner.stopScan(callback)
        }
    }

    private suspend fun cbCommand(cb: BeaconPrimary, cmd: String): String {
        val task = CompletableDeferred<String>()
        cb.onRead = {
            cb.onRead = {}
            task.complete(it)
        }
        cb.write(cmd)
        return task.await()
    }

    private suspend fun initSequence(primaryCb: BeaconPrimary) {
        try {
            // setup the responder object
            val leftCb = BeaconRssiPoller(scope)
            val rightCb = BeaconRssiPoller(scope)
            val responder = BeaconLocator(
                leftCb, rightCb, primaryCb,
                -55, -55, -55,
                scope
            )
            // run initialization handshake
            cbCommand(primaryCb, "OK")
            val left = cbCommand(primaryCb, "GL")
            val right = cbCommand(primaryCb, "GR")
            // run secondary beacon discovery
            //secondary(left, right)
            // connect all beacons
            //beaconL.connectGatt(context, true, leftCb)
            //beaconR.connectGatt(context, true, rightCb)
            responder.rssiAttach()
            async.complete(responder)
        }
        catch (x: Throwable) {
            primaryCb.close()
            async.completeExceptionally(x)
        }
    }

    suspend fun await(): BeaconLocator = async.await()

    init {
        initTask = scope.launch {
            primary(name)
            val primaryCb = BeaconPrimary(scope)
            primaryCb.onLost = { scope.cancel("device_lost") }
            primaryCb.onInit = { scope.launch { initSequence(primaryCb) } }
            data.connectGatt(context, true, primaryCb)
        }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var bleConnectBtn: Button
    private lateinit var bleDropBtn: Button
    private lateinit var bleName: EditText
    private var barr: BeaconLocator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bleConnectBtn = findViewById(R.id.ConnectBtn)
        bleDropBtn = findViewById(R.id.DisconnectBtn)
        bleName = findViewById(R.id.BLENameInput)
        bleDropBtn.isEnabled = false
    }

    private fun prepareBleScanner(): BluetoothLeScanner {
        // Initializes Bluetooth adapter.
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw MyConnectionError("no_service")
        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
        return adapter.bluetoothLeScanner ?: throw MyConnectionError("no_ble_service")
    }

    fun onBleConnectClick(v: View) {
        val context = this
        val name = bleName.text.toString()
        bleName.isEnabled = false
        bleConnectBtn.isEnabled = false
        bleDropBtn.isEnabled = true
        GlobalScope.launch {
            barr = BeaconTrackerInitializer(name, context, prepareBleScanner()).await()
        }
    }

    fun onBleDropClick(v: View) {
        barr?.close()
        bleName.isEnabled = true
        bleConnectBtn.isEnabled = true
        bleDropBtn.isEnabled = false
    }
}