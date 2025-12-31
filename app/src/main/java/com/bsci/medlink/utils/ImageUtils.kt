package com.bsci.medlink.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImageUtils {
    private const val TAG = "ImageUtils"
    
    /**
     * 从 base64 字符串解码为 Bitmap
     */
    fun decodeBase64ToBitmap(base64String: String?): Bitmap? {
        if (base64String.isNullOrEmpty()) {
            return null
        }
        
        return try {
            // 移除可能的数据 URI 前缀（如 "data:image/png;base64,"）
            val base64Data = if (base64String.contains(",")) {
                base64String.substringAfter(",")
            } else {
                base64String
            }
            
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 image", e)
            null
        }
    }
    
    /**
     * 异步加载 base64 图片到 ImageView
     */
    fun loadBase64Image(imageView: ImageView, base64String: String?, defaultResource: Int) {
        if (base64String.isNullOrEmpty()) {
            imageView.setImageResource(defaultResource)
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = decodeBase64ToBitmap(base64String)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(defaultResource)
                }
            }
        }
    }
}

