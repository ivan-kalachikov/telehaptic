package com.telehaptic.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import com.telehaptic.app.core.CapsuleInfo
import com.telehaptic.app.core.MagInfo
import com.telehaptic.app.core.Packet
import com.telehaptic.app.core.PacketParser
import com.telehaptic.app.core.Proto
import com.telehaptic.app.core.Sample
import com.telehaptic.app.core.StreamStats
import com.telehaptic.app.core.TimeSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Everything about the capsule link. All work runs on one background thread. */
@SuppressLint("MissingPermission")  // every entry point checks hasPermissions() first
class Capsule(private val ctx: Context, private val log: AppLog, private val prefs: Prefs) {

    enum class Status { NO_PERMISSION, BT_OFF, IDLE, SEARCHING, NOT_FOUND, CONNECTING, CONNECTED, NO_DATA, RECONNECTING }

    data class Live(
        val magUt: List<DoubleArray> = emptyList(),   // per magnetometer x, y, z in uT
        val sat: List<Boolean> = emptyList(),         // any axis saturated, per magnetometer
        val gyrDps: DoubleArray = DoubleArray(3),
        val accG: DoubleArray = DoubleArray(3),
        val motor: Boolean = false,
    )

    data class Ui(
        val status: Status = Status.IDLE,
        val firmware: String = "",
        val bootReason: String = "",
        val batteryMv: Int = 0,
        val mags: List<MagInfo> = emptyList(),
        val imu: Boolean = false,
        val scan: List<String> = emptyList(),
        val problems: List<String> = emptyList(),
        val rateHz: Int = 0,
        val rangeG: Int = 0,
        val osr: String = "",
        val measuredHz: Double = 0.0,
        val recentLoss: Double = 0.0,
        val lost: Long = 0,
        val received: Long = 0,
        val restarts: Int = 0,
        val rttMs: Double = 0.0,
        val syncResidualMs: Double = 0.0,
        val driftPpm: Double = 0.0,
        val stats: String = "",
        val live: Live = Live(),
    )

    /** Receives the stream on the capsule thread. */
    interface Listener {
        fun onPacket(p: Packet, rxNs: Long, tNs: LongArray) {}
        fun onInfo(rxNs: Long, line: String) {}
        fun onPong(sentNs: Long, rxNs: Long, token: Long, ticks: Long) {}
        fun onLink(connected: Boolean) {}
    }

    private val thread = HandlerThread("capsule").apply { start() }
    val handler = Handler(thread.looper)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val btManager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private var wantConnected = false
    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private var status = Status.IDLE
    private var lastDataMs = 0L
    private var scanning = false

    // Stream state (capsule thread only).
    val info = CapsuleInfo()
    private val sync = TimeSync()
    private val stats = StreamStats()
    private var lastSample: Sample? = null
    private var pingToken = 1L
    private val pings = HashMap<Long, Long>()
    private val rtts = ArrayDeque<Double>()

    // GATT operations must run one at a time.
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusySince = 0L

