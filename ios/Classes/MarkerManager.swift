import Foundation
import UIKit
import MapboxMaps
import CoreLocation

/**
 * Manages markers/annotations on the Mapbox map with support for:
 * - Custom icons (network, asset, base64)
 * - Clustering
 * - Batch operations for performance
 * - Update throttling
 */
class MarkerManager {
    private let mapView: MapView
    private let iconLoader: IconLoader
    private var pointAnnotationManager: PointAnnotationManager?
    private var markers: [String: MarkerData] = [:]
    private var pendingUpdates: [MarkerUpdate] = []
    private var updateTimer: Timer?
    
    // Clustering options
    private var clusteringEnabled = true
    private var clusterRadius = 50
    private var clusterMaxZoom = 14
    
    // Throttling
    private let updateThrottleMs: TimeInterval = 0.1 // 100ms
    private let positionChangeThresholdMeters = 5.0
    private var lastUpdateTime: Date = Date()
    
    struct MarkerData {
        let id: String
        var point: Point
        let title: String?
        let subtitle: String?
        let iconSource: String
        let iconData: String?
        let iconWidth: Int
        let iconHeight: Int
        let color: Int?
        var annotation: PointAnnotation?
    }
    
    struct MarkerUpdate {
        let id: String
        let point: Point
    }
    
    init(mapView: MapView, iconLoader: IconLoader) {
        self.mapView = mapView
        self.iconLoader = iconLoader
    }
    
    /**
     * Initialize the marker manager when map style is loaded
     */
    func initialize(style: Style) {
        pointAnnotationManager = mapView.annotations.makePointAnnotationManager()
        // Note: Clustering is handled by Mapbox automatically when configured
    }
    
