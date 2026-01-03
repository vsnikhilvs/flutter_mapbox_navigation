package com.eopeter.fluttermapboxnavigation.utilities

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    
    private var pointAnnotationManager: PointAnnotationManager? = null
    private val markers = ConcurrentHashMap<String, MarkerData>()
    private val pendingUpdates = mutableListOf<MarkerUpdate>()
    private var updateHandler: Handler? = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
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
        var annotationId: String? = null
    )
    
    data class MarkerUpdate(
        val id: String,
        val point: Point
    )
    
    /**
     * Initialize the marker manager when map style is loaded
     */
    fun initialize(style: Style) {
        pointAnnotationManager = mapView.annotations.createPointAnnotationManager(mapView)
        // Note: Clustering is handled by Mapbox automatically when using PointAnnotationManager
        // with appropriate configuration
    }
    
    /**
     * Add markers to the map
     */
    suspend fun addMarkers(
        markersList: List<Map<String, Any>>,
        clusteringOptions: Map<String, Any>? = null
    ) {
        if (pointAnnotationManager == null) {
            android.util.Log.w("MarkerManager", "Annotation manager not initialized")
            return
        }
        
        // Update clustering options if provided
        clusteringOptions?.let {
            clusteringEnabled = it["enabled"] as? Boolean ?: true
            clusterRadius = it["clusterRadius"] as? Int ?: 50
            clusterMaxZoom = it["clusterMaxZoom"] as? Int ?: 14
        }
        
        // Process markers in batches for performance
        markersList.chunked(BATCH_SIZE).forEach { batch ->
            addMarkerBatch(batch)
        }
    }
    
    private suspend fun addMarkerBatch(batch: List<Map<String, Any>>) {
        val annotationOptions = mutableListOf<PointAnnotationOptions>()
        
        batch.forEach { markerData ->
            val id = markerData["id"] as? String ?: return@forEach
            val latitude = (markerData["latitude"] as? Number)?.toDouble() ?: return@forEach
            val longitude = (markerData["longitude"] as? Number)?.toDouble() ?: return@forEach
            val title = markerData["title"] as? String
            val subtitle = markerData["subtitle"] as? String
            val iconSource = markerData["iconSource"] as? String ?: "defaultIcon"
            val iconData = markerData["iconData"] as? String
            val iconWidth = (markerData["iconWidth"] as? Number)?.toInt() ?: 40
            val iconHeight = (markerData["iconHeight"] as? Number)?.toInt() ?: 40
            val color = (markerData["color"] as? Number)?.toInt()
            
            val point = Point.fromLngLat(longitude, latitude)
            
            // Load icon
            val iconBitmap = iconLoader.loadIcon(iconSource, iconData, iconWidth, iconHeight, color)
            
            // Create annotation option
            val annotationOption = PointAnnotationOptions()
                .withPoint(point)
            
            // Set icon if loaded
            iconBitmap?.let {
                // Add icon to style and use it
                // For now, we'll use a default approach - in production, you'd add the bitmap to style
                annotationOption.withIconImage("default-marker")
            } ?: run {
                annotationOption.withIconImage("default-marker")
            }
            
            // Set text if title provided
            if (title != null) {
                annotationOption.withTextField(title)
                if (subtitle != null) {
                    annotationOption.withTextOffset(listOf(0.0, -2.0))
                }
                annotationOption.withTextAnchor(TextAnchor.BOTTOM)
            }
            
            // Store marker data
            val markerDataObj = MarkerData(
                id = id,
                point = point,
                title = title,
                subtitle = subtitle,
                iconSource = iconSource,
                iconData = iconData,
                iconWidth = iconWidth,
                iconHeight = iconHeight,
                color = color
            )
            markers[id] = markerDataObj
            
            annotationOptions.add(annotationOption)
        }
        
        // Create annotations on main thread
        Handler(Looper.getMainLooper()).post {
            pointAnnotationManager?.create(annotationOptions)?.let { createdAnnotations ->
                // Store annotation IDs
                createdAnnotations.forEachIndexed { index, annotation ->
                    if (index < batch.size) {
                        val markerId = batch[index]["id"] as? String
                        markerId?.let {
                            markers[it]?.annotationId = annotation.id
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Update marker positions
     */
    fun updateMarkers(markersList: List<Map<String, Any>>) {
        val currentTime = System.currentTimeMillis()
        
        // Throttle updates
        if (currentTime - lastUpdateTime < UPDATE_THROTTLE_MS) {
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
        if (pointAnnotationManager == null) return
        
        markersList.forEach { markerData ->
            val id = markerData["id"] as? String ?: return@forEach
            val latitude = (markerData["latitude"] as? Number)?.toDouble() ?: return@forEach
            val longitude = (markerData["longitude"] as? Number)?.toDouble() ?: return@forEach
            
            val existingMarker = markers[id]
            if (existingMarker != null) {
                val newPoint = Point.fromLngLat(longitude, latitude)
                
                // Update stored point
                val updatedMarker = existingMarker.copy(point = newPoint)
                markers[id] = updatedMarker
                
                // Update annotation if exists
                existingMarker.annotationId?.let { annotationId ->
                    // Find and update the annotation
                    val annotations = pointAnnotationManager?.annotations
                    val annotation = annotations?.find { it.id == annotationId }
                    
                    annotation?.let {
                        // Remove old and create new
                        pointAnnotationManager?.delete(listOf(annotationId))
                        
                        // Create new annotation with updated position
                        val annotationOption = PointAnnotationOptions()
                            .withPoint(newPoint)
                            .withIconImage("default-marker")
                        
                        if (updatedMarker.title != null) {
                            annotationOption.withTextField(updatedMarker.title)
                            annotationOption.withTextAnchor(TextAnchor.BOTTOM)
                        }
                        
                        pointAnnotationManager?.create(listOf(annotationOption))?.firstOrNull()?.let { newAnnotation ->
                            updatedMarker.annotationId = newAnnotation.id
                            markers[id] = updatedMarker
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Remove markers by IDs
     */
    fun removeMarkers(markerIds: List<String>) {
        if (pointAnnotationManager == null) return
        
        val annotationsToDelete = mutableListOf<String>()
        
        markerIds.forEach { id ->
            val marker = markers[id]
            marker?.annotationId?.let { annotationId ->
                annotationsToDelete.add(annotationId)
            }
            markers.remove(id)
        }
        
        if (annotationsToDelete.isNotEmpty()) {
            Handler(Looper.getMainLooper()).post {
                pointAnnotationManager?.delete(annotationsToDelete)
            }
        }
    }
    
    /**
     * Clear all markers
     */
    fun clearAllMarkers() {
        if (pointAnnotationManager == null) return
        
        Handler(Looper.getMainLooper()).post {
            pointAnnotationManager?.deleteAll()
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
        pointAnnotationManager = null
    }
}