    init {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                handler.post { onAdapterState() }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        handler.post { refreshStatus(); tick() }
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun hasPermissions(): Boolean =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    // ------------------------------------------------------------ public actions

    /** Connects to the remembered capsule, or searches for the nearest one. */
    fun connect() = handler.post {
        if (!hasPermissions()) { setStatus(Status.NO_PERMISSION); return@post }
        if (adapter?.isEnabled != true) { setStatus(Status.BT_OFF); return@post }
        wantConnected = true
        CapsuleService.start(ctx)
        val addr = prefs.capsuleAddress
        if (addr.isNotEmpty()) connectTo(addr, autoConnect = false) else scan()
    }

    fun disconnect() = handler.post {
        wantConnected = false
        closeGatt()
        setStatus(Status.IDLE)
        CapsuleService.stop(ctx)
    }

    /** Forgets the remembered capsule; the next connect searches again. */
    fun forget() = handler.post {
        prefs.capsuleAddress = ""
        wantConnected = false
        closeGatt()
        setStatus(Status.IDLE)
        CapsuleService.stop(ctx)
    }

    fun send(cmd: ByteArray, what: String) = handler.post {
        val g = gatt ?: return@post
        val c = ctrl ?: return@post
        log.i("cmd", what)
        enqueue { g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS }
    }

    fun motor(ms: Int) = send(Proto.cmdMotor(ms), "motor $ms ms")
    fun setRate(hz: Int) = send(Proto.cmdRate(hz), "rate $hz")
    fun setRange(g: Int) = send(Proto.cmdRange(g), "range $g")
    fun setOsr(a: Int, b: Int) = send(Proto.cmdOsr(a, b), "osr $a/$b")
    fun rescan() = send(Proto.cmdRescan(), "rescan")

    /** Capsule time (ticks) to phone time (ns), for listeners on the capsule thread. */
    fun phoneNs(ticks: Long) = sync.toPhoneNs(ticks)
    fun syncSnapshot() = Triple(sync.ready, sync.residualNs, sync.driftPpm)
    fun streamSnapshot() = Triple(stats.received, stats.lost, stats.restarts)

    // ------------------------------------------------------------ scan / connect

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { setStatus(Status.BT_OFF); return }
        setStatus(Status.SEARCHING)
        var best: ScanResult? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                handler.post { if (best == null || r.rssi > best!!.rssi) best = r }
            }
            override fun onScanFailed(code: Int) {
                log.w("ble", "scan failed $code")
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(Proto.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(listOf(filter), settings, cb)
        log.i("ble", "scan started")
        handler.postDelayed({
            scanning = false
            try { scanner.stopScan(cb) } catch (e: Exception) { log.w("ble", "stopScan", e) }
            val r = best
            if (r == null) {
                log.i("ble", "scan: nothing found")
                wantConnected = false
                setStatus(Status.NOT_FOUND)
                CapsuleService.stop(ctx)
            } else {
                log.i("ble", "scan: ${r.device.address} rssi ${r.rssi}")
                prefs.capsuleAddress = r.device.address
                if (wantConnected) connectTo(r.device.address, autoConnect = false)
            }
        }, 4000)
    }

    private fun connectTo(address: String, autoConnect: Boolean) {
        val dev: BluetoothDevice = try {
            adapter?.getRemoteDevice(address) ?: return
        } catch (e: IllegalArgumentException) {
            prefs.capsuleAddress = ""; scan(); return
        }
        closeGatt()
        setStatus(if (status == Status.RECONNECTING) Status.RECONNECTING else Status.CONNECTING)
        log.i("ble", "connect $address auto=$autoConnect")
        gatt = dev.connectGatt(ctx, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        if (!autoConnect) {
            // A direct connect gives up after ~30 s by itself; if it is still pending after 12 s,
            // switch to background auto-connect, which waits until the capsule appears.
            val g = gatt
            handler.postDelayed({
                if (wantConnected && gatt === g && status != Status.CONNECTED && status != Status.NO_DATA) {
                    log.i("ble", "direct connect slow, switching to auto-connect")
                    connectTo(address, autoConnect = true)
                }
            }, 12000)
        }
    }

    private fun closeGatt() {
        ops.clear()
        opBusySince = 0
        gatt?.let { g ->
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        gatt = null
        ctrl = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            handler.post { onConnectionState(g, st, newState) }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            handler.post { log.i("ble", "mtu $mtu status $st"); g.discoverServices() }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post { onServices(g, st) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            handler.post { opDone() }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            handler.post { if (st != BluetoothGatt.GATT_SUCCESS) log.w("ble", "write status $st"); opDone() }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val rx = SystemClock.elapsedRealtimeNanos()
            val uuid = c.uuid
            handler.post { onNotify(uuid, value, rx) }
        }
    }

    private fun onConnectionState(g: BluetoothGatt, st: Int, newState: Int) {
        if (g !== gatt) return
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            log.i("ble", "connected, status $st")
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.requestMtu(247)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            log.i("ble", "disconnected, status $st")
            val wasUp = status == Status.CONNECTED || status == Status.NO_DATA
            listeners.forEach { it.onLink(false) }
            closeGatt()
            if (wantConnected) {
                setStatus(Status.RECONNECTING)
                val addr = prefs.capsuleAddress
                handler.postDelayed({ if (wantConnected && gatt == null) connectTo(addr, autoConnect = wasUp || st != 0) }, 1500)
            } else {
                setStatus(Status.IDLE)
            }
        }
    }

    private fun onServices(g: BluetoothGatt, st: Int) {
        val svc = g.getService(UUID.fromString(Proto.SERVICE))
        if (st != BluetoothGatt.GATT_SUCCESS || svc == null) {
            log.w("ble", "service not found, status $st")
            g.disconnect()
            return
        }
        ctrl = svc.getCharacteristic(UUID.fromString(Proto.CTRL))
        val infoChr = svc.getCharacteristic(UUID.fromString(Proto.INFO))
        val dataChr = svc.getCharacteristic(UUID.fromString(Proto.DATA))
        stats.newConnection()
        sync.reset()
        pings.clear()
        for (c in listOf(dataChr, infoChr)) {   // INFO last: subscribing to it makes the capsule send its status
            if (c == null) continue
            enqueue {
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(UUID.fromString(Proto.CCCD)) ?: return@enqueue false
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            }
        }
        lastDataMs = SystemClock.elapsedRealtime()
        setStatus(Status.CONNECTED)
        listeners.forEach { it.onLink(true) }
        handler.postDelayed(pingRunnable, 1000)
    }

    // ------------------------------------------------------------ GATT op queue

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (opBusySince == 0L) runNext()
    }

    private fun runNext() {
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            val started = try { op() } catch (e: Exception) { log.w("ble", "op", e); false }
            if (started) { opBusySince = SystemClock.elapsedRealtime(); return }
        }
        opBusySince = 0
    }

    private fun opDone() {
        opBusySince = 0
        runNext()
    }

    // ------------------------------------------------------------ stream

