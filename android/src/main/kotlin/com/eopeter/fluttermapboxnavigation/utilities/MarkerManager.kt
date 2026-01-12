package com.eopeter.fluttermapboxnavigation.utilities

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
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
        // Load custom icon from assets once.
        val iconBitmap = customIconBitmap ?: run {
            val iconSource = batch.firstOrNull()?.get("iconSource") as? String ?: "assetPath"
            val iconData = batch.firstOrNull()?.get("iconData") as? String ?: "images/marker_car.png"
            val iconWidth = (batch.firstOrNull()?.get("iconWidth") as? Number)?.toInt() ?: 40
            val iconHeight = (batch.firstOrNull()?.get("iconHeight") as? Number)?.toInt() ?: 40
            
            android.util.Log.d("MarkerManager", "Loading custom icon from assets")
            android.util.Log.d("MarkerManager", "  iconSource: $iconSource, iconData: $iconData")
            android.util.Log.d("MarkerManager", "  iconWidth: $iconWidth, iconHeight: $iconHeight")
            
            val loaded = withContext(Dispatchers.IO) {
                iconLoader.loadIcon(iconSource, iconData, iconWidth, iconHeight, null)
            }
            
            if (loaded != null) {
                android.util.Log.d("MarkerManager", "✅ Icon bitmap loaded successfully (${loaded.width}x${loaded.height})")
                customIconBitmap = loaded
                loaded
            } else {
                android.util.Log.w("MarkerManager", "⚠️ Failed to load icon bitmap from assets")
                null
            }
        }
        
        // Create view annotations on main thread
        Handler(Looper.getMainLooper()).post {
            android.util.Log.w("MarkerManager", "📍 Creating ${batch.size} view annotations on map")
            
            try {
                batch.forEach { markerData ->
                    val id = markerData["id"] as? String ?: return@forEach
                    val latitude = (markerData["latitude"] as? Number)?.toDouble() ?: return@forEach
                    val longitude = (markerData["longitude"] as? Number)?.toDouble() ?: return@forEach
                    val title = markerData["title"] as? String
                    val subtitle = markerData["subtitle"] as? String
                    
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
                            if (iconBitmap != null) {
                                // Use the loaded icon bitmap
                                markerImageView.setImageBitmap(iconBitmap)
                                markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                                android.util.Log.d("MarkerManager", "✅ Set custom icon bitmap on ImageView")
                            } else {
                                // Create and set default colored marker bitmap
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
                    
                    if (markerImageView != null) {
                        if (iconBitmap != null) {
                            markerImageView.setImageBitmap(iconBitmap)
                            markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
                            android.util.Log.d("MarkerManager", "✅ Set custom icon bitmap on ImageView")
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
            val updatedMarker = existingMarker.copy(point = newPoint)
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
                    
                    if (markerImageView != null) {
                        if (iconBitmap != null) {
                            markerImageView.setImageBitmap(iconBitmap)
                            markerImageView.layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
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

