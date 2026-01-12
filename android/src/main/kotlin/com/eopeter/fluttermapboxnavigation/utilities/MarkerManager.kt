package com.eopeter.fluttermapboxnavigation.utilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.graphics.Shader
import android.util.LruCache
import android.widget.ImageView
import android.view.ViewGroup
import com.eopeter.fluttermapboxnavigation.R
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages markers/annotations on the Mapbox map with support for:
 * - Custom icons (network, asset, base64)
 * - Clustering
 * - Batch operations for performance
 * - Update throttling
 */
class MarkerManager(
    private val context: Context,
    private val mapView: MapView,
    private val iconLoader: IconLoader
) {
    
    companion object {
        private const val BATCH_SIZE = 10
        private const val UPDATE_THROTTLE_MS = 100L
        private const val POSITION_CHANGE_THRESHOLD_METERS = 5.0
        private const val CLUSTER_ENABLED_MARKER_COUNT = 10
    }
    
    // Use ViewAnnotationManager instead of PointAnnotationManager for NavigationView compatibility
    private val viewAnnotationMap = mutableMapOf<String, View>() // Map marker ID to View
    private val markers = ConcurrentHashMap<String, MarkerData>()
    private val pendingUpdates = mutableListOf<MarkerUpdate>()
    private var updateHandler: Handler? = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var customIconBitmap: Bitmap? = null // Cache the custom icon bitmap for direct usage
    private var isInitialized = false
    private val avatarMarkerCache = LruCache<String, Bitmap>(100)

    /**
     * Create an ImageView for a marker with the specified bitmap
     */
    private fun createMarkerImageView(bitmap: Bitmap, width: Int, height: Int): ImageView {
        val imageView = ImageView(context)
        imageView.setImageBitmap(bitmap)
        imageView.layoutParams = ViewGroup.LayoutParams(width, height)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        return imageView
    }
    
    /**
     * Create a default colored circle marker bitmap when asset loading fails
     */
    private fun createDefaultMarkerBitmap(width: Int, height: Int, color: Int): Bitmap {
        // Create a simple colored circle bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color
            style = android.graphics.Paint.Style.FILL
        }
        // Draw a circle
        val radius = (minOf(width, height) / 2).toFloat() - 2
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        
        // Add a white border
        val borderPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(width / 2f, height / 2f, radius, borderPaint)
        
        return bitmap
    }

    private fun createAvatarMarkerBitmap(sizePx: Int, markerColor: Int, avatarBitmap: Bitmap): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        val radius = cx - 2f
        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = markerColor
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = android.graphics.Color.WHITE
            strokeWidth = 3f
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val inset = borderPaint.strokeWidth + 2f
        val avatarRadius = (radius - inset).coerceAtLeast(1f)

        val shader = BitmapShader(avatarBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()
        // Center-crop the avatar into the inner circle
        val srcW = avatarBitmap.width.toFloat().coerceAtLeast(1f)
        val srcH = avatarBitmap.height.toFloat().coerceAtLeast(1f)
        val dst = avatarRadius * 2f
        val scale = maxOf(dst / srcW, dst / srcH)
        val dx = cx - (srcW * scale) / 2f
        val dy = cy - (srcH * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)

        val avatarPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            this.shader = shader
        }
        canvas.drawCircle(cx, cy, avatarRadius, avatarPaint)

        return bitmap
    }

    private fun avatarCacheKey(url: String, size: Int, markerColor: Int): String {
        return "avatar:$url:$size:$markerColor"
    }

    private fun applyAvatarMarkerAsync(
        markerImageView: ImageView,
        avatarUrl: String,
        size: Int,
        markerColor: Int
    ) {
        val key = avatarCacheKey(avatarUrl, size, markerColor)
        avatarMarkerCache.get(key)?.let { cached ->
            android.util.Log.w("MarkerManager", "👤 avatarUrl cache hit: $avatarUrl (size=$size)")
            markerImageView.setImageBitmap(cached)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.w("MarkerManager", "👤 Loading avatarUrl: $avatarUrl (size=$size)")
                val avatar = iconLoader.loadIcon("networkUrl", avatarUrl, size, size, null)
                if (avatar != null) {
                    val composed = createAvatarMarkerBitmap(size, markerColor, avatar)
                    avatarMarkerCache.put(key, composed)
                    withContext(Dispatchers.Main) {
                        android.util.Log.w("MarkerManager", "👤 ✅ Avatar loaded & applied for url=$avatarUrl")
                        markerImageView.setImageBitmap(composed)
                    }
                } else {
                    android.util.Log.w("MarkerManager", "👤 ❌ Avatar load returned null for url=$avatarUrl")
                }
            } catch (e: Exception) {
                android.util.Log.w("MarkerManager", "⚠️ Failed to load avatarUrl=$avatarUrl: ${e.message}")
            }
        }
    }

    private fun applyIconMarkerAsync(
        markerImageView: ImageView,
        iconSource: String,
        iconData: String?,
        iconWidth: Int,
        iconHeight: Int,
        markerColor: Int,
        isProfilePicture: Boolean
    ) {
        // For the default participant icon we preload, use the cached bitmap (only for that specific asset).
        if (iconSource == "assetPath" && iconData == "images/marker_car.png") {
            customIconBitmap?.let { bmp ->
                markerImageView.setImageBitmap(bmp)
                markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                markerImageView.clipToOutline = false
                markerImageView.outlineProvider = null
                return
            }
        }

        // Placeholder immediately
        markerImageView.setImageBitmap(createDefaultMarkerBitmap(iconWidth, iconHeight, markerColor))
        markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tintColor = if (iconSource == "defaultIcon") markerColor else null
                val bmp = iconLoader.loadIcon(iconSource, iconData, iconWidth, iconHeight, tintColor)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        markerImageView.setImageBitmap(bmp)
                    } else {
                        markerImageView.setImageBitmap(createDefaultMarkerBitmap(iconWidth, iconHeight, markerColor))
                    }

                    if (isProfilePicture) {
                        markerImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        markerImageView.clipToOutline = true
                        markerImageView.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                val size = minOf(view.width, view.height)
                                outline.setOval(0, 0, size, size)
                            }
                        }
                    } else {
                        markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                        markerImageView.clipToOutline = false
                        markerImageView.outlineProvider = null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MarkerManager", "⚠️ Failed to load iconSource=$iconSource iconData=$iconData: ${e.message}")
            }
        }
    }
    
    /**
     * Create a default colored circle marker ImageView when asset loading fails
     */
    private fun createDefaultColoredMarker(width: Int, height: Int, color: Int): ImageView {
        val bitmap = createDefaultMarkerBitmap(width, height, color)
        val imageView = ImageView(context)
        imageView.setImageBitmap(bitmap)
        imageView.layoutParams = ViewGroup.LayoutParams(width, height)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        return imageView
    }
    
    // Clustering options
    private var clusteringEnabled = true
    private var clusterRadius = 50
    private var clusterMaxZoom = 14
    
    // Track last update time for throttling
    private var lastUpdateTime = 0L
    
    data class MarkerData(
        val id: String,
        val point: Point,
        val title: String?,
        val subtitle: String?,
        val iconSource: String,
        val iconData: String?,
        val avatarUrl: String?,
        val iconWidth: Int,
        val iconHeight: Int,
        val color: Int?,
        var annotationId: String? = null // Store as String for compatibility
    )
    
    data class MarkerUpdate(
        val id: String,
        val point: Point
    )
    
    /**
     * Initialize the marker manager when map style is loaded
     * Uses ViewAnnotationManager which is compatible with NavigationView
     * @return true if initialization succeeded, false otherwise
     */
    fun initialize(style: Style): Boolean {
        android.util.Log.w("MarkerManager", "=================================================")
        android.util.Log.w("MarkerManager", "🚀 Initializing MarkerManager with ViewAnnotationManager")
        android.util.Log.w("MarkerManager", "   Style: ${style.styleURI}")
        android.util.Log.w("MarkerManager", "   Using ViewAnnotationManager (NavigationView compatible)")
        
        try {
            // ViewAnnotationManager is always available on MapView
            // No plugin registration needed - it's built into the MapView
            val viewAnnotationManager = mapView.viewAnnotationManager
            android.util.Log.e("MarkerManager", "🔍 DEBUG: viewAnnotationManager = $viewAnnotationManager")
            
            if (viewAnnotationManager == null) {
                android.util.Log.e("MarkerManager", "❌ viewAnnotationManager is null - this should not happen")
                android.util.Log.w("MarkerManager", "=================================================")
                isInitialized = false
                return false
            }
            
            // Load a default participant icon bitmap immediately so updateMarkers can self-heal
            // (create missing markers) even if addMarkers was never called.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bmp = iconLoader.loadIcon("assetPath", "images/marker_car.png", 40, 40, null)
                    if (bmp != null) {
                        customIconBitmap = bmp
                        android.util.Log.w("MarkerManager", "✅ Preloaded default participant icon bitmap (${bmp.width}x${bmp.height})")
                    } else {
                        android.util.Log.w("MarkerManager", "⚠️ Failed to preload default participant icon bitmap")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MarkerManager", "❌ Exception preloading default participant icon bitmap: ${e.message}", e)
                }
            }
            
            isInitialized = true
            android.util.Log.w("MarkerManager", "✅ MarkerManager initialized successfully with ViewAnnotationManager")
            android.util.Log.w("MarkerManager", "=================================================")
            return true
        } catch (e: Exception) {
            android.util.Log.e("MarkerManager", "❌ Failed to initialize MarkerManager: ${e.message}", e)
            android.util.Log.e("MarkerManager", "Stack trace: ${e.stackTraceToString()}")
            android.util.Log.w("MarkerManager", "=================================================")
            isInitialized = false
            return false
        }
    }
    
    /**
     * Check if the marker manager is properly initialized
     * @return true if ViewAnnotationManager is available, false otherwise
     */
    fun isInitialized(): Boolean {
        return isInitialized && mapView.viewAnnotationManager != null
    }
    
    /**
     * Add markers to the map using ViewAnnotationManager
     */
    suspend fun addMarkers(
        markersList: List<Map<String, Any>>,
        clusteringOptions: Map<String, Any>? = null
    ) {
        // Use WARN so it surfaces in flutter run output (debug logs are often suppressed).
        android.util.Log.w("MarkerManager", "=================================================")
        android.util.Log.w("MarkerManager", "📍 Adding ${markersList.size} markers using ViewAnnotationManager")
        
        val viewAnnotationManager = mapView.viewAnnotationManager
        if (viewAnnotationManager == null) {
            android.util.Log.e("MarkerManager", "❌ ViewAnnotationManager not available")
            return
        }
        
        // Update clustering options if provided (noted for future implementation)
        clusteringOptions?.let {
            clusteringEnabled = it["enabled"] as? Boolean ?: true
            clusterRadius = it["clusterRadius"] as? Int ?: 50
            clusterMaxZoom = it["clusterMaxZoom"] as? Int ?: 14
        }
        
        // Log each marker
        markersList.forEach { marker ->
            android.util.Log.d("MarkerManager", "Marker: ID=${marker["id"]}, Lat=${marker["latitude"]}, Lng=${marker["longitude"]}, Title=${marker["title"]}")
        }
        
        // Process markers in batches for performance
        markersList.chunked(BATCH_SIZE).forEach { batch ->
            addMarkerBatch(batch)
        }
        
        android.util.Log.w("MarkerManager", "✅ Markers add request processed")
        android.util.Log.w("MarkerManager", "=================================================")
    }
    
    private suspend fun addMarkerBatch(
        batch: List<Map<String, Any>>
    ) {
        val viewAnnotationManager = mapView.viewAnnotationManager
        if (viewAnnotationManager == null) {
            android.util.Log.e("MarkerManager", "❌ ViewAnnotationManager is null")
            return
        }
        
        // Create view annotations on main thread
        Handler(Looper.getMainLooper()).post {
            android.util.Log.w("MarkerManager", "📍 Creating ${batch.size} view annotations on map")
            
            try {
                batch.forEach { markerData ->
                    android.util.Log.d("MarkerManager", "markerData keys: ${markerData.keys}")
                    val id = markerData["id"] as? String ?: return@forEach
                    val latitude = (markerData["latitude"] as? Number)?.toDouble() ?: return@forEach
                    val longitude = (markerData["longitude"] as? Number)?.toDouble() ?: return@forEach
                    val title = markerData["title"] as? String
                    val subtitle = markerData["subtitle"] as? String
                    val avatarUrlDbg = (markerData["avatarUrl"] as? String)?.trim()
                    android.util.Log.w("MarkerManager", "📍 addMarkerBatch marker id=$id avatarUrl=$avatarUrlDbg iconSource=${markerData["iconSource"]}")
                    
                    val point = Point.fromLngLat(longitude, latitude)
                    
                    // Skip if marker already exists
                    if (viewAnnotationMap.containsKey(id)) {
                        android.util.Log.d("MarkerManager", "Marker $id already exists, skipping")
                        return@forEach
                    }
                    
                    // Get marker dimensions and color
                    val iconWidth = (markerData["iconWidth"] as? Number)?.toInt() ?: 40
                    val iconHeight = (markerData["iconHeight"] as? Number)?.toInt() ?: 40
                    val markerColor = (markerData["color"] as? Number)?.toInt() ?: android.graphics.Color.BLUE
                    
                    // Use ViewAnnotationManager with the layout resource
                    try {
                        // Add view annotation using the layout resource
                        val viewAnnotation = viewAnnotationManager.addViewAnnotation(
                            resId = R.layout.marker_view_annotation,
                            options = viewAnnotationOptions {
                                geometry(point)
                                offsetY(-iconHeight / 2) // Center the marker on the point
                            }
                        )
                        
                        // Get the ImageView from the inflated layout
                        // Since the layout root IS the ImageView, we can cast it directly or use findViewById
                        val markerImageView = if (viewAnnotation is ImageView) {
                            viewAnnotation as ImageView
                        } else {
                            viewAnnotation.findViewById<ImageView>(R.id.marker_image)
                        }
                        
                        if (markerImageView != null) {
                            val iconSource = markerData["iconSource"] as? String ?: "defaultIcon"
                            val iconData = markerData["iconData"] as? String
                            val isProfilePicture = iconSource == "networkUrl" || iconSource == "base64"
                            val avatarUrl = (markerData["avatarUrl"] as? String)?.trim()
                            val size = minOf(iconWidth, iconHeight)

                            if (!avatarUrl.isNullOrEmpty()) {
                                // Participants with avatar: draw a circular dot marker with avatar inside.
                                markerImageView.setImageBitmap(createDefaultMarkerBitmap(size, size, markerColor))
                                markerImageView.layoutParams = ViewGroup.LayoutParams(size, size)
                                markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                                markerImageView.clipToOutline = false
                                markerImageView.outlineProvider = null
                                applyAvatarMarkerAsync(markerImageView, avatarUrl, size, markerColor)
                            } else {
                                // Load per-marker icon (network/base64/asset/default) so each marker can differ.
                                applyIconMarkerAsync(
                                    markerImageView = markerImageView,
                                    iconSource = iconSource,
                                    iconData = iconData,
                                    iconWidth = iconWidth,
                                    iconHeight = iconHeight,
                                    markerColor = markerColor,
                                    isProfilePicture = isProfilePicture
                                )
                            }
                        } else {
                            android.util.Log.e("MarkerManager", "❌ Could not get ImageView from view annotation")
                            android.util.Log.e("MarkerManager", "   viewAnnotation type: ${viewAnnotation.javaClass.name}")
                        }
                        
                        // Store the view annotation for later updates/removal
                        viewAnnotationMap[id] = viewAnnotation
                        
                        android.util.Log.w("MarkerManager", "   ✅ View annotation created successfully using layout resource")
                    } catch (e: Exception) {
                        android.util.Log.e("MarkerManager", "❌ Failed to create view annotation: ${e.message}", e)
                        android.util.Log.e("MarkerManager", "Stack trace: ${e.stackTraceToString()}")
                    }
                    
                    // Store marker data
                    val markerDataObj = MarkerData(
                        id = id,
                        point = point,
                        title = title,
                        subtitle = subtitle,
                        iconSource = markerData["iconSource"] as? String ?: "assetPath",
                        iconData = markerData["iconData"] as? String ?: "images/marker_car.png",
                        avatarUrl = (markerData["avatarUrl"] as? String)?.trim(),
                        iconWidth = iconWidth,
                        iconHeight = iconHeight,
                        color = (markerData["color"] as? Number)?.toInt(),
                        annotationId = id // Use marker ID as annotation ID for view annotations
                    )
                    markers[id] = markerDataObj
                    
                    android.util.Log.w("MarkerManager", "   ✅ View annotation created: markerID=$id")
                    android.util.Log.w("MarkerManager", "      Position: $latitude, $longitude")
                }
                
                android.util.Log.w("MarkerManager", "✅ View annotations created successfully")
            } catch (e: Exception) {
                android.util.Log.e("MarkerManager", "❌ Exception while creating view annotations: ${e.message}", e)
                android.util.Log.e("MarkerManager", "Stack trace: ${e.stackTraceToString()}")
            }
        }
    }
    
    /**
     * Update marker positions
     */
    fun updateMarkers(markersList: List<Map<String, Any>>) {
        android.util.Log.d("MarkerManager", "=================================================")
        android.util.Log.d("MarkerManager", "🔄 Updating ${markersList.size} markers")
        
        val currentTime = System.currentTimeMillis()
        
        // Throttle updates
        if (currentTime - lastUpdateTime < UPDATE_THROTTLE_MS) {
            android.util.Log.d("MarkerManager", "⏱️ Throttling update (too soon since last update)")
            // Queue update
            markersList.forEach { markerData ->
                val id = markerData["id"] as? String ?: return@forEach
                val latitude = (markerData["latitude"] as? Number)?.toDouble() ?: return@forEach
                val longitude = (markerData["longitude"] as? Number)?.toDouble() ?: return@forEach
                val point = Point.fromLngLat(longitude, latitude)
                
                // Check if position changed significantly
                val existingMarker = markers[id]
                if (existingMarker != null) {
                    val distance = calculateDistance(
                        existingMarker.point.latitude(),
                        existingMarker.point.longitude(),
                        latitude,
                        longitude
                    )
                    
                    if (distance > POSITION_CHANGE_THRESHOLD_METERS) {
                        pendingUpdates.add(MarkerUpdate(id, point))
                    }
                }
            }
            
            // Schedule batched update
            scheduleBatchedUpdate()
            return
        }
        
        lastUpdateTime = currentTime
        
        // Process updates immediately
        processMarkerUpdates(markersList)
    }
    
    private fun scheduleBatchedUpdate() {
        updateRunnable?.let { updateHandler?.removeCallbacks(it) }
        
        updateRunnable = Runnable {
            if (pendingUpdates.isNotEmpty()) {
                val updates = pendingUpdates.toList()
                pendingUpdates.clear()
                
                CoroutineScope(Dispatchers.Main).launch {
                    processMarkerUpdates(updates.map { update ->
                        mapOf(
                            "id" to update.id,
                            "latitude" to update.point.latitude(),
                            "longitude" to update.point.longitude()
                        )
                    })
                }
            }
        }
        
        updateHandler?.postDelayed(updateRunnable!!, UPDATE_THROTTLE_MS)
    }
    
    private fun processMarkerUpdates(markersList: List<Map<String, Any>>) {
        val viewAnnotationManager = mapView.viewAnnotationManager
        if (viewAnnotationManager == null) return
        
        markersList.forEach { markerData ->
            val id = markerData["id"] as? String ?: return@forEach
            val latitude = (markerData["latitude"] as? Number)?.toDouble() ?: return@forEach
            val longitude = (markerData["longitude"] as? Number)?.toDouble() ?: return@forEach
            
            val existingMarker = markers[id]
            val newPoint = Point.fromLngLat(longitude, latitude)

            // If marker doesn't exist yet, create it (self-healing)
            if (existingMarker == null) {
                android.util.Log.w("MarkerManager", "🆕 updateMarkers: marker '$id' missing -> creating view annotation")
                // Create the marker using the same logic as addMarkers
                val iconWidth = (markerData["iconWidth"] as? Number)?.toInt() ?: 40
                val iconHeight = (markerData["iconHeight"] as? Number)?.toInt() ?: 40
                val iconSource = markerData["iconSource"] as? String ?: "assetPath"
                val iconData = markerData["iconData"] as? String ?: "images/marker_car.png"
                val avatarUrl = (markerData["avatarUrl"] as? String)?.trim()
                
                // Load icon if not cached
                val iconBitmap = customIconBitmap ?: run {
                    // Load icon using runBlocking since we're in a non-suspend context
                    // This is acceptable since updateMarkers is called less frequently
                    try {
                        runBlocking {
                            iconLoader.loadIcon(iconSource, iconData, iconWidth, iconHeight, null)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MarkerManager", "❌ Failed to load icon in updateMarkers: ${e.message}", e)
                        null
                    }
                }
                
                // Cache the icon if we just loaded it
                if (iconBitmap != null && customIconBitmap == null) {
                    customIconBitmap = iconBitmap
                }
                
                try {
                    val viewAnnotation = viewAnnotationManager.addViewAnnotation(
                        resId = R.layout.marker_view_annotation,
                        options = viewAnnotationOptions {
                            geometry(newPoint)
                            offsetY(-iconHeight / 2)
                        }
                    )
                    
                    // Get ImageView - the layout root IS the ImageView, so cast directly or use findViewById
                    val markerImageView = if (viewAnnotation is ImageView) {
                        viewAnnotation as ImageView
                    } else {
                        viewAnnotation.findViewById<ImageView>(R.id.marker_image)
                    }
                    val markerColor = (markerData["color"] as? Number)?.toInt() ?: android.graphics.Color.BLUE
                    
                    val isProfilePicture = iconSource == "networkUrl" || iconSource == "base64"
                    val size = minOf(iconWidth, iconHeight)
                    
                    if (markerImageView != null) {
                        if (!avatarUrl.isNullOrEmpty()) {
                            markerImageView.setImageBitmap(createDefaultMarkerBitmap(size, size, markerColor))
                            markerImageView.layoutParams = ViewGroup.LayoutParams(size, size)
                            markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                            markerImageView.clipToOutline = false
                            markerImageView.outlineProvider = null
                            applyAvatarMarkerAsync(markerImageView, avatarUrl, size, markerColor)
                        } else if (iconBitmap != null) {
                            markerImageView.setImageBitmap(iconBitmap)
                            markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                            
                            // Make profile pictures circular
                            if (isProfilePicture) {
                                markerImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                                markerImageView.clipToOutline = true
                                markerImageView.outlineProvider = object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        val size = minOf(view.width, view.height)
                                        outline.setOval(0, 0, size, size)
                                    }
                                }
                                android.util.Log.d("MarkerManager", "✅ Set profile picture bitmap (circular) on ImageView")
                            } else {
                                markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                                android.util.Log.d("MarkerManager", "✅ Set custom icon bitmap on ImageView")
                            }
                        } else {
                            // Fallback: create a default colored marker
                            android.util.Log.w("MarkerManager", "⚠️ Creating default colored marker (asset not found)")
                            val defaultBitmap = createDefaultMarkerBitmap(iconWidth, iconHeight, markerColor)
                            markerImageView.setImageBitmap(defaultBitmap)
                            markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                            android.util.Log.d("MarkerManager", "✅ Set default colored marker bitmap on ImageView")
                        }
                    } else {
                        android.util.Log.e("MarkerManager", "❌ Could not get ImageView from view annotation")
                        android.util.Log.e("MarkerManager", "   viewAnnotation type: ${viewAnnotation.javaClass.name}")
                    }
                    
                    viewAnnotationMap[id] = viewAnnotation
                    
                    markers[id] = MarkerData(
                        id = id,
                        point = newPoint,
                        title = markerData["title"] as? String,
                        subtitle = markerData["subtitle"] as? String,
                        iconSource = iconSource,
                        iconData = iconData,
                        avatarUrl = avatarUrl,
                        iconWidth = iconWidth,
                        iconHeight = iconHeight,
                        color = markerColor,
                        annotationId = id
                    )
                    android.util.Log.w("MarkerManager", "✅ updateMarkers: created view annotation for '$id'")
                } catch (e: Exception) {
                    android.util.Log.e("MarkerManager", "❌ Failed to create view annotation in updateMarkers: ${e.message}", e)
                }
                return@forEach
            }

            // Update stored point
            val updatedMarker = existingMarker.copy(
                point = newPoint,
                avatarUrl = (markerData["avatarUrl"] as? String)?.trim() ?: existingMarker.avatarUrl
            )
            markers[id] = updatedMarker

            // Update view annotation position if it exists
            val viewAnnotation = viewAnnotationMap[id]
            if (viewAnnotation == null) {
                android.util.Log.w("MarkerManager", "🆕 updateMarkers: marker '$id' has no live view annotation -> creating")
                // Recreate the view annotation
                val iconWidth = existingMarker.iconWidth
                val iconHeight = existingMarker.iconHeight
                
                // Load icon if not cached (use existing marker's iconSource/iconData)
                val iconBitmap = customIconBitmap ?: run {
                    try {
                        runBlocking {
                            iconLoader.loadIcon(existingMarker.iconSource, existingMarker.iconData, iconWidth, iconHeight, null)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MarkerManager", "❌ Failed to load icon when recreating: ${e.message}", e)
                        null
                    }
                }
                
                // Cache the icon if we just loaded it
                if (iconBitmap != null && customIconBitmap == null) {
                    customIconBitmap = iconBitmap
                }
                
                try {
                    val newViewAnnotation = viewAnnotationManager.addViewAnnotation(
                        resId = R.layout.marker_view_annotation,
                        options = viewAnnotationOptions {
                            geometry(newPoint)
                            offsetY(-iconHeight / 2)
                        }
                    )
                    
                    // Get ImageView - the layout root IS the ImageView, so cast directly or use findViewById
                    val markerImageView = if (newViewAnnotation is ImageView) {
                        newViewAnnotation as ImageView
                    } else {
                        newViewAnnotation.findViewById<ImageView>(R.id.marker_image)
                    }
                    val markerColor = existingMarker.color ?: android.graphics.Color.BLUE
                    
                    val isProfilePicture = existingMarker.iconSource == "networkUrl" || existingMarker.iconSource == "base64"
                    val size = minOf(iconWidth, iconHeight)
                    
                    if (markerImageView != null) {
                        if (!existingMarker.avatarUrl.isNullOrEmpty()) {
                            markerImageView.setImageBitmap(createDefaultMarkerBitmap(size, size, markerColor))
                            markerImageView.layoutParams = ViewGroup.LayoutParams(size, size)
                            markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                            markerImageView.clipToOutline = false
                            markerImageView.outlineProvider = null
                            applyAvatarMarkerAsync(markerImageView, existingMarker.avatarUrl!!, size, markerColor)
                        } else if (iconBitmap != null) {
                            markerImageView.setImageBitmap(iconBitmap)
                            markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                            
                            // Make profile pictures circular
                            if (isProfilePicture) {
                                markerImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                                markerImageView.clipToOutline = true
                                markerImageView.outlineProvider = object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        val size = minOf(view.width, view.height)
                                        outline.setOval(0, 0, size, size)
                                    }
                                }
                                android.util.Log.d("MarkerManager", "✅ Set profile picture bitmap (circular) when recreating")
                            } else {
                                markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                                android.util.Log.d("MarkerManager", "✅ Set custom icon bitmap when recreating")
                            }
                        } else {
                            // Fallback: create a default colored marker
                            android.util.Log.w("MarkerManager", "⚠️ Using default colored marker when recreating (asset not found)")
                            val defaultBitmap = createDefaultMarkerBitmap(iconWidth, iconHeight, markerColor)
                            markerImageView.setImageBitmap(defaultBitmap)
                            markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                        }
                    } else {
                        android.util.Log.e("MarkerManager", "❌ Could not get ImageView from recreated view annotation")
                    }
                    
                    viewAnnotationMap[id] = newViewAnnotation
                    android.util.Log.w("MarkerManager", "✅ updateMarkers: recreated view annotation for '$id'")
                } catch (e: Exception) {
                    android.util.Log.e("MarkerManager", "❌ Failed to recreate view annotation: ${e.message}", e)
                }
                return@forEach
            }

            // If avatarUrl is provided/changed, update the marker image (async) as well.
            val newAvatarUrl = (markerData["avatarUrl"] as? String)?.trim()
            if (!newAvatarUrl.isNullOrEmpty() && newAvatarUrl != existingMarker.avatarUrl) {
                val markerImageView = if (viewAnnotation is ImageView) {
                    viewAnnotation as ImageView
                } else {
                    viewAnnotation.findViewById<ImageView>(R.id.marker_image)
                }
                if (markerImageView != null) {
                    val markerColor = existingMarker.color ?: android.graphics.Color.BLUE
                    val size = minOf(existingMarker.iconWidth, existingMarker.iconHeight)
                    markerImageView.setImageBitmap(createDefaultMarkerBitmap(size, size, markerColor))
                    markerImageView.layoutParams = ViewGroup.LayoutParams(size, size)
                    markerImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    markerImageView.clipToOutline = false
                    markerImageView.outlineProvider = null
                    applyAvatarMarkerAsync(markerImageView, newAvatarUrl, size, markerColor)
                }
            }

            // Update the view annotation position
            try {
                viewAnnotationManager.updateViewAnnotation(
                    viewAnnotation,
                    viewAnnotationOptions {
                        geometry(newPoint)
                        offsetY(-existingMarker.iconHeight / 2)
                    }
                )
                android.util.Log.d("MarkerManager", "✅ Updated view annotation position for '$id'")
            } catch (e: Exception) {
                android.util.Log.e("MarkerManager", "❌ Failed to update view annotation position: ${e.message}", e)
                // Fallback: remove and recreate
                try {
                    viewAnnotationManager.removeViewAnnotation(viewAnnotation)
                    viewAnnotationMap.remove(id)
                    // Recreate will happen on next update
                } catch (e2: Exception) {
                    android.util.Log.e("MarkerManager", "❌ Failed to remove view annotation: ${e2.message}", e2)
                }
            }
        }
    }
    
    /**
     * Remove markers by IDs
     */
    fun removeMarkers(markerIds: List<String>) {
        val viewAnnotationManager = mapView.viewAnnotationManager
        if (viewAnnotationManager == null) return
        
        Handler(Looper.getMainLooper()).post {
            markerIds.forEach { id ->
                val viewAnnotation = viewAnnotationMap.remove(id)
                if (viewAnnotation != null) {
                    try {
                        viewAnnotationManager.removeViewAnnotation(viewAnnotation)
                        android.util.Log.d("MarkerManager", "✅ Removed view annotation for marker '$id'")
                    } catch (e: Exception) {
                        android.util.Log.e("MarkerManager", "❌ Failed to remove view annotation: ${e.message}", e)
                    }
                }
                markers.remove(id)
            }
        }
    }
    
    /**
     * Clear all markers
     */
    fun clearAllMarkers() {
        val viewAnnotationManager = mapView.viewAnnotationManager
        if (viewAnnotationManager == null) return
        
        Handler(Looper.getMainLooper()).post {
            viewAnnotationManager.removeAllViewAnnotations()
            viewAnnotationMap.clear()
        }
        markers.clear()
        pendingUpdates.clear()
        iconLoader.clearCache()
        avatarMarkerCache.evictAll()
    }
    
    /**
     * Set clustering options
     */
    fun setClusteringOptions(
        enabled: Boolean,
        radius: Int,
        maxZoom: Int
    ) {
        clusteringEnabled = enabled
        clusterRadius = radius
        clusterMaxZoom = maxZoom
        // Note: Actual clustering configuration would be set on the annotation manager
        // This is a simplified version - full implementation would configure clustering layers
    }
    
    /**
     * Calculate distance between two points in meters (Haversine formula)
     */
    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }
    
    /**
     * Cleanup resources
     */
    fun dispose() {
        updateRunnable?.let { updateHandler?.removeCallbacks(it) }
        updateHandler = null
        clearAllMarkers()
        isInitialized = false
    }
}

