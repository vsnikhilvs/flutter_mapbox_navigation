import Foundation
import UIKit
import MapboxMaps

/**
 * Utility class for loading marker icons from various sources:
 * - Network URLs
 * - Asset paths
 * - Base64 strings
 *
 * Includes caching for performance optimization.
 */
class IconLoader {
    private let maxCacheSize = 50
    private var iconCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 50
        cache.totalCostLimit = 50 * 1024 * 1024 // 50MB
        return cache
    }()
    
    private let defaultIconSize: CGFloat = 40
    
    /**
     * Load icon from various sources
     */
    func loadIcon(
        iconSource: String,
        iconData: String?,
        width: Int = 40,
        height: Int = 40,
        color: Int? = nil,
        completion: @escaping (UIImage?) -> Void
    ) {
        let cacheKey = generateCacheKey(iconSource: iconSource, iconData: iconData, width: width, height: height, color: color)
        
        // Check cache first
        if let cachedIcon = iconCache.object(forKey: cacheKey as NSString) {
            completion(cachedIcon)
            return
        }
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else {
                completion(nil)
                return
            }
            
            let icon: UIImage?
            
            switch iconSource {
            case "networkUrl":
                icon = self.loadFromNetwork(url: iconData, width: width, height: height)
            case "assetPath":
                icon = self.loadFromAsset(assetPath: iconData, width: width, height: height)
            case "base64":
                icon = self.loadFromBase64(base64String: iconData, width: width, height: height)
            case "defaultIcon":
                icon = self.createDefaultIcon(width: width, height: height, color: color)
            default:
                icon = self.createDefaultIcon(width: width, height: height, color: color)
            }
            
            // Cache the result
            if let finalIcon = icon {
                self.iconCache.setObject(finalIcon, forKey: cacheKey as NSString)
            }
            
            DispatchQueue.main.async {
                completion(icon)
            }
        }
    }
    
    /**
     * Load icon from network URL
     */
    private func loadFromNetwork(url: String?, width: Int, height: Int) -> UIImage? {
        guard let urlString = url, let imageUrl = URL(string: urlString) else {
            return nil
        }
        
        do {
            let imageData = try Data(contentsOf: imageUrl)
            guard let image = UIImage(data: imageData) else {
                return nil
            }
            return resizeImage(image: image, width: width, height: height)
        } catch {
            print("IconLoader: Error loading from network: \(error.localizedDescription)")
            return nil
        }
    }
    
    /**
     * Load icon from asset bundle
     */
    private func loadFromAsset(assetPath: String?, width: Int, height: Int) -> UIImage? {
        guard let assetPath = assetPath else {
            return nil
        }
        
        guard let image = UIImage(named: assetPath) else {
            print("IconLoader: Asset not found: \(assetPath)")
            return nil
        }
        
        return resizeImage(image: image, width: width, height: height)
    }
    
    /**
     * Load icon from base64 string
     */
    private func loadFromBase64(base64String: String?, width: Int, height: Int) -> UIImage? {
        guard let base64String = base64String,
              let imageData = Data(base64Encoded: base64String),
              let image = UIImage(data: imageData) else {
            return nil
        }
        
        return resizeImage(image: image, width: width, height: height)
    }
    
    /**
     * Create default marker icon
     */
    private func createDefaultIcon(width: Int, height: Int, color: Int?) -> UIImage {
        let size = CGSize(width: width, height: height)
        let renderer = UIGraphicsImageRenderer(size: size)
        
        return renderer.image { context in
            let rect = CGRect(origin: .zero, size: size)
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 * 0.8
            
            // Default color is blue, or use provided color
            let iconColor: UIColor
            if let colorValue = color {
                let red = CGFloat((colorValue >> 16) & 0xFF) / 255.0
                let green = CGFloat((colorValue >> 8) & 0xFF) / 255.0
                let blue = CGFloat(colorValue & 0xFF) / 255.0
                iconColor = UIColor(red: red, green: green, blue: blue, alpha: 1.0)
            } else {
                iconColor = UIColor.systemBlue
            }
            
            // Draw circle
            iconColor.setFill()
            context.cgContext.fillEllipse(in: CGRect(
                x: center.x - radius,
                y: center.y - radius,
                width: radius * 2,
                height: radius * 2
            ))
            
            // Add white border
            UIColor.white.setStroke()
            context.cgContext.setLineWidth(2.0)
            context.cgContext.strokeEllipse(in: CGRect(
                x: center.x - radius,
                y: center.y - radius,
                width: radius * 2,
                height: radius * 2
            ))
        }
    }
    
    /**
     * Resize image to specified dimensions
     */
    private func resizeImage(image: UIImage, width: Int, height: Int) -> UIImage? {
        let size = CGSize(width: width, height: height)
        let renderer = UIGraphicsImageRenderer(size: size)
        
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
    
    /**
     * Generate cache key for icon
     */
    private func generateCacheKey(iconSource: String, iconData: String?, width: Int, height: Int, color: Int?) -> String {
        return "\(iconSource)_\(iconData ?? "")_\(width)_\(height)_\(color ?? 0)"
    }
    
    /**
     * Clear the icon cache
     */
    func clearCache() {
        iconCache.removeAllObjects()
    }
}