    /**
     * Add markers to the map
     */
    func addMarkers(
        markersList: [[String: Any]],
        clusteringOptions: [String: Any]? = nil,
        completion: @escaping (Bool) -> Void
    ) {
        guard let annotationManager = pointAnnotationManager else {
            completion(false)
            return
        }
        
        // Update clustering options if provided
        if let clustering = clusteringOptions {
            clusteringEnabled = clustering["enabled"] as? Bool ?? true
            clusterRadius = clustering["clusterRadius"] as? Int ?? 50
            clusterMaxZoom = clustering["clusterMaxZoom"] as? Int ?? 14
        }
        
        // Process markers in batches
        let batchSize = 10
        var processedCount = 0
        let totalCount = markersList.count
        
        func processBatch(startIndex: Int) {
            let endIndex = min(startIndex + batchSize, totalCount)
            let batch = Array(markersList[startIndex..<endIndex])
            
            var annotations: [PointAnnotation] = []
            let group = DispatchGroup()
            
            for markerData in batch {
                guard let id = markerData["id"] as? String,
                      let latitude = (markerData["latitude"] as? NSNumber)?.doubleValue,
                      let longitude = (markerData["longitude"] as? NSNumber)?.doubleValue else {
                    continue
                }
                
                let title = markerData["title"] as? String
                let subtitle = markerData["subtitle"] as? String
                let iconSource = markerData["iconSource"] as? String ?? "defaultIcon"
                let iconData = markerData["iconData"] as? String
                let iconWidth = (markerData["iconWidth"] as? NSNumber)?.intValue ?? 40
                let iconHeight = (markerData["iconHeight"] as? NSNumber)?.intValue ?? 40
                let color = (markerData["color"] as? NSNumber)?.intValue
                
                let point = Point(CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
                
                group.enter()
                iconLoader.loadIcon(
                    iconSource: iconSource,
                    iconData: iconData,
                    width: iconWidth,
                    height: iconHeight,
                    color: color
                ) { [weak self] icon in
                    defer { group.leave() }
                    
                    guard let self = self else { return }
                    
                    var annotation = PointAnnotation(coordinate: point.coordinate)
                    
                    // Set icon if loaded
                    if let icon = icon {
                        // Add icon to style and use it
                        // For now, we'll use a default approach
                        annotation.image = icon
                    }
                    
                    // Set text if title provided
                    if let title = title {
                        annotation.textField = title
                        if subtitle != nil {
                            annotation.textOffset = [0, -2]
                        }
                        annotation.textAnchor = .bottom
                    }
                    
                    // Store marker data
                    let markerDataObj = MarkerData(
                        id: id,
                        point: point,
                        title: title,
                        subtitle: subtitle,
                        iconSource: iconSource,
                        iconData: iconData,
                        iconWidth: iconWidth,
                        iconHeight: iconHeight,
                        color: color,
                        annotation: nil
                    )
                    
                    DispatchQueue.main.async {
                        self.markers[id] = markerDataObj
                        annotations.append(annotation)
                    }
                }
            }
            
            group.notify(queue: .main) {
                // Create annotations
                annotationManager.annotations = annotationManager.annotations + annotations
                
                // Update stored annotations
                for (index, annotation) in annotations.enumerated() {
                    if index < batch.count {
                        let markerId = batch[index]["id"] as? String
                        if let id = markerId {
                            self.markers[id]?.annotation = annotation
                        }
                    }
                }
                
                processedCount += batch.count
                
                // Process next batch if any
                if endIndex < totalCount {
                    processBatch(startIndex: endIndex)
                } else {
                    completion(true)
                }
            }
        }
        
        processBatch(startIndex: 0)
    }
    
    /**
     * Update marker positions
     */
    func updateMarkers(markersList: [[String: Any]]) {
        let currentTime = Date()
        
        // Throttle updates
        if currentTime.timeIntervalSince(lastUpdateTime) < updateThrottleMs {
            // Queue update
            for markerData in markersList {
                guard let id = markerData["id"] as? String,
                      let latitude = (markerData["latitude"] as? NSNumber)?.doubleValue,
                      let longitude = (markerData["longitude"] as? NSNumber)?.doubleValue else {
                    continue
                }
                
                let newPoint = Point(CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
                
                // Check if position changed significantly
                if let existingMarker = markers[id] {
                    let distance = calculateDistance(
                        lat1: existingMarker.point.coordinate.latitude,
                        lon1: existingMarker.point.coordinate.longitude,
                        lat2: latitude,
                        lon2: longitude
                    )
                    
                    if distance > positionChangeThresholdMeters {
                        pendingUpdates.append(MarkerUpdate(id: id, point: newPoint))
                    }
                }
            }
            
            // Schedule batched update
            scheduleBatchedUpdate()
            return
        }
        
        lastUpdateTime = currentTime
        
        // Process updates immediately
        processMarkerUpdates(markersList: markersList)
    }
    
    private func scheduleBatchedUpdate() {
        updateTimer?.invalidate()
        
        updateTimer = Timer.scheduledTimer(withTimeInterval: updateThrottleMs, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            
            if !self.pendingUpdates.isEmpty {
                let updates = self.pendingUpdates
                self.pendingUpdates.removeAll()
                
                let markersList = updates.map { update in
                    [
                        "id": update.id,
                        "latitude": update.point.coordinate.latitude,
                        "longitude": update.point.coordinate.longitude
                    ] as [String: Any]
                }
                
                self.processMarkerUpdates(markersList: markersList)
            }
        }
    }
    
    private func processMarkerUpdates(markersList: [[String: Any]]) {
        guard let annotationManager = pointAnnotationManager else { return }
        
        var updatedAnnotations: [PointAnnotation] = []
        var annotationsToRemove: [PointAnnotation] = []
        
        for markerData in markersList {
            guard let id = markerData["id"] as? String,
                  let latitude = (markerData["latitude"] as? NSNumber)?.doubleValue,
                  let longitude = (markerData["longitude"] as? NSNumber)?.doubleValue else {
                continue
            }
            
            guard var existingMarker = markers[id] else { continue }
            
            let newPoint = Point(CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
            
            // Remove old annotation
            if let oldAnnotation = existingMarker.annotation {
                annotationsToRemove.append(oldAnnotation)
            }
            
            // Create new annotation with updated position
            var newAnnotation = PointAnnotation(coordinate: newPoint.coordinate)
            newAnnotation.image = existingMarker.annotation?.image
            
            if let title = existingMarker.title {
                newAnnotation.textField = title
                newAnnotation.textAnchor = .bottom
            }
            
            // Update stored marker
            existingMarker.point = newPoint
            existingMarker.annotation = newAnnotation
            markers[id] = existingMarker
            
            updatedAnnotations.append(newAnnotation)
        }
        
        // Update annotations
        var currentAnnotations = annotationManager.annotations
        currentAnnotations.removeAll { annotation in
            annotationsToRemove.contains { $0.id == annotation.id }
        }
        annotationManager.annotations = currentAnnotations + updatedAnnotations
        
        // Update stored annotations
        for annotation in updatedAnnotations {
            // Find marker by coordinate match (simplified - in production use ID tracking)
            if let markerId = markers.first(where: { $0.value.annotation?.id == annotation.id })?.key {
                markers[markerId]?.annotation = annotation
            }
        }
    }
    
    /**
     * Remove markers by IDs
     */
    func removeMarkers(markerIds: [String]) {
        guard let annotationManager = pointAnnotationManager else { return }
        
        var annotationsToRemove: [PointAnnotation] = []
        
        for id in markerIds {
            if let marker = markers[id], let annotation = marker.annotation {
                annotationsToRemove.append(annotation)
            }
            markers.removeValue(forKey: id)
        }
        
        var currentAnnotations = annotationManager.annotations
        currentAnnotations.removeAll { annotation in
            annotationsToRemove.contains { $0.id == annotation.id }
        }
        annotationManager.annotations = currentAnnotations
    }
    
    /**
     * Clear all markers
     */
    func clearAllMarkers() {
        guard let annotationManager = pointAnnotationManager else { return }
        
        annotationManager.annotations = []
        markers.removeAll()
        pendingUpdates.removeAll()
        updateTimer?.invalidate()
        iconLoader.clearCache()
    }
    
    /**
     * Set clustering options
     */
    func setClusteringOptions(enabled: Bool, radius: Int, maxZoom: Int) {
        clusteringEnabled = enabled
        clusterRadius = radius
        clusterMaxZoom = maxZoom
        // Note: Actual clustering configuration would be set on the annotation manager
    }
    
    /**
     * Calculate distance between two points in meters (Haversine formula)
     */
    private func calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let earthRadius: Double = 6371000.0 // meters
        let dLat = (lat2 - lat1) * .pi / 180.0
        let dLon = (lon2 - lon1) * .pi / 180.0
        let a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * .pi / 180.0) * cos(lat2 * .pi / 180.0) *
                sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
    
    /**
     * Cleanup resources
     */
    func dispose() {
        updateTimer?.invalidate()
        clearAllMarkers()
        pointAnnotationManager = nil
    }
}

