package com.eopeter.fluttermapboxnavigation.utilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class for loading marker icons from various sources:
 * - Network URLs
 * - Asset paths
 * - Base64 strings
 * 
 * Includes caching for performance optimization.
 */
class IconLoader(private val context: Context) {
    
    companion object {
        private const val MAX_CACHE_SIZE = 50
        private const val DEFAULT_ICON_SIZE = 40
    }
    
    // LRU cache for loaded bitmaps
    private val iconCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024 // Size in KB
        }
    }
    
    // Cache for network requests in progress to avoid duplicate downloads
    private val loadingCache = ConcurrentHashMap<String, Bitmap?>()
    
    /**
     * Load icon from various sources
     * 
     * @param iconSource Source type: "networkUrl", "assetPath", "base64", or "defaultIcon"
     * @param iconData The actual data (URL, path, or base64 string)
     * @param width Desired width in pixels
     * @param height Desired height in pixels
     * @param color Optional color tint for default icon
     * @return Bitmap or null if loading fails
     */
    suspend fun loadIcon(
        iconSource: String,
        iconData: String?,
        width: Int = DEFAULT_ICON_SIZE,
        height: Int = DEFAULT_ICON_SIZE,
        color: Int? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = generateCacheKey(iconSource, iconData, width, height, color)
            
            // Check cache first
            iconCache.get(cacheKey)?.let { return@withContext it }
            
            // Check if already loading
            loadingCache[cacheKey]?.let { return@withContext it }
            
            val bitmap = when (iconSource) {
                "networkUrl" -> loadFromNetwork(iconData, width, height)
                "assetPath" -> loadFromAsset(iconData, width, height)
                "base64" -> loadFromBase64(iconData, width, height)
                "defaultIcon" -> createDefaultIcon(width, height, color)
                else -> createDefaultIcon(width, height, color)
            }
            
            // Apply color tint if provided and not network/asset/base64
            val finalBitmap = if (bitmap != null && color != null && iconSource == "defaultIcon") {
                applyColorTint(bitmap, color)
            } else {
                bitmap
            }
            
            // Cache the result
            finalBitmap?.let {
                iconCache.put(cacheKey, it)
                loadingCache.remove(cacheKey)
            }
            
            finalBitmap
        } catch (e: Exception) {
            android.util.Log.e("IconLoader", "Error loading icon: ${e.message}", e)
            loadingCache.remove(generateCacheKey(iconSource, iconData, width, height, color))
            createDefaultIcon(width, height, color)
        }
    }
    
    /**
     * Load icon from network URL
     */
    private suspend fun loadFromNetwork(url: String?, width: Int, height: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        
        return try {
            val inputStream = URL(url).openConnection().getInputStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            resizeBitmap(bitmap, width, height)
        } catch (e: Exception) {
            android.util.Log.e("IconLoader", "Error loading from network: ${e.message}", e)
            null
        }
    }
    
    /**
     * Load icon from asset path
     */
    private suspend fun loadFromAsset(assetPath: String?, width: Int, height: Int): Bitmap? {
        if (assetPath.isNullOrBlank()) return null
        
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            resizeBitmap(bitmap, width, height)
        } catch (e: Exception) {
            android.util.Log.e("IconLoader", "Error loading from asset: ${e.message}", e)
            null
        }
    }
    
    /**
     * Load icon from base64 string
     */
    private suspend fun loadFromBase64(base64String: String?, width: Int, height: Int): Bitmap? {
        if (base64String.isNullOrBlank()) return null
        
        return try {
            val imageBytes = Base64.decode(base64String, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            resizeBitmap(bitmap, width, height)
        } catch (e: Exception) {
            android.util.Log.e("IconLoader", "Error loading from base64: ${e.message}", e)
            null
        }
    }
    
    /**
     * Create default marker icon
     */
    private fun createDefaultIcon(width: Int, height: Int, color: Int?): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // Default color is blue, or use provided color
        val iconColor = color ?: 0xFF2196F3.toInt()
        paint.color = iconColor
        
        // Draw a simple circle as default icon
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.8f
        
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        // Add a white border
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        return bitmap
    }
    
    /**
     * Apply color tint to bitmap
     */
    private fun applyColorTint(bitmap: Bitmap, color: Int): Bitmap {
        val tintedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(tintedBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return tintedBitmap
    }
    
    /**
     * Resize bitmap to specified dimensions
     */
    private fun resizeBitmap(bitmap: Bitmap?, width: Int, height: Int): Bitmap? {
        if (bitmap == null) return null
        if (bitmap.width == width && bitmap.height == height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
    
    /**
     * Generate cache key for icon
     */
    private fun generateCacheKey(
        iconSource: String,
        iconData: String?,
        width: Int,
        height: Int,
        color: Int?
    ): String {
        return "${iconSource}_${iconData}_${width}_${height}_${color}"
    }
    
    /**
     * Clear the icon cache
     */
    fun clearCache() {
        iconCache.evictAll()
        loadingCache.clear()
    }
}

