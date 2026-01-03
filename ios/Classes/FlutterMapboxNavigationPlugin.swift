import Flutter
import UIKit
import MapboxMaps
import MapboxDirections
import MapboxCoreNavigation
import MapboxNavigation

public class FlutterMapboxNavigationPlugin: NavigationFactory, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "flutter_mapbox_navigation", binaryMessenger: registrar.messenger())
    let eventChannel = FlutterEventChannel(name: "flutter_mapbox_navigation/events", binaryMessenger: registrar.messenger())
    let instance = FlutterMapboxNavigationPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)

    eventChannel.setStreamHandler(instance)

    let viewFactory = FlutterMapboxNavigationViewFactory(messenger: registrar.messenger())
    registrar.register(viewFactory, withId: "FlutterMapboxNavigationView")

  }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {

        let arguments = call.arguments as? NSDictionary

        if(call.method == "getPlatformVersion")
        {
            result("iOS " + UIDevice.current.systemVersion)
        }
        else if(call.method == "getDistanceRemaining")
        {
            result(_distanceRemaining)
        }
        else if(call.method == "getDurationRemaining")
        {
            result(_durationRemaining)
        }
        else if(call.method == "startFreeDrive")
        {
            startFreeDrive(arguments: arguments, result: result)
        }
        else if(call.method == "startNavigation")
        {
            startNavigation(arguments: arguments, result: result)
        }
        else if(call.method == "addWayPoints")
        {
            addWayPoints(arguments: arguments, result: result)
        }
        else if(call.method == "finishNavigation")
        {
            endNavigation(result: result)
        }
        else if(call.method == "enableOfflineRouting")
        {
            downloadOfflineRoute(arguments: arguments, flutterResult: result)
        }
        else if(call.method == "addMarkers")
        {
            addMarkers(arguments: arguments, result: result)
        }
        else if(call.method == "updateMarkers")
        {
            updateMarkers(arguments: arguments, result: result)
        }
        else if(call.method == "removeMarkers")
        {
            removeMarkers(arguments: arguments, result: result)
        }
        else if(call.method == "clearAllMarkers")
        {
            clearAllMarkers(result: result)
        }
        else if(call.method == "setClusteringOptions")
        {
            setClusteringOptions(arguments: arguments, result: result)
        }
        else
        {
            result("Method is Not Implemented");
        }

    }
    
    // Marker management for full-screen navigation
    private var fullScreenMarkerManager: MarkerManager?
    private var fullScreenIconLoader: IconLoader?
    
    private func addMarkers(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let markersList = arguments?["markers"] as? [[String: Any]] else {
            result(false)
            return
        }
        
        let clusteringOptions = arguments?["clustering"] as? [String: Any]
        
        // For full-screen navigation, access NavigationViewController's mapView
        if let navViewController = _navigationViewController,
           let navigationMapView = navViewController.navigationMapView {
            let mapView = navigationMapView.mapView
            
            // Initialize marker manager if not already done
            if fullScreenIconLoader == nil {
                fullScreenIconLoader = IconLoader()
            }
            
            if fullScreenMarkerManager == nil {
                fullScreenMarkerManager = MarkerManager(
                    mapView: mapView,
                    iconLoader: fullScreenIconLoader!
                )
                
                // Initialize when style loads
                mapView.mapboxMap.onStyleLoaded.observe { [weak self] _ in
                    guard let self = self else { return }
                    do {
                        let style = try mapView.mapboxMap.style
                        self.fullScreenMarkerManager?.initialize(style: style)
                    } catch {
                        print("Error initializing marker manager: \(error)")
                    }
                }
            }
            
            // Wait a bit for initialization, then add markers
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.fullScreenMarkerManager?.addMarkers(
                    markersList: markersList,
                    clusteringOptions: clusteringOptions
                ) { success in
                    result(success)
                }
            }
        } else {
            result(false)
        }
    }
    
    private func updateMarkers(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let markersList = arguments?["markers"] as? [[String: Any]],
              let markerManager = fullScreenMarkerManager else {
            result(false)
            return
        }
        
        markerManager.updateMarkers(markersList: markersList)
        result(true)
    }
    
    private func removeMarkers(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let markerIds = arguments?["markerIds"] as? [String],
              let markerManager = fullScreenMarkerManager else {
            result(false)
            return
        }
        
        markerManager.removeMarkers(markerIds: markerIds)
        result(true)
    }
    
    private func clearAllMarkers(result: @escaping FlutterResult) {
        guard let markerManager = fullScreenMarkerManager else {
            result(false)
            return
        }
        
        markerManager.clearAllMarkers()
        result(true)
    }
    
    private func setClusteringOptions(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let arguments = arguments,
              let markerManager = fullScreenMarkerManager else {
            result(false)
            return
        }
        
        let enabled = arguments["enabled"] as? Bool ?? true
        let radius = (arguments["clusterRadius"] as? NSNumber)?.intValue ?? 50
        let maxZoom = (arguments["clusterMaxZoom"] as? NSNumber)?.intValue ?? 14
        
        markerManager.setClusteringOptions(enabled: enabled, radius: radius, maxZoom: maxZoom)
        result(true)
    }

}
