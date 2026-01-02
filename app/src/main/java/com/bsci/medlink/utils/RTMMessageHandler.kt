package com.bsci.medlink.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Agora RTM 消息处理器
 * 负责接收 RTM 消息，解析后通过串口发送 HID 命令
 */
class RTMMessageHandler(private val context: Context) {
    private val TAG = "RTMMessageHandler"
    private val handler = Handler(Looper.getMainLooper())
    
    private var serialPortManager: SerialPortManager? = null
    private var isInitialized = false
    
    // 用于跟踪当前按下的键（用于键盘消息处理）
    private val currentPressedKeys = mutableSetOf<Int>()
    
    /**
     * 初始化消息处理器
     * @param serialPortManager 串口管理器实例，如果为 null 则自动创建
     */
    fun initialize(serialPortManager: SerialPortManager? = null) {
        if (isInitialized) {
            Log.w(TAG, "RTMMessageHandler already initialized")
            return
        }
        
        this.serialPortManager = serialPortManager ?: SerialPortManager(context)
        
        // 设置串口连接状态监听
        this.serialPortManager?.setOnConnectionStateListener { connected ->
            Log.d(TAG, "Serial port connection state: $connected")
            if (!connected) {
                // 串口断开时，清除所有按下的键
                currentPressedKeys.clear()
            }
        }
        
        // 尝试自动连接串口
        this.serialPortManager?.autoConnect()
        
        isInitialized = true
        Log.d(TAG, "RTMMessageHandler initialized")
    }
    
    /**
     * 处理接收到的 RTM 消息
     * @param messageText RTM 消息文本（JSON 格式）
     */
    fun handleMessage(messageText: String) {
        if (!isInitialized) {
            Log.w(TAG, "RTMMessageHandler not initialized, initializing now...")
            initialize()
        }
        
        val message = MessageProtocol.parseMessage(messageText)
        
        when (message) {
            is MessageProtocol.MouseMessage -> {
                handleMouseMessage(message)
            }
            is MessageProtocol.KeyboardMessage -> {
                handleKeyboardMessage(message)
            }
            else -> {
                Log.w(TAG, "Unknown or invalid message: $messageText")
            }
        }
    }
    
