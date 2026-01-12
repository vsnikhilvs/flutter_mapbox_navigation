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
    private let avatarMarkerCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 200
        cache.totalCostLimit = 50 * 1024 * 1024 // 50MB
        return cache
    }()
    
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
        var avatarUrl: String?
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
    
    private func markerUIColor(_ color: Int?) -> UIColor {
        guard let colorValue = color else { return UIColor.systemBlue }
        let red = CGFloat((colorValue >> 16) & 0xFF) / 255.0
        let green = CGFloat((colorValue >> 8) & 0xFF) / 255.0
        let blue = CGFloat(colorValue & 0xFF) / 255.0
        return UIColor(red: red, green: green, blue: blue, alpha: 1.0)
    }
    
    private func avatarCacheKey(url: String, width: Int, height: Int, color: Int?) -> NSString {
        return "avatar:\(url):\(width)x\(height):\(color ?? 0)" as NSString
    }
    
    private func createAvatarMarkerImage(avatar: UIImage, size: CGSize, markerColor: UIColor) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 2
            
            // Background dot
            markerColor.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(
                x: center.x - radius,
                y: center.y - radius,
                width: radius * 2,
                height: radius * 2
            ))
            
            // White border
            UIColor.white.setStroke()
            ctx.cgContext.setLineWidth(3.0)
            ctx.cgContext.strokeEllipse(in: CGRect(
                x: center.x - radius,
                y: center.y - radius,
                width: radius * 2,
                height: radius * 2
            ))
            
            let inset: CGFloat = 3.0 + 2.0
            let innerRadius = max(radius - inset, 1.0)
            let innerRect = CGRect(
                x: center.x - innerRadius,
                y: center.y - innerRadius,
                width: innerRadius * 2,
                height: innerRadius * 2
            )
            
            // Clip to inner circle and draw avatar (aspect fill / center-crop)
            ctx.cgContext.saveGState()
            ctx.cgContext.addEllipse(in: innerRect)
            ctx.cgContext.clip()
            
            let srcSize = avatar.size
            let scale = max(innerRect.width / srcSize.width, innerRect.height / srcSize.height)
            let drawSize = CGSize(width: srcSize.width * scale, height: srcSize.height * scale)
            let drawOrigin = CGPoint(
                x: innerRect.midX - drawSize.width / 2,
                y: innerRect.midY - drawSize.height / 2
            )
            avatar.draw(in: CGRect(origin: drawOrigin, size: drawSize))
            
            ctx.cgContext.restoreGState()
        }
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
                let avatarUrl = (markerData["avatarUrl"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
                print("MarkerManager iOS addMarkers: id=\(id) avatarUrl=\(avatarUrl ?? "nil") iconSource=\(iconSource)")
                let iconWidth = (markerData["iconWidth"] as? NSNumber)?.intValue ?? 40
                let iconHeight = (markerData["iconHeight"] as? NSNumber)?.intValue ?? 40
                let color = (markerData["color"] as? NSNumber)?.intValue
                let markerColor = markerUIColor(color)
                
                let point = Point(CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
                
                group.enter()
                
                // If avatarUrl is provided, render a circular marker with the avatar inside.
                if let avatarUrl, !avatarUrl.isEmpty {
                    let size = min(iconWidth, iconHeight)
                    let key = self.avatarCacheKey(url: avatarUrl, width: size, height: size, color: color)
                    if let cached = self.avatarMarkerCache.object(forKey: key) {
                        var annotation = PointAnnotation(coordinate: point.coordinate)
                        annotation.image = cached
                        DispatchQueue.main.async {
                            let markerDataObj = MarkerData(
                                id: id,
                                point: point,
                                title: title,
                                subtitle: subtitle,
                                iconSource: iconSource,
                                iconData: iconData,
                                avatarUrl: avatarUrl,
                                iconWidth: iconWidth,
                                iconHeight: iconHeight,
                                color: color,
                                annotation: nil
                            )
                            self.markers[id] = markerDataObj
                            annotations.append(annotation)
                            group.leave()
                        }
                        continue
                    }
                    
                    iconLoader.loadIcon(
                        iconSource: "networkUrl",
                        iconData: avatarUrl,
                        width: size,
                        height: size,
                        color: nil
                    ) { [weak self] avatar in
                        defer { group.leave() }
                        guard let self = self else { return }
                        
                        var annotation = PointAnnotation(coordinate: point.coordinate)
                        if let avatar = avatar {
                            print("MarkerManager iOS: ✅ avatar loaded for id=\(id) url=\(avatarUrl)")
                            let composed = self.createAvatarMarkerImage(
                                avatar: avatar,
                                size: CGSize(width: size, height: size),
                                markerColor: markerColor
                            )
                            self.avatarMarkerCache.setObject(composed, forKey: key)
                            annotation.image = composed
                        } else {
                            print("MarkerManager iOS: ❌ avatar load returned nil for id=\(id) url=\(avatarUrl)")
                        }
                        
                        // Set text if title provided
                        if let title = title {
                            annotation.textField = title
                            if subtitle != nil {
                                annotation.textOffset = [0, -2]
                            }
                            annotation.textAnchor = .bottom
                        }
                        
                        let markerDataObj = MarkerData(
                            id: id,
                            point: point,
                            title: title,
                            subtitle: subtitle,
                            iconSource: iconSource,
                            iconData: iconData,
                            avatarUrl: avatarUrl,
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
                    
                    continue
                }
                
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
                        avatarUrl: nil,
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

        for markerData in markersList {
            guard let id = markerData["id"] as? String,
                  let latitude = (markerData["latitude"] as? NSNumber)?.doubleValue,
                  let longitude = (markerData["longitude"] as? NSNumber)?.doubleValue else {
                continue
            }

            let newPoint = Point(CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
            let newAvatarUrl = (markerData["avatarUrl"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)

            // Self-heal: if marker doesn't exist, create it (position + optional avatar).
            if markers[id] == nil {
                let iconWidth = (markerData["iconWidth"] as? NSNumber)?.intValue ?? 40
                let iconHeight = (markerData["iconHeight"] as? NSNumber)?.intValue ?? 40
                let iconSource = markerData["iconSource"] as? String ?? "defaultIcon"
                let iconData = markerData["iconData"] as? String
                let color = (markerData["color"] as? NSNumber)?.intValue

                addMarkers(markersList: [markerData], clusteringOptions: nil) { _ in }
                continue
            }

            guard var existingMarker = markers[id], var annotation = existingMarker.annotation else { continue }
            annotation.coordinate = newPoint.coordinate

            // If avatarUrl changed, update the annotation image asynchronously.
            if let newAvatarUrl, !newAvatarUrl.isEmpty, newAvatarUrl != existingMarker.avatarUrl {
                existingMarker.avatarUrl = newAvatarUrl
                let size = min(existingMarker.iconWidth, existingMarker.iconHeight)
                let key = avatarCacheKey(url: newAvatarUrl, width: size, height: size, color: existingMarker.color)
                if let cached = avatarMarkerCache.object(forKey: key) {
                    annotation.image = cached
                } else {
                    let markerColor = markerUIColor(existingMarker.color)
                    iconLoader.loadIcon(iconSource: "networkUrl", iconData: newAvatarUrl, width: size, height: size, color: nil) { [weak self] avatar in
                        guard let self = self, let avatar = avatar else { return }
                        let composed = self.createAvatarMarkerImage(avatar: avatar, size: CGSize(width: size, height: size), markerColor: markerColor)
                        self.avatarMarkerCache.setObject(composed, forKey: key)
                        DispatchQueue.main.async {
                            if var m = self.markers[id] {
                                m.avatarUrl = newAvatarUrl
                                m.annotation?.image = composed
                                self.markers[id] = m
                                annotationManager.annotations = Array(self.markers.values.compactMap { $0.annotation })
                            }
                        }
                    }
                }
            }

            // Update stored marker
            existingMarker.point = newPoint
            existingMarker.annotation = annotation
            markers[id] = existingMarker
        }

        annotationManager.annotations = Array(markers.values.compactMap { $0.annotation })
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
        avatarMarkerCache.removeAllObjects()
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

