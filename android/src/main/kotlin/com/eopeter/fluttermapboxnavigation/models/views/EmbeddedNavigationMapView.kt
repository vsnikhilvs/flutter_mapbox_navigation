package com.eopeter.fluttermapboxnavigation.models.views

import android.app.Activity
import android.content.Context
import android.view.View
import com.eopeter.fluttermapboxnavigation.TurnByTurn
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.utilities.IconLoader
import com.eopeter.fluttermapboxnavigation.utilities.MarkerManager
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.navigation.dropin.map.MapViewObserver
import io.flutter.plugin.common.MethodCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import org.json.JSONObject

class EmbeddedNavigationMapView(
    context: Context,
    activity: Activity,
    binding: NavigationActivityBinding,
    binaryMessenger: BinaryMessenger,
    vId: Int,
    args: Any?,
    accessToken: String
) : PlatformView, TurnByTurn(context, activity, binding, accessToken) {
    private val viewId: Int = vId
    private val messenger: BinaryMessenger = binaryMessenger
    private val arguments = args as Map<*, *>
    
    // Marker management
    private var markerManager: MarkerManager? = null
    private var iconLoader: IconLoader? = null

    override fun initFlutterChannelHandlers() {
        methodChannel = MethodChannel(messenger, "flutter_mapbox_navigation/${viewId}")
        eventChannel = EventChannel(messenger, "flutter_mapbox_navigation/${viewId}/events")
        super.initFlutterChannelHandlers()
    }

    open fun initialize() {
        initFlutterChannelHandlers()
        initNavigation()

        if(!(this.arguments?.get("longPressDestinationEnabled") as Boolean)) {
            this.binding.navigationView.customizeViewOptions {
                enableMapLongClickIntercept = false;
            }
        }

        if((this.arguments?.get("enableOnMapTapCallback") as Boolean)) {
            this.binding.navigationView.registerMapObserver(onMapClick)
        }
        
        // Initialize marker manager when map style loads
        iconLoader = IconLoader(activity)
        // Use MapViewObserver to get MapView when it's attached
        this.binding.navigationView.registerMapObserver(object : MapViewObserver() {
            override fun onAttached(mapView: MapView) {
                super.onAttached(mapView)
                val mapboxMap = mapView.getMapboxMap()
                // Initialize marker manager when style is loaded
                mapboxMap.loadStyleUri(Style.MAPBOX_STREETS) { style ->
                    markerManager = MarkerManager(
                        activity,
                        mapView,
                        iconLoader!!
                    )
                    markerManager?.initialize(style)
                }
            }
        })
    }
    
    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "addMarkers" -> {
                addMarkers(methodCall, result)
            }
            "updateMarkers" -> {
                updateMarkers(methodCall, result)
            }
            "removeMarkers" -> {
                removeMarkers(methodCall, result)
            }
            "clearAllMarkers" -> {
                clearAllMarkers(result)
            }
            "setClusteringOptions" -> {
                setClusteringOptions(methodCall, result)
            }
            else -> {
                super.onMethodCall(methodCall, result)
            }
        }
    }
    
    private fun addMarkers(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        val markersList = arguments?.get("markers") as? List<Map<*, *>>
        val clusteringOptions = arguments?.get("clustering") as? Map<*, *>
        
        if (markersList == null || markerManager == null) {
            result.success(false)
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            markerManager?.addMarkers(
                markersList.map { it as Map<String, Any> },
                clusteringOptions as? Map<String, Any>
            )
            result.success(true)
        }
    }
    
    private fun updateMarkers(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        val markersList = arguments?.get("markers") as? List<Map<*, *>>
        
        if (markersList == null || markerManager == null) {
            result.success(false)
            return
        }
        
        markerManager?.updateMarkers(markersList.map { it as Map<String, Any> })
        result.success(true)
    }
    
    private fun removeMarkers(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        val markerIds = arguments?.get("markerIds") as? List<String>
        
        if (markerIds == null || markerManager == null) {
            result.success(false)
            return
        }
        
        markerManager?.removeMarkers(markerIds)
        result.success(true)
    }
    
    private fun clearAllMarkers(result: MethodChannel.Result) {
        if (markerManager == null) {
            result.success(false)
            return
        }
        
        markerManager?.clearAllMarkers()
        result.success(true)
    }
    
    private fun setClusteringOptions(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        
        if (arguments == null || markerManager == null) {
            result.success(false)
            return
        }
        
        val enabled = arguments["enabled"] as? Boolean ?: true
        val radius = (arguments["clusterRadius"] as? Number)?.toInt() ?: 50
        val maxZoom = (arguments["clusterMaxZoom"] as? Number)?.toInt() ?: 14
        
        markerManager?.setClusteringOptions(enabled, radius, maxZoom)
        result.success(true)
    }

    override fun getView(): View {
        return binding.root
    }

    override fun dispose() {
        if((this.arguments?.get("enableOnMapTapCallback") as Boolean)) {
            this.binding.navigationView.unregisterMapObserver(onMapClick)
        }
        unregisterObservers()
        
        // Cleanup marker manager
        markerManager?.dispose()
        markerManager = null
        iconLoader?.clearCache()
        iconLoader = null
    }

    /**
     * Notifies with attach and detach events on [MapView]
     */
    private val onMapClick = object : MapViewObserver(), OnMapClickListener {

        override fun onAttached(mapView: MapView) {
            mapView.gestures.addOnMapClickListener(this)
        }

        override fun onDetached(mapView: MapView) {
            mapView.gestures.removeOnMapClickListener(this)
        }

        override fun onMapClick(point: Point): Boolean {
            var waypoint = mapOf<String, String>(
                Pair("latitude", point.latitude().toString()),
                Pair("longitude", point.longitude().toString())
            )
            PluginUtilities.sendEvent(MapBoxEvents.ON_MAP_TAP, JSONObject(waypoint).toString())
            return false
        }
    }

}