    private fun onNotify(uuid: UUID, value: ByteArray, rx: Long) {
        when (uuid.toString()) {
            Proto.DATA -> onData(value, rx)
            Proto.INFO -> onInfoLine(String(value, Charsets.UTF_8), rx)
        }
    }

    private fun onData(value: ByteArray, rx: Long) {
        val p = PacketParser.parse(value) ?: run { log.w("ble", "bad packet ${value.size} bytes"); return }
        if (p.samples.isEmpty()) return
        lastDataMs = SystemClock.elapsedRealtime()
        if (status == Status.NO_DATA) setStatus(Status.CONNECTED)
        val last = p.samples.last()
        if (!stats.onPacket(p.firstIndex, p.samples.size, last.ticks)) {
            log.i("ble", "capsule restarted (index went back), clock sync reset")
            sync.reset()
        }
        sync.add(rx, last.ticks)
        val t = LongArray(p.samples.size) { sync.toPhoneNs(p.samples[it].ticks) }
        lastSample = last
        listeners.forEach { it.onPacket(p, rx, t) }
    }

    private fun onInfoLine(line: String, rx: Long) {
        log.i("capsule", line)
        val pong = info.apply(line)
        if (pong != null) {
            val sent = pings.remove(pong.first)
            if (sent != null) {
                val rtt = (rx - sent) / 1e6
                rtts.addLast(rtt)
                while (rtts.size > 30) rtts.removeFirst()
                listeners.forEach { it.onPong(sent, rx, pong.first, pong.second) }
            }
        }
        listeners.forEach { it.onInfo(rx, line) }
        publish()
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (status != Status.CONNECTED && status != Status.NO_DATA) return
            val token = pingToken++
            pings[token] = SystemClock.elapsedRealtimeNanos()
            if (pings.size > 50) pings.keys.minOrNull()?.let { pings.remove(it) }
            val g = gatt; val c = ctrl
            if (g != null && c != null) {
                enqueue {
                    pings[token] = SystemClock.elapsedRealtimeNanos()  // as close to the write as possible
                    g.writeCharacteristic(c, Proto.cmdPing(token), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------ status

    private fun onAdapterState() {
        if (adapter?.isEnabled != true) {
            closeGatt()
            setStatus(Status.BT_OFF)
        } else if (status == Status.BT_OFF) {
            setStatus(Status.IDLE)
            if (wantConnected || prefs.capsuleAddress.isNotEmpty()) connect()
        }
    }

    private fun refreshStatus() {
        when {
            !hasPermissions() -> setStatus(Status.NO_PERMISSION)
            adapter?.isEnabled != true -> setStatus(Status.BT_OFF)
            status == Status.NO_PERMISSION || status == Status.BT_OFF -> setStatus(Status.IDLE)
        }
    }

    /** Called by the UI when it comes to the front (permissions may have changed). */
    fun onUiResume() = handler.post { refreshStatus() }

    private fun setStatus(s: Status) {
        if (s != status) log.i("ble", "status $s")
        status = s
        publish()
    }

    private fun tick() {
        // Detect a connection without data; refresh the UI 10 times a second.
        if (status == Status.CONNECTED && SystemClock.elapsedRealtime() - lastDataMs > 2500) setStatus(Status.NO_DATA)
        if (opBusySince != 0L && SystemClock.elapsedRealtime() - opBusySince > 3000) {
            log.w("ble", "GATT operation timed out")
            opDone()
        }
        publish()
        handler.postDelayed({ tick() }, 100)
    }

    /** For screenshots only: shows fixed state and stops live updates. */
    @Volatile private var frozen = false
    fun showForPreview(u: Ui) { frozen = true; _ui.value = u }

    private fun publish() {
        if (frozen) return
        val s = lastSample
        val mags = info.mags
        val live = if (s != null && s.nMags == mags.size) {
            Live(
                magUt = mags.indices.map { i -> DoubleArray(3) { a -> mags[i].uT(s.mag[3 * i + a]) } },
                sat = mags.indices.map { i -> (0..2).any { a -> s.flags and Proto.satBit(i, a) != 0 } },
                gyrDps = DoubleArray(3) { s.gyr[it] * Proto.GYR_MDPS_PER_LSB / 1000.0 },
                accG = DoubleArray(3) { s.acc[it] * Proto.ACC_MG_PER_LSB / 1000.0 },
                motor = s.motor(),
            )
        } else Live()
        val sorted = rtts.sorted()
        _ui.value = Ui(
            status = status,
            firmware = info.firmware,
            bootReason = info.bootReason,
            batteryMv = info.batteryMv,
            mags = mags,
            imu = info.imu,
            scan = info.scan.toList(),
            problems = info.problems(),
            rateHz = info.rateHz,
            rangeG = info.rangeG,
            osr = info.osr,
            measuredHz = stats.rateHz(),
            recentLoss = stats.recentLoss(),
            lost = stats.lost,
            received = stats.received,
            restarts = stats.restarts,
            rttMs = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2],
            syncResidualMs = sync.residualNs / 1e6,
            driftPpm = sync.driftPpm,
            stats = info.stats,
            live = live,
        )
    }
}
