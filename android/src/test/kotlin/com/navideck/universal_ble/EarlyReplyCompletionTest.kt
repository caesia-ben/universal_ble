package com.navideck.universal_ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito

/*
 * GATT callbacks arrive on Bluetooth binder threads (connectGatt registers no
 * Handler), so a reply can be delivered before readCharacteristic,
 * discoverServices or readRemoteRssi has returned. The pending future must
 * already be registered at that point, or the reply is matched against an
 * empty list, dropped, and the Dart call only fails at its timeout.
 *
 * These tests fire the reply from inside the native call, which is the
 * ordering a fast peripheral (or a cached service table) produces.
 */
internal class EarlyReplyCompletionTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val serviceUuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val posted = ConcurrentLinkedQueue<Runnable>()
    private val plugin = UniversalBlePlugin().also {
        val handler = Mockito.mock(Handler::class.java)
        Mockito.doAnswer { call ->
            posted.add(call.getArgument<Runnable>(0))
            true
        }.`when`(handler).post(Mockito.any())
        setField(it, "mainThreadHandler", handler)
        // onServicesDiscovered persists the discovered service UUIDs.
        setField(it, "context", Mockito.mock(Context::class.java, Mockito.RETURNS_DEEP_STUBS))
    }

    private val service = Mockito.mock(BluetoothGattService::class.java).also {
        Mockito.`when`(it.uuid).thenReturn(serviceUuid)
    }
    private val characteristic = Mockito.mock(BluetoothGattCharacteristic::class.java).also {
        Mockito.`when`(it.uuid).thenReturn(charUuid)
        Mockito.`when`(it.service).thenReturn(service)
        Mockito.`when`(it.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_READ)
        Mockito.`when`(it.descriptors).thenReturn(emptyList())
        Mockito.`when`(service.getCharacteristic(charUuid)).thenReturn(it)
        Mockito.`when`(service.characteristics).thenReturn(listOf(it))
    }

    private val gatt: BluetoothGatt = run {
        val device = Mockito.mock(BluetoothDevice::class.java)
        Mockito.`when`(device.address).thenReturn(address)
        Mockito.mock(BluetoothGatt::class.java).also {
            Mockito.`when`(it.device).thenReturn(device)
            Mockito.`when`(it.getService(serviceUuid)).thenReturn(service)
            Mockito.`when`(it.services).thenReturn(listOf(service))
            it.saveCacheIfNeeded()
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = UniversalBlePlugin::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun drainMain() {
        while (true) (posted.poll() ?: return).run()
    }

    @AfterTest
    fun clearConnections() {
        connectedGatts().forEach { it.removeCache() }
    }

    private fun read(results: MutableList<Result<ByteArray>>) =
        plugin.readValue(address, serviceUuid.toString(), charUuid.toString()) { results.add(it) }

    @Test
    fun readReplyBeforeReadCharacteristicReturnsIsDelivered() {
        Mockito.doAnswer {
            plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(1, 2), BluetoothGatt.GATT_SUCCESS)
            true
        }.`when`(gatt).readCharacteristic(characteristic)
        val results = mutableListOf<Result<ByteArray>>()
        read(results)
        drainMain()
        assertEquals(listOf<Byte>(1, 2), results.single().getOrThrow().toList())
    }

    @Test
    fun discoveryReplyBeforeDiscoverServicesReturnsIsDelivered() {
        Mockito.doAnswer {
            plugin.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            true
        }.`when`(gatt).discoverServices()
        val results = mutableListOf<Result<List<UniversalBleService>>>()
        plugin.discoverServices(address, false) { results.add(it) }
        drainMain()
        val services = results.single().getOrThrow()
        assertEquals(serviceUuid.toString(), services.single().uuid)
        assertEquals(charUuid.toString(), services.single().characteristics!!.single().uuid)
    }

    @Test
    fun rssiReplyBeforeReadRemoteRssiReturnsIsDelivered() {
        Mockito.doAnswer {
            plugin.onReadRemoteRssi(gatt, -42, BluetoothGatt.GATT_SUCCESS)
            true
        }.`when`(gatt).readRemoteRssi()
        val results = mutableListOf<Result<Long>>()
        plugin.readRssi(address) { results.add(it) }
        drainMain()
        assertEquals(-42L, results.single().getOrThrow())
    }

    @Test
    fun binderRepliesAreDeliveredOnMainThreadExactlyOnce() {
        Mockito.`when`(gatt.readCharacteristic(characteristic)).thenReturn(true)
        Mockito.`when`(gatt.discoverServices()).thenReturn(true)
        Mockito.`when`(gatt.readRemoteRssi()).thenReturn(true)
        val reads = mutableListOf<Result<ByteArray>>()
        val discoveries = mutableListOf<Result<List<UniversalBleService>>>()
        val rssis = mutableListOf<Result<Long>>()
        read(reads)
        plugin.discoverServices(address, false) { discoveries.add(it) }
        plugin.readRssi(address) { rssis.add(it) }
        Thread {
            plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(7), BluetoothGatt.GATT_SUCCESS)
            plugin.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            plugin.onReadRemoteRssi(gatt, -60, BluetoothGatt.GATT_SUCCESS)
        }.apply { start(); join() }
        assertTrue(reads.isEmpty() && discoveries.isEmpty() && rssis.isEmpty(), "replies must be posted to the main looper")
        drainMain()
        assertEquals(1, reads.size)
        assertEquals(1, discoveries.size)
        assertEquals(1, rssis.size)
        // A duplicate callback finds nothing left to complete.
        plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(8), BluetoothGatt.GATT_SUCCESS)
        plugin.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        plugin.onReadRemoteRssi(gatt, -61, BluetoothGatt.GATT_SUCCESS)
        drainMain()
        assertEquals(listOf(1, 1, 1), listOf(reads.size, discoveries.size, rssis.size))
    }

    @Test
    fun rejectedNativeRequestsFailImmediatelyAndLeaveNoWaiter() {
        Mockito.`when`(gatt.readCharacteristic(characteristic)).thenReturn(false)
        Mockito.`when`(gatt.discoverServices()).thenReturn(false)
        Mockito.`when`(gatt.readRemoteRssi()).thenReturn(false)
        val reads = mutableListOf<Result<ByteArray>>()
        val discoveries = mutableListOf<Result<List<UniversalBleService>>>()
        val rssis = mutableListOf<Result<Long>>()
        read(reads)
        plugin.discoverServices(address, false) { discoveries.add(it) }
        plugin.readRssi(address) { rssis.add(it) }
        assertTrue(reads.single().isFailure)
        assertTrue(discoveries.single().isFailure)
        assertTrue(rssis.single().isFailure)
        // A stray reply must not complete the already-failed requests again.
        plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(1), BluetoothGatt.GATT_SUCCESS)
        plugin.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        plugin.onReadRemoteRssi(gatt, -42, BluetoothGatt.GATT_SUCCESS)
        drainMain()
        assertEquals(listOf(1, 1, 1), listOf(reads.size, discoveries.size, rssis.size))
    }

    @Test
    fun throwingNativeRequestLeavesNoWaiter() {
        Mockito.`when`(gatt.readCharacteristic(characteristic)).thenThrow(SecurityException("no permission"))
        val reads = mutableListOf<Result<ByteArray>>()
        read(reads)
        assertTrue(reads.single().isFailure)
        plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(1), BluetoothGatt.GATT_SUCCESS)
        drainMain()
        assertEquals(1, reads.size)
    }

    @Test
    fun replyCompletesOnlyTheOldestPendingReadOfACharacteristic() {
        Mockito.`when`(gatt.readCharacteristic(characteristic)).thenReturn(true)
        val first = mutableListOf<Result<ByteArray>>()
        val second = mutableListOf<Result<ByteArray>>()
        read(first)
        read(second)
        plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(1), BluetoothGatt.GATT_SUCCESS)
        drainMain()
        assertEquals(listOf<Byte>(1), first.single().getOrThrow().toList())
        assertTrue(second.isEmpty())
        plugin.onCharacteristicRead(gatt, characteristic, byteArrayOf(2), BluetoothGatt.GATT_SUCCESS)
        drainMain()
        assertEquals(listOf<Byte>(2), second.single().getOrThrow().toList())
    }

    @Test
    fun disconnectFailsPendingReadsDiscoveriesAndRssiReads() {
        Mockito.`when`(gatt.readCharacteristic(characteristic)).thenReturn(true)
        Mockito.`when`(gatt.discoverServices()).thenReturn(true)
        Mockito.`when`(gatt.readRemoteRssi()).thenReturn(true)
        val reads = mutableListOf<Result<ByteArray>>()
        val discoveries = mutableListOf<Result<List<UniversalBleService>>>()
        val rssis = mutableListOf<Result<Long>>()
        read(reads)
        plugin.discoverServices(address, false) { discoveries.add(it) }
        plugin.readRssi(address) { rssis.add(it) }
        plugin.disconnect(address)
        drainMain()
        assertTrue(reads.single().isFailure)
        assertTrue(discoveries.single().isFailure)
        assertTrue(rssis.single().isFailure)
    }
}
