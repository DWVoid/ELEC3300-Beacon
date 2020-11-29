package cn.dwvoid.beacon

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import io.github.controlwear.virtual.joystick.android.JoystickView
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val REQUEST_ENABLE_BT = 31128
private const val SCAN_PERIOD: Long = 5000

private class MyConnectionError(val reason: String) : Throwable()

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
    private var enabled = false
    var onCompute: (Double, Double, Double, Double, Double, Double)->Unit = { _, _, _, _, _, _ -> }

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
        l.onRssi = { if (enabled) lRssi.add(convert(it, lPower)) }
        r.onRssi = { if (enabled) rRssi.add(convert(it, rPower)) }
        c.onRssi = {
            if (enabled) {
                cRssi.add(convert(it, cPower))
                evaluate()
            }
        }
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()
        try {
            if ((now - lastClk) > 50) {
                lastClk = now
                val lR = lRssi.average()
                val cR = cRssi.average()
                val rR = rRssi.average()
                val (x, y) = compute(lR, cR, rR)
                onCompute(x, y, 0.0, lR, cR, rR)
            }
        }
        catch (x: Throwable) {
            close()
        }
    }

    private fun compute(RL: Double, RC: Double, RR: Double): Pair<Double, Double> {
        val a = 0.20
        val b = 0.306 / 2.0
        val RL2 = RL * RL
        val RC2 = RC * RC
        val RR2 = RR * RR
        val div = RC2 * RL2 + RC2 * RR2 + RL2 * RR2
        val x = (b * RC2 * (RL2 - RR2)) / div
        val y = -(a * RC2 * (RL2 + RR2)) / div
        return Pair(x, y)
    }

    fun enable() {
        this.enabled = true
        val ths = this
        scope.launch {
            while (ths.enabled){
                r.rssi()
                delay(2)
                l.rssi()
                delay(2)
                c.rssi()
                delay(2)
            }
        }
    }

    fun disable() {
        this.enabled = false
    }

    fun command(x: Double, y: Double, z: Double) = c.cmd(x, y, z)

    private fun convert(x: Int, power: Int) = 1.0 / (10.0.pow((x - power).toDouble() / 20.0))
}

@Suppress("ThrowableNotThrown")
class BeaconTrackerInitializer(
    name: String,
    private val context: Context,
    private val scanner: BluetoothLeScanner,
    private val dispatch: CoroutineDispatcher
) {

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
                -60, -60, -52,
                scope
            )
            // run initialization handshake
            cbCommand(primaryCb, "OK")
            val left = cbCommand(primaryCb, "GL")
            val right = cbCommand(primaryCb, "GR")
            // run secondary beacon discovery
            secondary(left, right)
            // connect all beacons
            beaconL.connectGatt(context, true, leftCb)
            beaconR.connectGatt(context, true, rightCb)
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
    private lateinit var toggleButton: Button
    private lateinit var bleDropBtn: Button
    private lateinit var bleName: EditText
    private lateinit var rocker: JoystickView
    private lateinit var rssiText: TextView
    private lateinit var coordText: TextView
    private var barr: BeaconLocator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bleConnectBtn = findViewById(R.id.ConnectBtn)
        toggleButton = findViewById(R.id.ToggleBtn)
        bleDropBtn = findViewById(R.id.DisconnectBtn)
        bleName = findViewById(R.id.BLENameInput)
        rocker = findViewById(R.id.Stick)
        rssiText = findViewById(R.id.rssi)
        coordText = findViewById(R.id.coord)
        bleDropBtn.isEnabled = false
        toggleButton.isVisible = false
        rocker.setOnMoveListener({ angle, strength ->
            val power = if (strength < 10) 0.0 else 0.8
            if (barr != null) {
                val a2 = (360 - ((angle + 270) % 360)) * Math.PI / 180.0
                val z = 0.5
                val x = sin(a2) * power
                val y = cos(a2) * power
                barr?.command(x, y, z)
            }
        }, 100)
    }

    override fun onDestroy() {
        super.onDestroy()
        barr?.close()
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

    private val dispatch = Handler(Looper.getMainLooper()).asCoroutineDispatcher()
    private val scope = CoroutineScope(Job() + dispatch)

    @SuppressLint("SetTextI18n")
    fun onBleConnectClick(v: View) {
        val context = this
        val name = bleName.text.toString()
        bleName.isEnabled = false
        bleConnectBtn.isEnabled = false
        bleDropBtn.isEnabled = true
        scope.launch {
            try {
                barr = BeaconTrackerInitializer(name, context, prepareBleScanner(), dispatch).await()
                barr?.onCompute = { x, y, _, rL, rC, rR ->
                    rssiText.text = "${"%6f".format(rL)}, ${"%6f".format(rC)}, ${"%6f".format(rR)}"
                    coordText.text = "${"%6f".format(x)}, ${"%6f".format(y)}"
                }
                isAuto = false
                rocker.isEnabled = true
                toggleButton.text = getString(R.string.toggle_to_auto)
                toggleButton.isVisible = true
            }
            catch (x: Throwable) {
                bleName.isEnabled = true
                bleConnectBtn.isEnabled = true
                bleDropBtn.isEnabled = false
                toggleButton.isVisible = false
                rocker.isEnabled = false
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(x.localizedMessage)
                    .setPositiveButton("OK") { dialogInterface, _ -> dialogInterface.cancel() }
                    .show()
            }
        }
    }

    private var isAuto = false

    fun onToggleBtnClick(v: View) {
        synchronized(isAuto) {
            if (!isAuto) {
                rocker.isEnabled = false
                barr?.enable()
                toggleButton.text = getString(R.string.toggle_to_manual)
            }
            else {
                rocker.isEnabled = true
                barr?.disable()
                toggleButton.text = getString(R.string.toggle_to_auto)
            }
            isAuto = !isAuto
        }
    }

    fun onBleDropClick(v: View) {
        barr?.close()
        bleName.isEnabled = true
        bleConnectBtn.isEnabled = true
        bleDropBtn.isEnabled = false
        toggleButton.isVisible = false
        rocker.isEnabled = false
    }
}