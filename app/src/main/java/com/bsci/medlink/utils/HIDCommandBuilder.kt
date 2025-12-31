package com.bsci.medlink.utils

import android.util.Log

/**
 * HID 控制指令构建器
 * 用于生成鼠标和键盘控制的串口指令
 * 基于 Windows HIDControl 的协议实现
 */
object HIDCommandBuilder {
    private const val TAG = "HIDCommandBuilder"
    
    // 协议头
    private const val PROTOCOL_HEADER_1: Byte = 0x57
    private const val PROTOCOL_HEADER_2: Byte = 0xAB.toByte()
    private const val PROTOCOL_RESERVED: Byte = 0x00
    
    /**
     * 鼠标按钮状态
     */
    object MouseButton {
        const val NONE = 0
        const val LEFT = 1 shl 0      // 左键
        const val RIGHT = 1 shl 1     // 右键
        const val MIDDLE = 1 shl 2    // 中键
    }
    
    /**
     * 修饰键
     */
    object ModifierKey {
        const val CONTROL = 1 shl 0    // Ctrl
        const val SHIFT = 1 shl 1      // Shift
        const val ALT = 1 shl 2        // Alt
        const val WIN = 1 shl 3        // Win
    }
    
    /**
     * 构建鼠标相对移动指令
     * @param deltaX X方向相对移动量 (-127 到 127)
     * @param deltaY Y方向相对移动量 (-127 到 127)
     * @return 11字节的指令数组
     */
    fun buildMouseRelativeMoveCommand(deltaX: Int, deltaY: Int): ByteArray {
        val cmd = mutableListOf<Byte>()
        
        // 协议头
        cmd.add(PROTOCOL_HEADER_1)
        cmd.add(PROTOCOL_HEADER_2)
        cmd.add(PROTOCOL_RESERVED)
        cmd.add(0x05)  // 命令类型：鼠标相对移动
        cmd.add(0x05)  // 数据长度
        cmd.add(0x01)  // 子命令
        cmd.add(0x00)  // 保留
        
        // 移动量（限制在 -127 到 127 范围内）
        val x = deltaX.coerceIn(-127, 127).toByte()
        val y = deltaY.coerceIn(-127, 127).toByte()
        cmd.add(x)
        cmd.add(y)
        cmd.add(0x00)  // 保留
        
        // 计算校验和
        val checksum = calculateChecksum(cmd)
        cmd.add(checksum)
        
        return cmd.toByteArray()
    }
    
    /**
     * 构建鼠标绝对位置和按钮状态指令
     * @param x 鼠标X坐标 (0 到 targetWidth)
     * @param y 鼠标Y坐标 (0 到 targetHeight)
     * @param targetWidth 目标窗口宽度
     * @param targetHeight 目标窗口高度
     * @param buttonStatus 按钮状态 (MouseButton 组合)
     * @return 13字节的指令数组
     */
    fun buildMouseAbsoluteCommand(
        x: Int,
        y: Int,
        targetWidth: Int,
        targetHeight: Int,
        buttonStatus: Int = MouseButton.NONE
    ): ByteArray {
        val cmd = mutableListOf<Byte>()
        
        // 协议头
        cmd.add(PROTOCOL_HEADER_1)
        cmd.add(PROTOCOL_HEADER_2)
        cmd.add(PROTOCOL_RESERVED)
        cmd.add(0x04)  // 命令类型：鼠标绝对位置
        cmd.add(0x07)  // 数据长度
        cmd.add(0x02)  // 子命令
        
        // 按钮状态
        cmd.add(buttonStatus.toByte())
        
        // 计算绝对位置（转换为 0-4096 范围，然后乘以 2/3）
        val normalizedX = ((x * 4096) / targetWidth) * 2 / 3
        val normalizedY = ((y * 4096) / targetHeight) * 2 / 3
        
        val xLow = (normalizedX and 0xFF).toByte()
        val xHigh = ((normalizedX shr 8) and 0xFF).toByte()
        val yLow = (normalizedY and 0xFF).toByte()
        val yHigh = ((normalizedY shr 8) and 0xFF).toByte()
        
        // 位置数据（小端序）
        cmd.add(xLow)
        cmd.add(xHigh)
        cmd.add(yLow)
        cmd.add(yHigh)
        cmd.add(0x00)  // 保留
        
        // 计算校验和
        val checksum = calculateChecksum(cmd)
        cmd.add(checksum)
        
        return cmd.toByteArray()
    }
    