    /**
     * 处理鼠标消息
     */
    private fun handleMouseMessage(message: MessageProtocol.MouseMessage) {
        if (serialPortManager == null || !serialPortManager!!.isPortOpen()) {
            Log.w(TAG, "Serial port not available, cannot send mouse command")
            return
        }
        
        try {
            val command = when (message.action.lowercase()) {
                "move" -> {
                    // 相对移动
                    if (message.deltaX != null && message.deltaY != null) {
                        HIDCommandBuilder.buildMouseRelativeMoveCommand(
                            deltaX = message.deltaX.coerceIn(-127, 127),
                            deltaY = message.deltaY.coerceIn(-127, 127)
                        )
                    } else if (message.x != null && message.y != null && 
                               message.targetWidth != null && message.targetHeight != null) {
                        // 绝对位置
                        HIDCommandBuilder.buildMouseAbsoluteCommand(
                            x = message.x,
                            y = message.y,
                            targetWidth = message.targetWidth,
                            targetHeight = message.targetHeight
                        )
                    } else {
                        Log.w(TAG, "Invalid mouse move message: missing coordinates")
                        return
                    }
                }
                "click" -> {
                    // 点击事件（需要绝对位置才能支持按钮）
                    val buttonStatus = parseMouseButton(message.button)
                    if (message.x != null && message.y != null && 
                        message.targetWidth != null && message.targetHeight != null) {
                        HIDCommandBuilder.buildMouseAbsoluteCommand(
                            x = message.x,
                            y = message.y,
                            targetWidth = message.targetWidth,
                            targetHeight = message.targetHeight,
                            buttonStatus = buttonStatus
                        )
                    } else if (message.deltaX != null && message.deltaY != null) {
                        // 相对移动不支持按钮，先移动再发送点击
                        // 这里只发送移动命令，按钮状态无法通过相对移动命令发送
                        HIDCommandBuilder.buildMouseRelativeMoveCommand(
                            deltaX = message.deltaX.coerceIn(-127, 127),
                            deltaY = message.deltaY.coerceIn(-127, 127)
                        )
                    } else {
                        Log.w(TAG, "Invalid mouse click message: missing coordinates")
                        return
                    }
                }
                "down" -> {
                    // 按下（需要绝对位置才能支持按钮）
                    val buttonStatus = parseMouseButton(message.button)
                    if (message.x != null && message.y != null && 
                        message.targetWidth != null && message.targetHeight != null) {
                        HIDCommandBuilder.buildMouseAbsoluteCommand(
                            x = message.x,
                            y = message.y,
                            targetWidth = message.targetWidth,
                            targetHeight = message.targetHeight,
                            buttonStatus = buttonStatus
                        )
                    } else if (message.deltaX != null && message.deltaY != null) {
                        // 相对移动不支持按钮状态
                        HIDCommandBuilder.buildMouseRelativeMoveCommand(
                            deltaX = message.deltaX.coerceIn(-127, 127),
                            deltaY = message.deltaY.coerceIn(-127, 127)
                        )
                    } else {
                        Log.w(TAG, "Invalid mouse down message: missing coordinates")
                        return
                    }
                }
                "up" -> {
                    // 释放
                    if (message.x != null && message.y != null && 
                        message.targetWidth != null && message.targetHeight != null) {
                        HIDCommandBuilder.buildMouseAbsoluteCommand(
                            x = message.x,
                            y = message.y,
                            targetWidth = message.targetWidth,
                            targetHeight = message.targetHeight,
                            buttonStatus = HIDCommandBuilder.MouseButton.NONE
                        )
                    } else if (message.deltaX != null && message.deltaY != null) {
                        // 相对移动不支持按钮状态
                        HIDCommandBuilder.buildMouseRelativeMoveCommand(
                            deltaX = message.deltaX.coerceIn(-127, 127),
                            deltaY = message.deltaY.coerceIn(-127, 127)
                        )
                    } else {
                        Log.w(TAG, "Invalid mouse up message: missing coordinates")
                        return
                    }
                }
                "scroll" -> {
                    // 滚动（使用相对移动命令，但通常需要特殊处理）
                    if (message.scrollDelta != null) {
                        // 滚动可以转换为垂直移动
                        HIDCommandBuilder.buildMouseRelativeMoveCommand(
                            deltaX = 0,
                            deltaY = message.scrollDelta.coerceIn(-127, 127)
                        )
                    } else {
                        Log.w(TAG, "Invalid mouse scroll message: missing scrollDelta")
                        return
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown mouse action: ${message.action}")
                    return
                }
            }
            
            // 立即发送命令到串口
            val success = serialPortManager?.sendData(command) ?: false
            if (success) {
                Log.d(TAG, "Mouse command sent: ${message.action}")
            } else {
                Log.e(TAG, "Failed to send mouse command: ${message.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling mouse message", e)
        }
    }
    
    /**
     * 处理键盘消息
     */
    private fun handleKeyboardMessage(message: MessageProtocol.KeyboardMessage) {
        if (serialPortManager == null || !serialPortManager!!.isPortOpen()) {
            Log.w(TAG, "Serial port not available, cannot send keyboard command")
            return
        }
        
        try {
            val hidKeyCode = HIDCommandBuilder.transformKeyCode(message.keyCode)
            if (hidKeyCode == 0) {
                Log.w(TAG, "Unknown key code: ${message.keyCode}, skipping")
                return
            }
            
            val command = when (message.action.lowercase()) {
                "keydown", "keypress" -> {
                    // 按下键
                    currentPressedKeys.add(hidKeyCode)
                    HIDCommandBuilder.buildKeyboardCommand(
                        keyCode = hidKeyCode,
                        modifierKeys = message.modifierKeys,
                        isKeyDown = true,
                        pressedKeys = currentPressedKeys
                    )
                }
                "keyup" -> {
                    // 释放键
                    currentPressedKeys.remove(hidKeyCode)
                    HIDCommandBuilder.buildKeyboardCommand(
                        keyCode = hidKeyCode,
                        modifierKeys = message.modifierKeys,
                        isKeyDown = false,
                        pressedKeys = currentPressedKeys
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown keyboard action: ${message.action}")
                    return
                }
            }
            
            // 立即发送命令到串口
            val success = serialPortManager?.sendData(command) ?: false
            if (success) {
                Log.d(TAG, "Keyboard command sent: ${message.action}, keyCode=${message.keyCode}")
            } else {
                Log.e(TAG, "Failed to send keyboard command: ${message.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling keyboard message", e)
        }
    }
    
    /**
     * 解析鼠标按钮字符串为按钮状态
     */
    private fun parseMouseButton(button: String?): Int {
        return when (button?.lowercase()) {
            "left" -> HIDCommandBuilder.MouseButton.LEFT
            "right" -> HIDCommandBuilder.MouseButton.RIGHT
            "middle" -> HIDCommandBuilder.MouseButton.MIDDLE
            else -> HIDCommandBuilder.MouseButton.NONE
        }
    }
    
    /**
     * 释放所有按下的键（用于清理状态）
     */
    fun releaseAllKeys() {
        if (serialPortManager == null || !serialPortManager!!.isPortOpen()) {
            return
        }
        
        try {
            val command = HIDCommandBuilder.buildReleaseAllKeysCommand()
            serialPortManager?.sendData(command)
            currentPressedKeys.clear()
            Log.d(TAG, "All keys released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing all keys", e)
        }
    }
    
    /**
     * 检查串口是否可用
     */
    fun isSerialPortAvailable(): Boolean {
        return serialPortManager?.isPortOpen() == true
    }
    
    /**
     * 手动连接串口
     */
    fun connectSerialPort(): Boolean {
        return serialPortManager?.autoConnect() ?: false
    }
    
    /**
     * 释放资源
     */
    fun release() {
        releaseAllKeys()
        serialPortManager?.release()
        serialPortManager = null
        isInitialized = false
        Log.d(TAG, "RTMMessageHandler released")
    }
}

