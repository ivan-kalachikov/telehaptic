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
import com.telehaptic.app.core.TickUnwrap
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

    companion object {
        /** Rule of thumb for a single-cell LiPo near empty, not measured on this capsule. */
        const val LOW_BATTERY_MV = 3500
    }

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
        /** Connected, but the data stopped (true) or came back (false). */
        fun onStall(stalled: Boolean) {}
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
    // One service discovery and one setup per GATT connection, whatever callbacks arrive.
    private var discoveryStarted = false
    private var servicesReady = false
    private var mtu = 23
    private var badPackets = 0
    private var lastStatusRequestMs = 0L
    private var autoConnectTried = false
    @Volatile private var notice = ""

    // Stream state (capsule thread only).
    val info = CapsuleInfo()
    private val sync = TimeSync()
    private val unwrap = TickUnwrap()
    private val stats = StreamStats()
    private var lastSample: Sample? = null
    private var pingToken = 1L
    private val pings = HashMap<Long, Long>()
    private val rtts = ArrayDeque<Double>()

    // GATT operations must run one at a time.
    private val ops = ArrayDeque<Op>()
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
        testSink?.let { it(cmd); return@post }
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
        handler.removeCallbacks(pingRunnable)
        discoveryStarted = false
        servicesReady = false
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
        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            handler.post {
                log.i("ble", "mtu $m status $st")
                if (st == BluetoothGatt.GATT_SUCCESS) mtu = m
                startDiscovery(g)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post { onServices(g, st) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            handler.post {
                if (g !== gatt) return@post   // late answer from a closed connection
                if (st != BluetoothGatt.GATT_SUCCESS) log.w("ble", "subscribe ${d.characteristic.uuid} status $st")
                opDone()
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (st != BluetoothGatt.GATT_SUCCESS) log.w("ble", "write status $st")
                opDone()
            }
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
            mtu = 23
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!g.requestMtu(247)) startDiscovery(g)
            // If the MTU answer never comes, discover anyway.
            handler.postDelayed({ if (g === gatt) startDiscovery(g) }, 2000)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            log.i("ble", "disconnected, status $st")
            val wasUp = status == Status.CONNECTED || status == Status.NO_DATA
            if (wasUp) listeners.forEach { it.onLink(false) }
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

    private fun restartLink(why: String) {
        log.w("ble", "reconnecting: $why")
        listeners.forEach { it.onLink(false) }
        closeGatt()
        setStatus(Status.RECONNECTING)
        val addr = prefs.capsuleAddress
        handler.postDelayed({ if (wantConnected && gatt == null && addr.isNotEmpty()) connectTo(addr, autoConnect = false) }, 1000)
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (g !== gatt || discoveryStarted) return
        discoveryStarted = true
        if (!g.discoverServices()) log.w("ble", "discoverServices refused")
    }

    private fun onServices(g: BluetoothGatt, st: Int) {
        if (g !== gatt || servicesReady) return
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
        unwrap.reset()
        pings.clear()
        for (c in listOf(dataChr, infoChr)) {   // INFO last: subscribing to it makes the capsule send its status
            if (c == null) continue
            enqueue {
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(UUID.fromString(Proto.CCCD)) ?: return@enqueue false
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            }
        }
        servicesReady = true
        badPackets = 0
        // The capsule fits its packets to the MTU, but its longer status lines need about 120 bytes.
        notice = if (mtu < 128) "The Bluetooth link uses small packets (MTU $mtu); some capsule status lines may be cut. Reconnect the capsule." else ""
        lastDataMs = SystemClock.elapsedRealtime()
        setStatus(Status.CONNECTED)
        listeners.forEach { it.onLink(true) }
        handler.removeCallbacks(pingRunnable)
        handler.postDelayed(pingRunnable, 1000)
    }

    // ------------------------------------------------------------ GATT op queue

    private class Op(val run: () -> Boolean, var tries: Int = 0)

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(Op(op))
        if (opBusySince == 0L) runNext()
    }

    /** Runs the next operation. One the stack refuses (busy) is tried again a little later, 3 times. */
    private fun runNext() {
        while (ops.isNotEmpty()) {
            val op = ops.first()
            val started = try { op.run() } catch (e: Exception) { log.w("ble", "op", e); false }
            if (started) { ops.removeFirst(); opBusySince = SystemClock.elapsedRealtime(); return }
            if (++op.tries < 3) {
                opBusySince = SystemClock.elapsedRealtime()   // nothing else starts meanwhile
                handler.postDelayed({ if (ops.firstOrNull() === op) { opBusySince = 0; runNext() } }, 300)
                return
            }
            log.w("ble", "GATT operation refused 3 times, skipped")
            ops.removeFirst()
        }
        opBusySince = 0
    }

    private fun opDone() {
        opBusySince = 0
        runNext()
    }

    // ------------------------------------------------------------ stream

    // Tests drive the stream without Bluetooth: a fake capsule receives the commands and sends notifications.
    @Volatile internal var testSink: ((ByteArray) -> Unit)? = null
    internal fun testNotify(uuid: String, value: ByteArray, rx: Long) = handler.post { onNotify(UUID.fromString(uuid), value, rx) }
    internal fun testLink(up: Boolean) = handler.post {
        lastDataMs = SystemClock.elapsedRealtime()
        setStatus(if (up) Status.CONNECTED else Status.RECONNECTING)
        listeners.forEach { it.onLink(up) }
    }

    private fun onNotify(uuid: UUID, value: ByteArray, rx: Long) {
        when (uuid.toString()) {
            Proto.DATA -> onData(value, rx)
            Proto.INFO -> onInfoLine(String(value, Charsets.UTF_8), rx)
        }
    }

    private fun onData(value: ByteArray, rx: Long) {
        val p = PacketParser.parse(value) ?: run {
            badPackets++
            if (badPackets == 1 || badPackets % 200 == 0) log.w("ble", "bad packet ${value.size} bytes (${badPackets} so far)")
            if (badPackets >= 20) notice = "The capsule sends data this app version cannot read. Install the firmware and the app from the same release."
            return
        }
        if (badPackets >= 20) { badPackets = 0; notice = "" }
        if (p.samples.isEmpty()) return
        lastDataMs = SystemClock.elapsedRealtime()
        if (status == Status.NO_DATA) { setStatus(Status.CONNECTED); listeners.forEach { it.onStall(false) } }
        // A lost CFG line would leave the sensor list out of date: ask for the status again.
        if (p.nMags != info.mags.size && SystemClock.elapsedRealtime() - lastStatusRequestMs > 2000) {
            lastStatusRequestMs = SystemClock.elapsedRealtime()
            send(Proto.cmdStatus(), "status (sensor list out of date)")
        }
        val last = p.samples.last()
        var ticks = LongArray(p.samples.size) { unwrap.unwrap(p.samples[it].ticks) }
        if (!stats.onPacket(p.firstIndex, p.samples.size, ticks.last())) {
            log.i("ble", "capsule restarted (index went back), clock sync reset")
            sync.reset()
            unwrap.reset()
            ticks = LongArray(p.samples.size) { unwrap.unwrap(p.samples[it].ticks) }
        }
        sync.add(rx, ticks.last())
        val t = LongArray(p.samples.size) { sync.toPhoneNs(ticks[it]) }
        lastSample = last
        listeners.forEach { it.onPacket(p, rx, t) }
    }

    private var lowBatterySaid = false

    private fun onInfoLine(line: String, rx: Long) {
        if (!line.startsWith("PONG")) log.i("capsule", line)
        val pong = info.apply(line)
        if (line.startsWith("BAT ")) {
            val mv = info.batteryMv
            if (mv in 1 until LOW_BATTERY_MV && !lowBatterySaid) { lowBatterySaid = true; App.get().cues.alert("Capsule battery low") }
            if (mv >= LOW_BATTERY_MV + 100) lowBatterySaid = false
        }
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
            if (status == Status.CONNECTED || status == Status.NO_DATA) listeners.forEach { it.onLink(false) }
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

    /** Called by the UI when it comes to the front (permissions may have changed). Connects once to a remembered capsule. */
    fun onUiResume() = handler.post {
        refreshStatus()
        if (!autoConnectTried && prefs.capsuleAddress.isNotEmpty() && status == Status.IDLE) {
            autoConnectTried = true
            connect()
        }
    }

    private fun setStatus(s: Status) {
        if (s != status) log.i("ble", "status $s")
        if (s == Status.CONNECTING || s == Status.RECONNECTING || s == Status.SEARCHING) { if (status != s) waitingSince = SystemClock.elapsedRealtime() }
        else waitingSince = 0
        status = s
        if (wantConnected) when (s) {
            Status.CONNECTED -> "Connected to the capsule"
            Status.NO_DATA -> "Connected, but no data from the capsule"
            Status.RECONNECTING -> "Capsule lost, reconnecting…"
            Status.CONNECTING -> "Connecting to the capsule…"
            Status.SEARCHING -> "Looking for the capsule…"
            Status.NOT_FOUND -> "Capsule not found"
            else -> null
        }?.let { CapsuleService.update(ctx, it) }
        publish()
    }

    private fun linkProblems(): List<String> {
        val out = ArrayList<String>()
        val mv = info.batteryMv
        val waiting = waitingSince > 0 && SystemClock.elapsedRealtime() - waitingSince > 20_000
        if (waiting && mv in 1 until LOW_BATTERY_MV) out.add("The capsule battery was low before the link was lost; it is probably empty. Charge the capsule.")
        else if (waiting) out.add("Still waiting for the capsule. Is it switched on and charged? Switching it off and on helps.")
        else if ((status == Status.CONNECTED || status == Status.NO_DATA) && mv in 1 until LOW_BATTERY_MV) out.add("Capsule battery low (${"%.2f".format(java.util.Locale.US, mv / 1000.0)} V). Charge it soon.")
        return out
    }

    /** Since when the app waits for the capsule (connecting, reconnecting, searching); 0 if not waiting. */
    private var waitingSince = 0L

    private fun tick() {
        // Detect a connection without data; refresh the UI 10 times a second.
        val quietMs = SystemClock.elapsedRealtime() - lastDataMs
        if (status == Status.CONNECTED && quietMs > 2500) {
            setStatus(Status.NO_DATA)
            listeners.forEach { it.onStall(true) }
        }
        // Connected without data for long (a subscription that did not take, a capsule that hung
        // and restarted while the link stayed): start the connection again.
        if (status == Status.NO_DATA && quietMs > 8000 && wantConnected) restartLink("no data for 8 s")
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
                gyrDps = DoubleArray(3) { s.gyr[it] * info.gyrMdpsPerLsb / 1000.0 },
                accG = DoubleArray(3) { s.acc[it] * info.accMgPerLsb / 1000.0 },
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
            problems = (if (notice.isNotEmpty()) listOf(notice) else emptyList()) + linkProblems() + info.problems(),
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
