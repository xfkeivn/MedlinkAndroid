package com.bsci.medlink.utils

import android.util.Log

/**
 * Agora RTM 消息协议解析器
 * 用于解析从 Agora RTM 服务传递过来的消息（逗号分隔的字符串格式）
 */
object MessageProtocol {
    private const val TAG = "MessageProtocol"
    
    // 消息类型
    object MessageType {
        const val MOUSE = "mouse"
        const val KEYBOARD = "keyboard"
        const val UNKNOWN = "unknown"
    }
    
    /**
     * 鼠标消息数据类
     */
    data class MouseMessage(
        val type: String,           // 消息类型: "mouse"
        val action: String,        // 动作: "move", "click", "scroll", "down", "up"
        val x: Int? = null,        // X 坐标（绝对位置或相对移动量）
        val y: Int? = null,        // Y 坐标（绝对位置或相对移动量）
        val deltaX: Int? = null,   // X 方向相对移动量
        val deltaY: Int? = null,   // Y 方向相对移动量
        val button: String? = null, // 按钮: "left", "right", "middle"
        val scrollDelta: Int? = null, // 滚动量
        val targetWidth: Int? = null, // 目标屏幕宽度（用于绝对位置）
        val targetHeight: Int? = null // 目标屏幕高度（用于绝对位置）
    )
    
    /**
     * 键盘消息数据类
     */
    data class KeyboardMessage(
        val type: String,           // 消息类型: "keyboard"
        val action: String,        // 动作: "keydown", "keyup", "keypress"
        val keyCode: Int,          // 按键代码
        val key: String? = null,   // 按键字符
        val modifierKeys: Int = 0, // 修饰键组合 (ModifierKey 组合)
        val pressedKeys: Set<Int> = emptySet() // 当前按下的键集合
    )
    
    /**
     * 解析 RTM 消息（逗号分隔的字符串格式）
     * @param messageText RTM 消息文本（逗号分隔格式，如: "mouse,move,10,20" 或 "keyboard,keydown,65"）
     * @return 解析后的消息对象，如果解析失败返回 null
     */
    fun parseMessage(messageText: String): Any? {
        return try {
            // 按逗号分割字符串
            val parts = messageText.split(",").map { it.trim() }
            
            if (parts.isEmpty()) {
                Log.w(TAG, "Empty message: $messageText")
                return null
            }
            
            val type = parts[0].lowercase()
            
            when (type) {
                MessageType.MOUSE -> parseMouseMessage(parts)
                MessageType.KEYBOARD -> parseKeyboardMessage(parts)
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $messageText", e)
            null
        }
    }
    
    /**
     * 解析鼠标消息
     * 格式示例:
     * - mouse,move,10,20 (相对移动)
     * - mouse,click,100,200,left (绝对位置点击)
     * - mouse,down,100,200,left (按下)
     * - mouse,up,100,200 (释放)
     * - mouse,scroll,5 (滚动)
     */
    private fun parseMouseMessage(parts: List<String>): MouseMessage? {
        return try {
            if (parts.size < 3) {
                Log.w(TAG, "Invalid mouse message format, need at least 3 parts: ${parts.joinToString(",")}")
                return null
            }
            
            val action = parts[1].lowercase()
            var x: Int? = null
            var y: Int? = null
            var deltaX: Int? = null
            var deltaY: Int? = null
            var button: String? = null
            var scrollDelta: Int? = null
            var targetWidth: Int? = null
            var targetHeight: Int? = null
            
            when (action) {
                "move" -> {
                    // mouse,move,deltaX,deltaY 或 mouse,move,x,y,targetWidth,targetHeight
                    if (parts.size >= 4) {
                        val val1 = parts[2].toIntOrNull()
                        val val2 = parts[3].toIntOrNull()
                        if (val1 != null && val2 != null) {
                            if (parts.size >= 6) {
                                // 绝对位置模式
                                x = val1
                                y = val2
                                targetWidth = parts[4].toIntOrNull()
                                targetHeight = parts[5].toIntOrNull()
                            } else {
                                // 相对移动模式
                                deltaX = val1
                                deltaY = val2
                            }
                        }
                    }
                }
                "click", "down", "up" -> {
                    // mouse,click,x,y,button 或 mouse,click,x,y,button,targetWidth,targetHeight
                    if (parts.size >= 4) {
                        x = parts[2].toIntOrNull()
                        y = parts[3].toIntOrNull()
                        if (parts.size >= 5) {
                            button = parts[4].lowercase()
                        }
                        if (parts.size >= 7) {
                            targetWidth = parts[5].toIntOrNull()
                            targetHeight = parts[6].toIntOrNull()
                        }
                    }
                }
                "scroll" -> {
                    // mouse,scroll,scrollDelta
                    if (parts.size >= 3) {
                        scrollDelta = parts[2].toIntOrNull()
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown mouse action: $action")
                    return null
                }
            }
            
            MouseMessage(
                type = parts[0],
                action = action,
                x = x,
                y = y,
                deltaX = deltaX,
                deltaY = deltaY,
                button = button,
                scrollDelta = scrollDelta,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing mouse message: ${parts.joinToString(",")}", e)
            null
        }
    }
    
    /**
     * 解析键盘消息
     * 格式示例:
     * - keyboard,keydown,65 (按下 A 键)
     * - keyboard,keyup,65 (释放 A 键)
     * - keyboard,keydown,67,ctrl (Ctrl+C)
     * - keyboard,keydown,67,ctrl,shift (Ctrl+Shift+C)
     */
    private fun parseKeyboardMessage(parts: List<String>): KeyboardMessage? {
        return try {
            if (parts.size < 3) {
                Log.w(TAG, "Invalid keyboard message format, need at least 3 parts: ${parts.joinToString(",")}")
                return null
            }
            
            val action = parts[1].lowercase()
            val keyCode = parts[2].toIntOrNull()
            
            if (keyCode == null) {
                Log.w(TAG, "Invalid keyCode: ${parts[2]}")
                return null
            }
            
            // 解析修饰键（从第4个参数开始）
            var modifierKeys = 0
            val pressedKeys = mutableSetOf<Int>()
            
            for (i in 3 until parts.size) {
                val modifier = parts[i].lowercase()
                when (modifier) {
                    "ctrl", "control" -> modifierKeys = modifierKeys or HIDCommandBuilder.ModifierKey.CONTROL
                    "shift" -> modifierKeys = modifierKeys or HIDCommandBuilder.ModifierKey.SHIFT
                    "alt" -> modifierKeys = modifierKeys or HIDCommandBuilder.ModifierKey.ALT
                    "meta", "win" -> modifierKeys = modifierKeys or HIDCommandBuilder.ModifierKey.WIN
                    else -> {
                        // 可能是其他按键代码
                        val additionalKeyCode = modifier.toIntOrNull()
                        if (additionalKeyCode != null && additionalKeyCode > 0) {
                            pressedKeys.add(additionalKeyCode)
                        }
                    }
                }
            }
            
            KeyboardMessage(
                type = parts[0],
                action = action,
                keyCode = keyCode,
                key = null,
                modifierKeys = modifierKeys,
                pressedKeys = pressedKeys
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing keyboard message: ${parts.joinToString(",")}", e)
            null
        }
    }
}