    /**
     * 构建键盘按键指令
     * @param keyCode 按键代码（转换后的HID键码）
     * @param modifierKeys 修饰键组合 (ModifierKey 组合)
     * @param isKeyDown true=按下, false=释放
     * @param pressedKeys 当前按下的普通键集合（最多6个）
     * @return 14字节的指令数组
     */
    fun buildKeyboardCommand(
        keyCode: Int,
        modifierKeys: Int = 0,
        isKeyDown: Boolean = true,
        pressedKeys: Set<Int> = emptySet()
    ): ByteArray {
        val cmd = mutableListOf<Byte>()
        
        // 协议头
        cmd.add(PROTOCOL_HEADER_1)
        cmd.add(PROTOCOL_HEADER_2)
        cmd.add(PROTOCOL_RESERVED)
        cmd.add(0x02)  // 命令类型：键盘
        cmd.add(0x08)  // 数据长度
        
        // 修饰键状态（8位）
        val modifierByte = modifierKeys.toByte()
        cmd.add(modifierByte)
        cmd.add(0x00)  // 保留
        
        // 普通按键（最多6个）
        val keysToSend = if (isKeyDown) {
            // 按下时，添加当前按键到集合
            (pressedKeys + keyCode).take(6)
        } else {
            // 释放时，从集合中移除
            pressedKeys.filter { it != keyCode }.take(6)
        }
        
        // 填充按键数据
        keysToSend.forEach { cmd.add(it.toByte()) }
        // 如果不足6个，用0填充
        repeat(6 - keysToSend.size) { cmd.add(0x00) }
        
        // 计算校验和
        val checksum = calculateChecksum(cmd)
        cmd.add(checksum)
        
        return cmd.toByteArray()
    }
    
    /**
     * 构建释放所有按键指令
     * @return 14字节的指令数组
     */
    fun buildReleaseAllKeysCommand(): ByteArray {
        val cmd = mutableListOf<Byte>()
        
        // 协议头
        cmd.add(PROTOCOL_HEADER_1)
        cmd.add(PROTOCOL_HEADER_2)
        cmd.add(PROTOCOL_RESERVED)
        cmd.add(0x02)  // 命令类型：键盘
        cmd.add(0x08)  // 数据长度
        
        // 所有修饰键和普通键都设为0
        cmd.add(0x00)  // 修饰键
        cmd.add(0x00)  // 保留
        repeat(6) { cmd.add(0x00) }  // 6个普通键
        
        // 计算校验和
        val checksum = calculateChecksum(cmd)
        cmd.add(checksum)
        
        return cmd.toByteArray()
    }
    
    /**
     * 将标准键码转换为HID键码
     * @param keyValue 标准键码（如 KeyEvent.KEYCODE_A）
     * @return HID键码
     */
    fun transformKeyCode(keyValue: Int): Int {
        // 字母 A-Z (65-90) -> 0x04-0x1D
        if (keyValue in 65..90) {
            return 0x04 + (keyValue - 65)
        }
        
        // 数字 0-9 (48-57)
        if (keyValue in 48..57) {
            return if (keyValue == 48) {
                0x27  // 0
            } else {
                0x1E + (keyValue - 49)  // 1-9
            }
        }
        
        // 小键盘数字 (96-105)
        if (keyValue in 96..105) {
            return if (keyValue == 96) {
                0x62  // 小键盘0
            } else {
                0x59 + (keyValue - 97)  // 小键盘1-9
            }
        }
        
        // 功能键 F1-F12 (112-123) -> 0x3A-0x45
        if (keyValue in 112..123) {
            return 0x3A + (keyValue - 112)
        }
        
        // 特殊键映射
        return when (keyValue) {
            27 -> 0x29  // ESC
            8 -> 0x2A   // Backspace
            9 -> 0x2B   // Tab
            20 -> 0x39  // Caps Lock
            13 -> 0x28  // Enter
            16 -> 0x02  // Shift (左)
            17 -> 0x01  // Ctrl (左)
            32 -> 0x2C  // Space
            45 -> 0x49  // Insert
            46 -> 0x4C  // Delete
            36 -> 0x4A  // Home
            35 -> 0x4D  // End
            33 -> 0x4B  // Page Up
            34 -> 0x4E  // Page Down
            37 -> 0x50  // Left Arrow
            38 -> 0x52  // Up Arrow
            39 -> 0x4F  // Right Arrow
            40 -> 0x51  // Down Arrow
            144 -> 0x53 // Num Lock
            192 -> 0x35 // `
            189 -> 0x2D // -
            187 -> 0x2E // =
            219 -> 0x2F // [
            221 -> 0x30 // ]
            186 -> 0x33 // ;
            222 -> 0x34 // '
            220 -> 0x31 // \
            188 -> 0x36 // ,
            190 -> 0x37 // .
            191 -> 0x38 // /
            111 -> 0x38 // / (小键盘)
            106 -> 0x55 // * (小键盘)
            109 -> 0x56 // - (小键盘)
            107 -> 0x57 // + (小键盘)
            110 -> 0x63 // . (小键盘)
            91 -> 0x08  // Win
            else -> {
                Log.w(TAG, "Unknown key code: $keyValue")
                0x00  // 未知键
            }
        }
    }
    
    /**
     * 计算校验和（所有字节相加后对256取模）
     */
    private fun calculateChecksum(cmd: List<Byte>): Byte {
        var sum = 0
        for (byte in cmd) {
            sum += byte.toInt() and 0xFF
        }
        return (sum % 256).toByte()
    }
    
    /**
     * 将字节数组转换为十六进制字符串（用于调试）
     */
    fun bytesToHexString(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}

