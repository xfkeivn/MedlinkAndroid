package com.bsci.medlink.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/**
 * USB 串口管理器
 * 用于发现、初始化和控制 USB 串口设备
 */
class SerialPortManager(private val context: Context) {
    private val TAG = "SerialPortManager"
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private var usbSerialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialIoManager: SerialInputOutputManager? = null
    
    private var onDataReceivedListener: ((ByteArray) -> Unit)? = null
    private var onConnectionStateListener: ((Boolean) -> Unit)? = null
    
    companion object {
        private const val ACTION_USB_PERMISSION = "com.bsci.medlink.USB_PERMISSION"
        private const val DEFAULT_BAUD_RATE = 9600
        private const val DEFAULT_DATA_BITS = 8
        private const val DEFAULT_STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val DEFAULT_PARITY = UsbSerialPort.PARITY_NONE
        private const val WRITE_WAIT_MILLIS = 2000
    }
    
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                            openSerialPort(device)
                        }
                    } else {
                        Log.e(TAG, "USB permission denied for device: ${device?.deviceName}")
                        onConnectionStateListener?.invoke(false)
                    }
                }
            }
        }
    }
    
    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {

                        Log.d(TAG, "USB device attached: ${it.deviceName}")
                        if (isSerialPortDevice(it)) {
                            requestPermission(it)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.d(TAG, "USB device detached: ${it.deviceName}")
                        if (usbSerialPort?.driver?.device?.deviceId == it.deviceId) {
                            disconnect()
                            onConnectionStateListener?.invoke(false)
                        }
                    }
                }
            }
        }
    }
    
    init {
        // 注册 USB 权限和设备插拔广播接收器
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbPermissionReceiver, filter)
        context.registerReceiver(usbDeviceReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
    }
    
    /**
     * 发现所有可用的 USB 串口设备
     */
    fun discoverSerialPorts(): List<UsbSerialDriver> {
        val allDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.d(TAG, "Found ${allDrivers.size} USB serial drivers")
        return allDrivers
    }
    
    /**
     * 获取所有已连接的 USB 设备（包括非串口设备）
     */
    fun getAllUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }
    
    /**
     * 检查设备是否是串口设备
     */
    fun isSerialPortDevice(device: UsbDevice): Boolean {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        return driver != null
    }
    
    /**
     * 请求 USB 权限
     */
    fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) {
            openSerialPort(device)
            return true
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
        return false
    }
    
    /**
     * 打开串口
     */
    private fun openSerialPort(device: UsbDevice) {
        try {
            // 探测设备驱动
            var driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                Log.e(TAG, "No serial driver found for device: ${device.deviceName}")
                onConnectionStateListener?.invoke(false)
                return
            }
            
            // 检查端口数量
            if (driver.ports.isEmpty()) {
                Log.e(TAG, "No ports found for device: ${device.deviceName}")
                onConnectionStateListener?.invoke(false)
                return
            }
            
            val port = driver.ports[0] // 使用第一个端口
            
            // 打开 USB 设备连接
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                if (!usbManager.hasPermission(device)) {
                    Log.e(TAG, "Connection failed: permission denied")
                } else {
                    Log.e(TAG, "Connection failed: open failed")
                }
                onConnectionStateListener?.invoke(false)
                return
            }
            
            usbConnection = connection
            
            // 打开串口
            port.open(connection)
            
            // 设置串口参数
            try {
                port.setParameters(
                    DEFAULT_BAUD_RATE,
                    DEFAULT_DATA_BITS,
                    DEFAULT_STOP_BITS,
                    DEFAULT_PARITY
                )
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "setParameters not supported: ${e.message}")
            }
            
            usbSerialPort = port
            Log.d(TAG, "Serial port opened successfully")
            
            // 启动数据监听
            startIoManager()
            
            onConnectionStateListener?.invoke(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            disconnect()
            onConnectionStateListener?.invoke(false)
        }
    }
    
    /**
     * 启动 I/O 管理器，用于接收数据
     */
    private fun startIoManager() {
        val port = usbSerialPort ?: return
        
        serialIoManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                Log.d(TAG, "Received data: ${data.size} bytes")
                onDataReceivedListener?.invoke(data)
            }
            
            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial I/O error", e)
                disconnect()
            }
        })
        
        serialIoManager?.start()
    }
    
    /**
     * 停止 I/O 管理器
     */
    private fun stopIoManager() {
        serialIoManager?.setListener(null)
        serialIoManager?.stop()
        serialIoManager = null
    }
    
    /**
     * 发送数据到串口
     */
    fun sendData(data: ByteArray): Boolean {
        return try {
            val port = usbSerialPort
            if (port == null) {
                Log.w(TAG, "Serial port not opened")
                return false
            }
            
            port.write(data, WRITE_WAIT_MILLIS)
            Log.d(TAG, "Sent data: ${data.size} bytes")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error sending data", e)
            disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data", e)
            false
        }
    }
    
    /**
     * 发送字符串到串口
     */
    fun sendString(message: String): Boolean {
        return sendData(message.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * 断开串口连接
     */
    private fun disconnect() {
        stopIoManager()
        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            // 忽略关闭时的异常
        }
        usbSerialPort = null
        usbConnection?.close()
        usbConnection = null
        Log.d(TAG, "Serial port disconnected")
    }
    
    /**
     * 关闭串口
     */
    fun closeSerialPort() {
        disconnect()
        onConnectionStateListener?.invoke(false)
    }
    
    /**
     * 检查串口是否已打开
     */
    fun isPortOpen(): Boolean {
        return usbSerialPort != null
    }
    
    /**
     * 设置数据接收监听器
     */
    fun setOnDataReceivedListener(listener: (ByteArray) -> Unit) {
        this.onDataReceivedListener = listener
    }
    
    /**
     * 设置连接状态监听器
     */
    fun setOnConnectionStateListener(listener: (Boolean) -> Unit) {
        this.onConnectionStateListener = listener
    }
    
    /**
     * 自动发现并连接第一个可用的串口设备
     */
    fun autoConnect(): Boolean {
        val drivers = discoverSerialPorts()
        if (drivers.isEmpty()) {
            Log.w(TAG, "No serial port devices found")
            return false
        }
        
        val device = drivers[0].device
        return requestPermission(device)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        closeSerialPort()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
            context.unregisterReceiver(usbDeviceReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }
}

