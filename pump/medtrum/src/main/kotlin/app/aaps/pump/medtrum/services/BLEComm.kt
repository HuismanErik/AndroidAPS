package app.aaps.pump.medtrum.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.pump.medtrum.comm.ManufacturerData
import app.aaps.pump.medtrum.comm.ReadDataPacket
import app.aaps.pump.medtrum.comm.WriteCommandPackets
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface BLECommCallback {

    fun onBLEConnected()
    fun onBLEDisconnected()
    fun onNotification(notification: ByteArray)
    fun onIndication(indication: ByteArray)
    fun onSendMessageError(reason: String, isRetryAble: Boolean)
}

@Singleton
class BLEComm @Inject internal constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val preferences: Preferences
) {

    companion object {

        private const val WRITE_DELAY_MILLIS: Long = 10
        private const val WRITE_TIMEOUT_MILLIS = 5000L

        private const val MAX_CONNECT_ATTEMPTS: Int = 5

        private const val SERVICE_UUID = "669A9001-0008-968F-E311-6050405558B3"
        private const val READ_UUID = "669a9120-0008-968f-e311-6050405558b3"
        private const val WRITE_UUID = "669a9101-0008-968f-e311-6050405558b3"
        private const val CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        private const val NEEDS_ENABLE_NOTIFICATION = 0x10
        private const val NEEDS_ENABLE_INDICATION = 0x20
        private const val NEEDS_ENABLE = 0x30

        private const val MANUFACTURER_ID = 18305
    }

    private val reconnectCooldownMillis: Long = 5000
    private var connectAttempts: Int = 0
    private var reconnectBlockedUntil: Long = 0

    private val handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    private val mBluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    private var mBluetoothGatt: BluetoothGatt? = null
    private var isConnected = false
    private var isConnecting = false
    private var uartWrite: BluetoothGattCharacteristic? = null
    private var uartRead: BluetoothGattCharacteristic? = null

    // Read and write buffers
    private var mWritePackets: WriteCommandPackets? = null
    private var mWriteSequenceNumber: Int = 0
    private var mReadPacket: ReadDataPacket? = null

    private var mDeviceSN: Long = 0
    private var mDeviceAddress: String? = null
    private var mCallback: BLECommCallback? = null
    private var writeTimeoutRunnable: Runnable? = null
    private var lastSeenTimestamp: Long = 0

    fun setCallback(callback: BLECommCallback?) {
        handler.post { mCallback = callback }
    }

    /** Connect flow: 1. Start scanning for our device (SN entered in settings) */
    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permissions")
            return false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Start scan!!")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Find our Medtrum Device!
        val filters = listOf(
            ScanFilter.Builder().setDeviceName("MT").build()
        )
        mBluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, mScanCallback)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mBluetoothAdapter?.bluetoothLeScanner?.stopScan(mScanCallback)
    }

    fun connect(from: String, deviceSN: Long): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return false
        }

        stopScan()
        aapsLogger.debug(LTag.PUMPBTCOMM, "Initializing BLEComm.")
        if (mBluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.")
            return false
        }
        handler.post {
            val now = System.currentTimeMillis()
            if (now < reconnectBlockedUntil) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "connect ignored: cooldown active")
                return@post
            }

            val recentlySeen = System.currentTimeMillis() - lastSeenTimestamp < 12000
            isConnected = false
            isConnecting = true
            mWritePackets = null
            mReadPacket = null

            // When recentlySeen and mDeviceAddress is known, warmup BLE radio and connect with known device
            if (mDeviceAddress != null && mDeviceSN == deviceSN && recentlySeen) {
                // Skip scanning and directly connect to gatt
                aapsLogger.debug(LTag.PUMPBTCOMM, "Skipping scan and directly connecting to gatt")
                mBluetoothAdapter?.getRemoteDevice(mDeviceAddress)?.let {
                    warmUpRadioThenConnect(it)
                }
            } else {
                // Scan for device
                aapsLogger.debug(LTag.PUMPBTCOMM, "Scanning for device")
                mDeviceAddress = null
                mDeviceSN = deviceSN
                startScan()
            }
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun warmUpRadioThenConnect(device: BluetoothDevice) {
        val scanner = mBluetoothAdapter?.bluetoothLeScanner ?: return

        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build(), dummyScanCallback)

        handler.postDelayed({
                                scanner.stopScan(dummyScanCallback)
                                connectGattInternal(device)
                            }, 400)
    }

    private val dummyScanCallback = object : ScanCallback() {}

    @SuppressLint("MissingPermission")
    private fun connectGattInternal(device: BluetoothDevice) {
        stopScan()
        // Reset sequence counter
        mWriteSequenceNumber = 0
        if (mBluetoothGatt == null) {
            mBluetoothGatt = device.connectGatt(
                context,
                false,
                mGattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            // Already connected?, this should not happen force disconnect
            aapsLogger.error(LTag.PUMPBTCOMM, "connectGatt while GATT not null")
            closeInternal()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(from: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
        handler.post {
            aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
            isConnecting = false

            val gatt = mBluetoothGatt
            if (gatt != null) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Requesting disconnect...")
                gatt.disconnect()
                handler.postDelayed({
                                        if (mBluetoothGatt != null) {
                                            aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnect watchdog – forcing close")
                                            closeInternal()
                                        }
                                    }, 3000)
            } else {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Gatt was null, cleaning up")
                isConnected = false
                mCallback?.onBLEDisconnected()
                closeInternal()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeInternal() {
        val gatt = mBluetoothGatt ?: return

        aapsLogger.debug(LTag.PUMPBTCOMM, "Closing GATT instance now")

        mBluetoothGatt = null
        uartRead = null
        uartWrite = null
        isConnected = false
        isConnecting = false

        try {
            gatt.close()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Error closing GATT: ${e.message}")
        }
    }

    /** Scan callback  */
    private val mScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                lastSeenTimestamp = System.currentTimeMillis()
                aapsLogger.debug(LTag.PUMPBTCOMM, "OnScanResult! $result")
                super.onScanResult(callbackType, result)
                stopScan()

                val manufacturerData =
                    result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                        ?.let { ManufacturerData(it) }

                aapsLogger.debug(LTag.PUMPBTCOMM, "Found deviceSN: " + manufacturerData?.getDeviceSN())

                if (manufacturerData?.getDeviceSN() == mDeviceSN) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Found our device! deviceSN: " + manufacturerData.getDeviceSN())
                    handler.postDelayed({
                                            mDeviceAddress = result.device.address
                                            connectGattInternal(result.device)
                                        }, 1000)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Scan FAILED!")
        }
    }

    private val mGattCallback = object : BluetoothGattCallback() {

        /** Connect flow: 3. When we are connected discover services*/
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
                aapsLogger.error(LTag.PUMPBTCOMM, "missing permissions")
                return
            }

            handler.post {
                aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange newState: $newState status: $status")

                if (status == 133) {
                    handleGattFailure("status 133")
                    return@post
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnecting = false
                    aapsLogger.debug(LTag.PUMPBTCOMM, "STATE_CONNECTED - starting discoverServices")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (isConnecting) {
                        val resetDevice = preferences.get(MedtrumBooleanKey.MedtrumScanOnConnectionErrors)
                        if (resetDevice) {
                            // When we are disconnected during connecting, we reset the device address to force a new scan
                            aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnected while connecting! Reset device address")
                            mDeviceAddress = null
                        }
                    }
                    handler.postDelayed({
                                            isConnecting = false
                                            isConnected = false
                                            mCallback?.onBLEDisconnected()
                                            aapsLogger.debug(LTag.PUMPBTCOMM, "STATE_DISCONNECTED - cleaning up")
                                            closeInternal()
                                        }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered status: $status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Services discovered successfully - finding characteristics")
                    findCharacteristic()
                    isConnected = true
                } else {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Service discovery failed with status: $status")
                    handleGattFailure("Service discovery failed: $status")
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicRead data: " + characteristic.value.contentToString() + " UUID: " + characteristic.uuid.toString() + " status: " + status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicChanged data: " + characteristic.value.contentToString() + " UUID: " + characteristic.uuid.toString())

            val value = characteristic.value.copyOf() // Create a copy of data
            val uuid = characteristic.uuid
            handler.post {
                aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicChanged UUID: $uuid")
                if (uuid == UUID.fromString(READ_UUID)) {
                    mCallback?.onNotification(value)
                } else if (uuid == UUID.fromString(WRITE_UUID)) {
                    if (mReadPacket == null) {
                        mReadPacket = ReadDataPacket(value)
                    } else {
                        mReadPacket?.addData(value)
                    }
                    if (mReadPacket?.allDataReceived() == true) {
                        if (mReadPacket?.failed() == true) {
                            mCallback?.onSendMessageError("ReadDataPacket failed", false)
                        } else {
                            mReadPacket?.getData()?.let { mCallback?.onIndication(it) }
                        }
                        mReadPacket = null
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicWrite status = $status")
                writeTimeoutRunnable?.let { handler.removeCallbacks(it) }
                writeTimeoutRunnable = null

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Check if we need to finish our command!
                    mWritePackets?.getNextPacket()?.let { pkt ->
                        writeCharacteristic(uartWriteBTGattChar, pkt)
                    }
                } else {
                    mCallback?.onSendMessageError("onCharacteristicWrite failure", true)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorWrite status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDescriptor(descriptor)
            } else {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Descriptor write failed: $status")
            }
        }

        /** Connect flow: 5. Notifications enabled read descriptor to verify and start auth process*/
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorRead status: $status, value: ${descriptor.value?.contentToString()}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                checkDescriptor(descriptor)
            } else {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Descriptor read failed: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDescriptor(descriptor: BluetoothGattDescriptor?) {
        handler.post {
            aapsLogger.debug(LTag.PUMPBTCOMM, "readDescriptor")
            if (mBluetoothAdapter == null || mBluetoothGatt == null || descriptor == null) {
                handleNotInitialized()
                return@post
            }
            mBluetoothGatt?.readDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun checkDescriptor(descriptor: BluetoothGattDescriptor) {
        handler.post {
            aapsLogger.debug(LTag.PUMPBTCOMM, "checkDescriptor value: ${descriptor.value?.contentToString()}")
            val service = getGattService()
            if (mBluetoothAdapter == null || mBluetoothGatt == null || service == null) {
                handleNotInitialized()
                return@post
            }
            if (descriptor.value.toInt() > 0) {
                var notificationEnabled = true
                val characteristics = service.characteristics
                for (j in 0 until characteristics.size) {
                    val configDescriptor =
                        characteristics[j].getDescriptor(UUID.fromString(CONFIG_UUID))
                    if (configDescriptor.value == null || configDescriptor.value.toInt() <= 0) {
                        notificationEnabled = false
                    }
                }
                if (notificationEnabled) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Notifications enabled! Calling onBLEConnected")
                    connectAttempts = 0
                    /** Connect flow: 6. Connected */
                    mCallback?.onBLEConnected()

                } else {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Notifications NOT fully enabled")
                }

            }
        }
    }

    private val uartWriteBTGattChar: BluetoothGattCharacteristic
        get() = uartWrite
            ?: BluetoothGattCharacteristic(
                UUID.fromString(WRITE_UUID),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                0
            ).also { uartWrite = it }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        handler.post {
            aapsLogger.debug(LTag.PUMPBTCOMM, "setCharacteristicNotification")
            if (mBluetoothAdapter == null || mBluetoothGatt == null) {
                handleNotInitialized()
                return@post
            }
            mBluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
            characteristic?.getDescriptor(UUID.fromString(CONFIG_UUID))?.let {
                if (characteristic.properties and NEEDS_ENABLE_NOTIFICATION > 0) {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    mBluetoothGatt?.writeDescriptor(it)
                } else if (characteristic.properties and NEEDS_ENABLE_INDICATION > 0) {
                    it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    mBluetoothGatt?.writeDescriptor(it)
                } else {
                    // Do nothing
                }
            }
        }
    }

    private fun handleGattFailure(reason: String) {
        handler.post {
            aapsLogger.error(LTag.PUMPBTCOMM, "GATT failure: $reason")

            isConnecting = false
            isConnected = false
            connectAttempts++

            disconnect("Calling disconnect GATT failure: $reason")
            // after closing wait at leat 1 second before continueing
            handler.postDelayed({
                                    if (connectAttempts <= MAX_CONNECT_ATTEMPTS && reason.contains("133")) {
                                        val delay = reconnectCooldownMillis * connectAttempts
                                        reconnectBlockedUntil = System.currentTimeMillis() + delay
                                        // after 133 force rescan, the deviceAddress is set to null
                                        mDeviceAddress = null
                                        aapsLogger.debug(LTag.PUMPBTCOMM, "Retrying connect attempt $connectAttempts/$MAX_CONNECT_ATTEMPTS after cooldown")
                                        handler.postDelayed({
                                                                connect("Retry after GATT 133", mDeviceSN)
                                                            }, delay)
                                    } else {
                                        reconnectBlockedUntil = System.currentTimeMillis() + 10_000
                                        aapsLogger.error(LTag.PUMPBTCOMM, "Max retries reached for GATT failure: $reason")
                                        connectAttempts = 0
                                        mCallback?.onBLEDisconnected()
                                    }
                                }, 1000)
        }
    }

    fun sendMessage(message: ByteArray) {
        handler.post {
            if (uartWrite == null || !isConnected) {
                mCallback?.onSendMessageError("BLE not ready", true)
                return@post
            }

            mWritePackets = WriteCommandPackets(message, mWriteSequenceNumber)
            mWriteSequenceNumber = (mWriteSequenceNumber + 1) % 256

            mWritePackets?.getNextPacket()?.let {
                writeCharacteristic(uartWriteBTGattChar, it)
            }
        }
    }

    private fun getGattService(): BluetoothGattService? {
        aapsLogger.debug(LTag.PUMPBTCOMM, "getGattService")
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            handleNotInitialized()
            return null
        }
        return mBluetoothGatt?.getService(UUID.fromString(SERVICE_UUID))
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        handler.postDelayed({
                                if (mBluetoothGatt == null || uartWrite == null) {
                                    handleNotInitialized()
                                    return@postDelayed
                                }

                                characteristic.value = data
                                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                aapsLogger.debug(LTag.PUMPBTCOMM, "writeCharacteristic: ${data.contentToString()}")
                                val success = mBluetoothGatt?.writeCharacteristic(characteristic) == true

                                if (!success) {
                                    mCallback?.onSendMessageError("Failed to write characteristic", true)
                                } else {
                                    writeTimeoutRunnable = Runnable {
                                        mCallback?.onSendMessageError("Write timeout", true)
                                    }
                                    handler.postDelayed(writeTimeoutRunnable!!, WRITE_TIMEOUT_MILLIS)
                                }
                            }, WRITE_DELAY_MILLIS)
    }

    /** Connect flow: 4. When services are discovered find characteristics and set notifications*/
    private fun findCharacteristic() {
        val gattService = getGattService() ?: return
        var uuid: String
        val gattCharacteristics = gattService.characteristics
        for (i in 0 until gattCharacteristics.size) {
            val gattCharacteristic = gattCharacteristics[i]
            // Check whether read or write properties is set, the pump needs us to enable notifications on all characteristics that have these properties
            if (gattCharacteristic.properties and NEEDS_ENABLE > 0) {
                handler.postDelayed({
                                        uuid = gattCharacteristic.uuid.toString()
                                        setCharacteristicNotification(gattCharacteristic, true)
                                        if (READ_UUID == uuid) {
                                            uartRead = gattCharacteristic
                                        }
                                        if (WRITE_UUID == uuid) {
                                            uartWrite = gattCharacteristic
                                        }
                                    }, (i * 600).toLong())
            }
        }
    }

    private fun handleNotInitialized() {
        aapsLogger.error("BluetoothAdapter not initialized_ERROR")
        isConnecting = false
        isConnected = false
    }
}
