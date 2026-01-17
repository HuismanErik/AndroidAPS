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
import android.os.SystemClock
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

        private const val WRITE_DELAY_MILLIS: Long = 30
        private const val WRITE_TIMEOUT_MILLIS = 2000L
        private const val SERVICE_UUID = "669A9001-0008-968F-E311-6050405558B3"
        private const val READ_UUID = "669a9120-0008-968f-e311-6050405558b3"
        private const val WRITE_UUID = "669a9101-0008-968f-e311-6050405558b3"
        private const val CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        private const val NEEDS_ENABLE_NOTIFICATION = 0x10
        private const val NEEDS_ENABLE_INDICATION = 0x20
        private const val NEEDS_ENABLE = 0x30

        private const val MANUFACTURER_ID = 18305
    }

    private val reconnectCooldownMillis: Long = 3000
    private val maxConnectAttempts: Int = 3
    private var connectAttempts: Int = 0
    private var reconnectBlockedUntil: Long = 0
    private val handler =
        Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private val mBluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var mBluetoothGatt: BluetoothGatt? = null

    private var isConnected = false // Only to track internal ble state
    private var isConnecting = false // Only to track internal ble state
    private var uartWrite: BluetoothGattCharacteristic? = null
    private var uartRead: BluetoothGattCharacteristic? = null

    // Read and write buffers
    private var mWritePackets: WriteCommandPackets? = null
    private var mWriteSequenceNumber: Int = 0
    private var mReadPacket: ReadDataPacket? = null
    private val readLock = Any()

    private var mDeviceSN: Long = 0
    private var mCallback: BLECommCallback? = null
    private var mDeviceAddress: String? = null
    private var writeTimeoutRunnable: Runnable? = null

    private val bleStateLock = Any()  // Dedicated lock for BLE state

    fun setCallback(callback: BLECommCallback?) {
        this.mCallback = callback
    }

    /** Connect flow: 1. Start scanning for our device (SN entered in settings) */
    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permissions")
            return false
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Start scan!!")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

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
        aapsLogger.debug(LTag.PUMPBTCOMM, "Initializing BLEComm.")
        if (mBluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.")
            return false
        }
        synchronized(bleStateLock) {
            val now = System.currentTimeMillis()
            if (now < reconnectBlockedUntil) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "connect ignored: cooldown active")
                return true
            }
            isConnected = false
            isConnecting = true
            mWritePackets = null
            mReadPacket = null
        }

        if (mDeviceAddress != null && mDeviceSN == deviceSN) {
            // Skip scanning and directly connect to gatt
            aapsLogger.debug(LTag.PUMPBTCOMM, "Skipping scan and directly connecting to gatt")
            mBluetoothAdapter?.getRemoteDevice(mDeviceAddress)?.let { connectGatt(it) }
        } else {
            // Scan for device
            aapsLogger.debug(LTag.PUMPBTCOMM, "Scanning for device")
            mDeviceAddress = null
            mDeviceSN = deviceSN
            startScan()
        }
        return true
    }

    /** Connect flow: 2. When device is found this is called by onScanResult() */
    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        synchronized(bleStateLock) {
            // Reset sequence counter
            mWriteSequenceNumber = 0
            if (mBluetoothGatt == null) {
                mBluetoothGatt = device.connectGatt(context, false, mGattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                // Already connected?, this should not happen force disconnect
                aapsLogger.error(LTag.PUMPBTCOMM, "connectGatt, mBluetoothGatt is not null")
                disconnect("connectGatt, mBluetoothGatt is not null")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(from: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            aapsLogger.error(LTag.PUMPBTCOMM, "missing permission: $from")
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "disconnect from: $from")
        val wasConnecting: Boolean
        synchronized(bleStateLock) {
            wasConnecting = isConnecting
            isConnecting = false
        }
        if (wasConnecting) {
            stopScan()
            SystemClock.sleep(100)
        }
        synchronized(bleStateLock) {
            if (isConnected) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Connected, disconnecting - will close after callback")
                mBluetoothGatt?.disconnect()
                // Delay close tot na disconnect-callback
                handler.postDelayed({ close() }, 500)
            } else {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Not connected, closing gatt")
                isConnected = false
                mCallback?.onBLEDisconnected()
                close()
            }
        }
    }

    /** Scan callback  */
    private val mScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "OnScanResult! $result")
            super.onScanResult(callbackType, result)

            val manufacturerData =
                result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                    ?.let { ManufacturerData(it) }

            aapsLogger.debug(LTag.PUMPBTCOMM, "Found deviceSN: " + manufacturerData?.getDeviceSN())

            if (manufacturerData?.getDeviceSN() == mDeviceSN) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Found our device! deviceSN: " + manufacturerData.getDeviceSN())
                stopScan()
                mDeviceAddress = result.device.address
                connectGatt(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Scan FAILED!")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        /** Connect flow: 3. When we are connected discover services*/
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
                aapsLogger.error(LTag.PUMPBTCOMM, "missing permissions")
                return
            }

            synchronized(bleStateLock) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "onConnectionStateChange newState: $newState status: $status")

                if (status == 133) {
                    handleGattFailure("status 133")
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnecting = false
                    aapsLogger.debug(LTag.PUMPBTCOMM, "STATE_CONNECTED - starting discoverServices")
                    mBluetoothGatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (isConnecting) {
                        val resetDevice = preferences.get(MedtrumBooleanKey.MedtrumScanOnConnectionErrors)
                        if (resetDevice) {
                            // When we are disconnected during connecting, we reset the device address to force a new scan
                            aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnected while connecting! Reset device address")
                            mDeviceAddress = null
                        }
                        // Wait a bit before retrying
                        SystemClock.sleep(2000)
                    }
                    isConnecting = false
                    isConnected = false
                    mCallback?.onBLEDisconnected()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "STATE_DISCONNECTED - cleaning up")
                    close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Services discovered successfully - finding characteristics")
                findCharacteristic()
                synchronized(bleStateLock) {
                    isConnected = true
                }
            } else {
                aapsLogger.error(LTag.PUMPBTCOMM, "Service discovery failed with status: $status")
                handleGattFailure("Service discovery failed: $status")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicRead data: " + characteristic.value.contentToString() + " UUID: " + characteristic.uuid.toString() + " status: " + status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicChanged data: " + characteristic.value.contentToString() + " UUID: " + characteristic.uuid.toString())

            val value = characteristic.value
            if (characteristic.uuid == UUID.fromString(READ_UUID)) {
                mCallback?.onNotification(value)
            } else if (characteristic.uuid == UUID.fromString(WRITE_UUID)) {
                synchronized(readLock) {
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
            aapsLogger.debug(LTag.PUMPBTCOMM, "onCharacteristicWrite status = $status")

            writeTimeoutRunnable?.let { handler.removeCallbacks(it) }
            writeTimeoutRunnable = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Check if we need to finish our command!
                mWritePackets?.let {
                    synchronized(it) {
                        val value: ByteArray? = mWritePackets?.getNextPacket()
                        if (value != null) {
                            writeCharacteristic(uartWriteBTGattChar, value)
                        }
                    }
                }
            } else {
                mCallback?.onSendMessageError("onCharacteristicWrite failure", true)
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
        synchronized(bleStateLock) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "readDescriptor")
            if (mBluetoothAdapter == null || mBluetoothGatt == null || descriptor == null) {
                handleNotInitialized()
                return
            }
            mBluetoothGatt?.readDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun checkDescriptor(descriptor: BluetoothGattDescriptor) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "checkDescriptor value: ${descriptor.value?.contentToString()}")
        val service = getGattService()
        if (mBluetoothAdapter == null || mBluetoothGatt == null || service == null) {
            handleNotInitialized()
            return
        }
        if (descriptor.value.toInt() > 0) {
            var notificationEnabled = true
            val characteristics = service.characteristics
            for (j in 0 until characteristics.size) {
                val configDescriptor =
                    characteristics[j].getDescriptor(UUID.fromString(CONFIG_UUID))
                if (configDescriptor.value == null || configDescriptor.value.toInt() <= 0) {
                    notificationEnabled = false
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Descriptor not enabled for char: ${characteristics[j].uuid}")
                }
            }
            if (notificationEnabled) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Notifications enabled! Calling onBLEConnected")
                synchronized(bleStateLock) {
                    connectAttempts = 0
                    mCallback?.onBLEConnected()
                }
            } else {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Notifications NOT fully enabled")
            }
        } else {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Descriptor value <= 0")
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        synchronized(bleStateLock) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "setCharacteristicNotification")
            if (mBluetoothAdapter == null || mBluetoothGatt == null) {
                handleNotInitialized()
                return
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
        synchronized(bleStateLock) {
            aapsLogger.error(LTag.PUMPBTCOMM, "GATT failure: $reason")
            isConnecting = false
            isConnected = false
            connectAttempts++

            if (connectAttempts < maxConnectAttempts && reason.contains("133")) {
                reconnectBlockedUntil = System.currentTimeMillis() + reconnectCooldownMillis
                aapsLogger.debug(LTag.PUMPBTCOMM, "Retrying connect attempt $connectAttempts/$maxConnectAttempts after cooldown")
                handler.postDelayed({
                                        connect("Retry after GATT 133", mDeviceSN)
                                    }, reconnectCooldownMillis)
            } else {
                reconnectBlockedUntil = System.currentTimeMillis() + 10000
                aapsLogger.error(LTag.PUMPBTCOMM, "Max retries reached for GATT failure: $reason")
                connectAttempts = 0
                mCallback?.onBLEDisconnected()
            }
        }
        close()
    }

    fun sendMessage(message: ByteArray) {
        synchronized(bleStateLock) {
            if (uartWrite == null || !isConnected) {
                mCallback?.onSendMessageError("BLE not ready", true)
                return
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "sendMessage message = " + message.contentToString())
            if (mWritePackets?.allPacketsConsumed() == false) {
                aapsLogger.error(LTag.PUMPBTCOMM, "sendMessage not all packets consumed!! unable to sent message!")
                return
            }
            mWritePackets = WriteCommandPackets(message, mWriteSequenceNumber)
            mWriteSequenceNumber = (mWriteSequenceNumber + 1) % 256
        }
        val value: ByteArray? = mWritePackets?.getNextPacket()
        if (value != null) {
            writeCharacteristic(uartWriteBTGattChar, value)
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "sendMessage error in writePacket!")
            mCallback?.onSendMessageError("error in writePacket!", false)
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

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, data: ByteArray?) {
        handler.postDelayed({
                                synchronized(bleStateLock) {
                                    if (mBluetoothAdapter == null || mBluetoothGatt == null || uartWrite == null) {
                                        handleNotInitialized()
                                    } else {
                                        characteristic.value = data
                                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                        aapsLogger.debug(LTag.PUMPBTCOMM, "writeCharacteristic: ${data.contentToString()}")
                                        val success = mBluetoothGatt?.writeCharacteristic(characteristic)
                                        if (success != true) {
                                            mCallback?.onSendMessageError("Failed to write characteristic", true)
                                        } else {
                                            writeTimeoutRunnable?.let { handler.removeCallbacks(it) }
                                            writeTimeoutRunnable = Runnable {
                                                aapsLogger.error(LTag.PUMPBTCOMM, "Write timeout")
                                                mCallback?.onSendMessageError("Write timeout", true)
                                                disconnect("write timeout")
                                            }
                                            handler.postDelayed(writeTimeoutRunnable!!, WRITE_TIMEOUT_MILLIS)
                                        }
                                    }
                                }
                            }, WRITE_DELAY_MILLIS)
    }

    private val uartWriteBTGattChar: BluetoothGattCharacteristic
        get() = uartWrite
            ?: BluetoothGattCharacteristic(UUID.fromString(WRITE_UUID), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, 0).also { uartWrite = it }

    @SuppressLint("MissingPermission")
    fun close() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "BluetoothAdapter close")
        val gattToClose: BluetoothGatt?
        synchronized(bleStateLock) {
            gattToClose = mBluetoothGatt
            mBluetoothGatt = null
        }
        if (gattToClose != null) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Closing GATT")
            gattToClose.close()
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "GATT already null")
        }
        SystemClock.sleep(100)
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
        synchronized(bleStateLock) {
            aapsLogger.error("BluetoothAdapter not initialized_ERROR")
            isConnecting = false
            isConnected = false
        }
        return
    }
}