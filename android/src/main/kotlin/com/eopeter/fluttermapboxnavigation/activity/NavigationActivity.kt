package com.eopeter.fluttermapboxnavigation.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eopeter.fluttermapboxnavigation.FlutterMapboxNavigationPlugin
import com.eopeter.fluttermapboxnavigation.R
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.MapBoxRouteProgressEvent
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.CustomInfoPanelEndNavButtonBinder
import com.eopeter.fluttermapboxnavigation.utilities.IconLoader
import com.eopeter.fluttermapboxnavigation.utilities.MarkerManager
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities.Companion.sendEvent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.*
import com.mapbox.navigation.dropin.map.MapViewObserver
import com.mapbox.navigation.dropin.navigationview.NavigationViewListener
import com.mapbox.navigation.utils.internal.ifNonNull
import org.json.JSONObject

class NavigationActivity : AppCompatActivity() {

    private lateinit var binding: NavigationActivityBinding
    private var finishBroadcastReceiver: BroadcastReceiver? = null
    private var addWayPointsBroadcastReceiver: BroadcastReceiver? = null
    private var points: MutableList<Waypoint> = mutableListOf()
    private var waypointSet: WaypointSet = WaypointSet()
    private var canResetRoute: Boolean = false
    private var accessToken: String? = null
    private var lastLocation: Location? = null
    private var isNavigationInProgress = false

    private val addedWaypoints = WaypointSet()
    
    // Marker management
    private var markerManager: MarkerManager? = null
    private var iconLoader: IconLoader? = null
    private var markersBroadcastReceiver: BroadcastReceiver? = null
    
    // Queue for marker operations that arrive before markerManager is ready
    private val pendingMarkerOperations = mutableListOf<() -> Unit>()
    private var isMarkerManagerReady = false

    private val navigationStateListener = object : NavigationViewListener() {
        override fun onFreeDrive() {}
        override fun onDestinationPreview() {}
        override fun onRoutePreview() {}
        override fun onActiveNavigation() {
            isNavigationInProgress = true
        }
        override fun onArrival() {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.w("NavigationActivity", "=================================================")
        android.util.Log.w("NavigationActivity", "🚀 NavigationActivity onCreate called")
        android.util.Log.w("NavigationActivity", "=================================================")
        
        setTheme(R.style.AppTheme)
        binding = NavigationActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navigationView.addListener(navigationStateListener)
        binding.navigationView.registerMapObserver(onMapClick)
        accessToken = PluginUtilities.getResourceFromContext(this.applicationContext, "mapbox_access_token")

        val navigationOptions = NavigationOptions.Builder(this.applicationContext)
            .accessToken(accessToken)
            .build()

        MapboxNavigationApp.setup(navigationOptions).attach(this)

        if (FlutterMapboxNavigationPlugin.longPressDestinationEnabled) {
            binding.navigationView.registerMapObserver(onMapLongClick)
            binding.navigationView.customizeViewOptions {
                enableMapLongClickIntercept = false
            }
        }

        if (FlutterMapboxNavigationPlugin.enableOnMapTapCallback) {
            binding.navigationView.registerMapObserver(onMapClick)
        }

        // Add custom view binders
        binding.navigationView.customizeViewBinders {
            infoPanelEndNavigationButtonBinder = CustomInfoPanelEndNavButtonBinder(this@NavigationActivity)
        }

        // Register Mapbox observers
        MapboxNavigationApp.current()?.apply {
            registerBannerInstructionsObserver(bannerInstructionObserver)
            registerVoiceInstructionsObserver(voiceInstructionObserver)
            registerOffRouteObserver(offRouteObserver)
            registerRoutesObserver(routesObserver)
            registerLocationObserver(locationObserver)
            registerRouteProgressObserver(routeProgressObserver)
            registerArrivalObserver(arrivalObserver)
        }

        // Initialize BroadcastReceivers
        initReceivers()

        // Set map style and distance units
        var styleUrlDay = FlutterMapboxNavigationPlugin.mapStyleUrlDay ?: Style.MAPBOX_STREETS
        var styleUrlNight = FlutterMapboxNavigationPlugin.mapStyleUrlNight ?: Style.DARK
        binding.navigationView.customizeViewStyles {}
        binding.navigationView.customizeViewOptions {
            mapStyleUriDay = styleUrlDay
            mapStyleUriNight = styleUrlNight
            // Set distance units for UI display based on user preference
            distanceFormatterOptions = com.mapbox.navigation.base.formatter.DistanceFormatterOptions.Builder(this@NavigationActivity)
                .unitType(
                    if (FlutterMapboxNavigationPlugin.navigationVoiceUnits == com.mapbox.api.directions.v5.DirectionsCriteria.IMPERIAL)
                        com.mapbox.navigation.base.formatter.UnitType.IMPERIAL
                    else
                        com.mapbox.navigation.base.formatter.UnitType.METRIC
                )
                .build()
        }
        
        // Initialize marker manager when map style loads
        android.util.Log.w("NavigationActivity", "📍 Initializing IconLoader and MapViewObserver for markers")
        iconLoader = IconLoader(this.applicationContext)
        // Use MapViewObserver to get MapView when it's attached
        binding.navigationView.registerMapObserver(object : MapViewObserver() {
            override fun onAttached(mapView: MapView) {
                super.onAttached(mapView)
                android.util.Log.w("NavigationActivity", "📍 MapView attached")
                val mapboxMap = mapView.getMapboxMap()
                
                // IMPORTANT: Use the existing style from NavigationView, don't load a new one!
                // Loading MAPBOX_STREETS would overwrite the navigation style
                val existingStyle = mapboxMap.getStyle()
                if (existingStyle != null) {
                    android.util.Log.w("NavigationActivity", "📍 Using existing style: ${existingStyle.styleURI}")
                    // Try to initialize immediately, with retry if annotation plugin not ready
                    initializeMarkerManagerWithRetry(mapView, existingStyle)
                } else {
                    // Style not loaded yet, use getStyle callback
                    android.util.Log.w("NavigationActivity", "📍 Style not loaded yet, waiting for style...")
                    mapboxMap.getStyle { style ->
                        if (!isMarkerManagerReady || !(markerManager?.isInitialized() ?: false)) {
                            android.util.Log.w("NavigationActivity", "📍 Style callback received: ${style.styleURI}")
                            initializeMarkerManagerWithRetry(mapView, style)
                        } else {
                            android.util.Log.w("NavigationActivity", "📍 Style callback received but MarkerManager already initialized, skipping")
                        }
                    }
                }
            }
        })

        // Free drive mode
        if (FlutterMapboxNavigationPlugin.enableFreeDriveMode) {
            binding.navigationView.api.routeReplayEnabled(FlutterMapboxNavigationPlugin.simulateRoute)
            binding.navigationView.api.startFreeDrive()
            return
        }

        // Load waypoints from intent
        val p = intent.getSerializableExtra("waypoints") as? MutableList<Waypoint>
        if (p != null) points = p
        points.map { waypointSet.add(it) }
        requestRoutes(waypointSet)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister Map observers
        if (FlutterMapboxNavigationPlugin.longPressDestinationEnabled) {
            binding.navigationView.unregisterMapObserver(onMapLongClick)
        }
        if (FlutterMapboxNavigationPlugin.enableOnMapTapCallback) {
            binding.navigationView.unregisterMapObserver(onMapClick)
        }
        binding.navigationView.removeListener(navigationStateListener)

        // Unregister Mapbox observers
        MapboxNavigationApp.current()?.apply {
            unregisterBannerInstructionsObserver(bannerInstructionObserver)
            unregisterVoiceInstructionsObserver(voiceInstructionObserver)
            unregisterOffRouteObserver(offRouteObserver)
            unregisterRoutesObserver(routesObserver)
            unregisterLocationObserver(locationObserver)
            unregisterRouteProgressObserver(routeProgressObserver)
            unregisterArrivalObserver(arrivalObserver)
        }

        // Unregister broadcast receivers safely
        try {
            finishBroadcastReceiver?.let { unregisterReceiver(it) }
            addWayPointsBroadcastReceiver?.let { unregisterReceiver(it) }
            markersBroadcastReceiver?.let { unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) {}
        
        // Cleanup marker manager
        markerManager?.dispose()
        markerManager = null
        iconLoader?.clearCache()
        iconLoader = null
        isMarkerManagerReady = false
        pendingMarkerOperations.clear()
    }

    private fun initReceivers() {
        finishBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                finish()
            }
        }

        addWayPointsBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stops = intent.getSerializableExtra("waypoints") as? MutableList<Waypoint>
                val nextIndex = 1
                if (stops != null) {
                    if (points.count() >= nextIndex)
                        points.addAll(nextIndex, stops)
                    else
                        points.addAll(stops)
                }
            }
        }

        // Marker broadcast receiver
        markersBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    NavigationLauncher.KEY_ADD_MARKERS -> {
                        val markersList = intent.getSerializableExtra("markers") as? List<Map<*, *>>
                        // Use WARN so it shows up even when logcat is noisy.
                        android.util.Log.w("NavigationActivity", "📍 Broadcast received: ADD_MARKERS")
                        android.util.Log.w("NavigationActivity", "   markersList size: ${markersList?.size}")
                        android.util.Log.w("NavigationActivity", "   markerManager ready: $isMarkerManagerReady")
                        android.util.Log.w("NavigationActivity", "   markerManager actually initialized: ${markerManager?.isInitialized() ?: false}")
                        val clusteringOptions = intent.getSerializableExtra("clustering") as? Map<*, *>
                        
                        if (markersList != null) {
                            val operation: () -> Unit = {
                                android.util.Log.w("NavigationActivity", "📍 Executing ADD_MARKERS operation...")
                                CoroutineScope(Dispatchers.Main).launch {
                                    markerManager?.addMarkers(
                                        markersList.map { it as Map<String, Any> },
                                        clusteringOptions as? Map<String, Any>
                                    )
                                }
                                Unit
                            }
                            
                            // Double-check: both flag and actual initialization status
                            val actuallyReady = isMarkerManagerReady && (markerManager?.isInitialized() ?: false)
                            if (actuallyReady) {
                                android.util.Log.w("NavigationActivity", "✅ MarkerManager ready, executing immediately")
                                operation()
                            } else {
                                android.util.Log.w("NavigationActivity", "⏳ MarkerManager not ready (flag=$isMarkerManagerReady, initialized=${markerManager?.isInitialized()}), queueing operation")
                                pendingMarkerOperations.add(operation)
                            }
                        }
                    }
                    NavigationLauncher.KEY_UPDATE_MARKERS -> {
                        val markersList = intent.getSerializableExtra("markers") as? List<Map<*, *>>
                        android.util.Log.w("NavigationActivity", "📍 Broadcast received: UPDATE_MARKERS")
                        android.util.Log.w("NavigationActivity", "   markersList size: ${markersList?.size}")
                        android.util.Log.w("NavigationActivity", "   markerManager ready: $isMarkerManagerReady")
                        android.util.Log.w("NavigationActivity", "   markerManager actually initialized: ${markerManager?.isInitialized() ?: false}")
                        
                        if (markersList != null) {
                            val operation: () -> Unit = {
                                android.util.Log.w("NavigationActivity", "📍 Executing UPDATE_MARKERS operation...")
                                markerManager?.updateMarkers(markersList.map { it as Map<String, Any> })
                                Unit
                            }
                            
                            // Double-check: both flag and actual initialization status
                            val actuallyReady = isMarkerManagerReady && (markerManager?.isInitialized() ?: false)
                            if (actuallyReady) {
                                android.util.Log.w("NavigationActivity", "✅ MarkerManager ready, executing immediately")
                                operation()
                            } else {
                                android.util.Log.w("NavigationActivity", "⏳ MarkerManager not ready (flag=$isMarkerManagerReady, initialized=${markerManager?.isInitialized()}), queueing operation")
                                pendingMarkerOperations.add(operation)
                            }
                        }
                    }
                    NavigationLauncher.KEY_REMOVE_MARKERS -> {
                        val markerIds = intent.getSerializableExtra("markerIds") as? List<String>
                        if (markerIds != null) {
                            val operation: () -> Unit = {
                                markerManager?.removeMarkers(markerIds)
                                Unit
                            }
                            
                            if (isMarkerManagerReady) {
                                operation()
                            } else {
                                pendingMarkerOperations.add(operation)
                            }
                        }
                    }
                    NavigationLauncher.KEY_CLEAR_ALL_MARKERS -> {
                        val operation: () -> Unit = {
                            markerManager?.clearAllMarkers()
                            Unit
                        }
                        
                        if (isMarkerManagerReady) {
                            operation()
                        } else {
                            pendingMarkerOperations.add(operation)
                        }
                    }
                    NavigationLauncher.KEY_SET_CLUSTERING_OPTIONS -> {
                        val enabled = intent.getBooleanExtra("enabled", true)
                        val radius = intent.getIntExtra("radius", 50)
                        val maxZoom = intent.getIntExtra("maxZoom", 14)
                        val operation: () -> Unit = {
                            markerManager?.setClusteringOptions(enabled, radius, maxZoom)
                            Unit
                        }
                        
                        if (isMarkerManagerReady) {
                            operation()
                        } else {
                            pendingMarkerOperations.add(operation)
                        }
                    }
                }
            }
        }
        
        val stopFilter = IntentFilter(NavigationLauncher.KEY_STOP_NAVIGATION)
        val waypointFilter = IntentFilter(NavigationLauncher.KEY_ADD_WAYPOINTS)
        val markerFilter = IntentFilter().apply {
            addAction(NavigationLauncher.KEY_ADD_MARKERS)
            addAction(NavigationLauncher.KEY_UPDATE_MARKERS)
            addAction(NavigationLauncher.KEY_REMOVE_MARKERS)
            addAction(NavigationLauncher.KEY_CLEAR_ALL_MARKERS)
            addAction(NavigationLauncher.KEY_SET_CLUSTERING_OPTIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishBroadcastReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(addWayPointsBroadcastReceiver, waypointFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(markersBroadcastReceiver, markerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(finishBroadcastReceiver, stopFilter)
            registerReceiver(addWayPointsBroadcastReceiver, waypointFilter)
            registerReceiver(markersBroadcastReceiver, markerFilter)
        }
        android.util.Log.w("NavigationActivity", "✅ Broadcast receivers registered (marker actions: ADD/UPDATE/REMOVE/CLEAR/CLUSTER)")
    }

    /**
     * Initialize marker manager with retry mechanism in case annotation plugin isn't ready immediately
     */
    private fun initializeMarkerManagerWithRetry(mapView: MapView, style: Style, retryCount: Int = 0) {
        val maxRetries = 5
        val retryDelayMs = 500L
        
        if (isMarkerManagerReady && (markerManager?.isInitialized() ?: false)) {
            android.util.Log.w("NavigationActivity", "MarkerManager already initialized, skipping")
            return
        }
        
        android.util.Log.w("NavigationActivity", "📍 Attempting to initialize MarkerManager (attempt ${retryCount + 1}/$maxRetries)")
        val success = initializeMarkerManager(mapView, style)
        
        if (!success && retryCount < maxRetries) {
            android.util.Log.w("NavigationActivity", "⏳ MarkerManager initialization failed, retrying in ${retryDelayMs}ms...")
            Handler(Looper.getMainLooper()).postDelayed({
                initializeMarkerManagerWithRetry(mapView, style, retryCount + 1)
            }, retryDelayMs)
        } else if (!success) {
            android.util.Log.e("NavigationActivity", "❌ MarkerManager initialization failed after $maxRetries attempts")
            android.util.Log.e("NavigationActivity", "   The annotation plugin is not available in NavigationView's MapView")
        }
    }
    
    private fun initializeMarkerManager(mapView: MapView, style: Style): Boolean {
        if (isMarkerManagerReady && (markerManager?.isInitialized() ?: false)) {
            android.util.Log.w("NavigationActivity", "MarkerManager already initialized, skipping")
            return true
        }
        
        android.util.Log.w("NavigationActivity", "📍 Initializing MarkerManager with style: ${style.styleURI}")
        markerManager = MarkerManager(
            this@NavigationActivity.applicationContext,
            mapView,
            iconLoader!!
        )
        
        // ✅ Only set ready if initialization actually succeeded
        val success = markerManager?.initialize(style) ?: false
        isMarkerManagerReady = success
        
        if (success) {
            android.util.Log.w("NavigationActivity", "✅ MarkerManager initialized and ready!")
            
            // Process any pending marker operations
            if (pendingMarkerOperations.isNotEmpty()) {
                android.util.Log.w("NavigationActivity", "📍 Processing ${pendingMarkerOperations.size} pending marker operations...")
                pendingMarkerOperations.forEach { operation ->
                    try {
                        operation()
                    } catch (e: Exception) {
                        android.util.Log.e("NavigationActivity", "❌ Error processing pending marker operation: ${e.message}", e)
                    }
                }
                pendingMarkerOperations.clear()
                android.util.Log.w("NavigationActivity", "✅ All pending marker operations processed")
            }
        } else {
            android.util.Log.e("NavigationActivity", "❌ MarkerManager initialization FAILED - PointAnnotationManager is null")
            android.util.Log.e("NavigationActivity", "   Markers will be queued until initialization succeeds")
        }
        
        return success
    }
    
    fun tryCancelNavigation() {
        if (isNavigationInProgress) {
            isNavigationInProgress = false
            sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
        }
    }

    private fun requestRoutes(waypointSet: WaypointSet) {
        sendEvent(MapBoxEvents.ROUTE_BUILDING)
        MapboxNavigationApp.current()!!.requestRoutes(
            routeOptions = RouteOptions.builder()
                .applyDefaultNavigationOptions()
                // Removed applyLanguageAndVoiceUnitOptions to manually control units
                .coordinatesList(waypointSet.coordinatesList())
                .waypointIndicesList(waypointSet.waypointsIndices())
                .waypointNamesList(waypointSet.waypointsNames())
                .language(FlutterMapboxNavigationPlugin.navigationLanguage)
                .alternatives(FlutterMapboxNavigationPlugin.showAlternateRoutes)
                .voiceUnits(FlutterMapboxNavigationPlugin.navigationVoiceUnits) // Explicitly set units from Flutter
                .bannerInstructions(FlutterMapboxNavigationPlugin.bannerInstructionsEnabled)
                .voiceInstructions(FlutterMapboxNavigationPlugin.voiceInstructionsEnabled)
                .steps(true)
                .build(),
            callback = object : NavigationRouterCallback {
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                }

                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: RouterOrigin) {
                    sendEvent(MapBoxEvents.ROUTE_BUILT, Gson().toJson(routes.map { it.directionsRoute.toJson() }))
                    if (routes.isEmpty()) {
                        sendEvent(MapBoxEvents.ROUTE_BUILD_NO_ROUTES_FOUND)
                        return
                    }
                    binding.navigationView.api.routeReplayEnabled(FlutterMapboxNavigationPlugin.simulateRoute)
                    binding.navigationView.api.startActiveGuidance(routes)
                    
                    // CRITICAL: Send navigation_running event to notify Flutter that navigation is ready
                    android.util.Log.d("NavigationActivity", "🚀 Navigation started, sending NAVIGATION_RUNNING event")
                    sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
                    android.util.Log.d("NavigationActivity", "✅ NAVIGATION_RUNNING event sent")
                }
            }
        )
    }

    // Observers
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        val progressEvent = MapBoxRouteProgressEvent(routeProgress)
        FlutterMapboxNavigationPlugin.distanceRemaining = routeProgress.distanceRemaining
        FlutterMapboxNavigationPlugin.durationRemaining = routeProgress.durationRemaining
        sendEvent(progressEvent)
    }

    private val arrivalObserver: ArrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            isNavigationInProgress = false
            sendEvent(MapBoxEvents.ON_ARRIVAL)
        }
        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}
        override fun onWaypointArrival(routeProgress: RouteProgress) {}
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            lastLocation = locationMatcherResult.enhancedLocation
        }
        override fun onNewRawLocation(rawLocation: Location) {}
    }

    private val bannerInstructionObserver = BannerInstructionsObserver { bannerInstructions ->
        sendEvent(MapBoxEvents.BANNER_INSTRUCTION, bannerInstructions.primary().text())
    }

    private val voiceInstructionObserver = VoiceInstructionsObserver { voiceInstructions ->
        sendEvent(MapBoxEvents.SPEECH_ANNOUNCEMENT, voiceInstructions.announcement().toString())
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        if (offRoute) sendEvent(MapBoxEvents.USER_OFF_ROUTE)
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) sendEvent(MapBoxEvents.REROUTE_ALONG)
    }

    private val onMapLongClick = object : MapViewObserver(), OnMapLongClickListener {
        override fun onAttached(mapView: MapView) {
            mapView.gestures.addOnMapLongClickListener(this)
        }
        override fun onDetached(mapView: MapView) {
            mapView.gestures.removeOnMapLongClickListener(this)
        }
        override fun onMapLongClick(point: Point): Boolean {
            ifNonNull(lastLocation) {
                val waypointSet = WaypointSet()
                waypointSet.add(Waypoint(Point.fromLngLat(it.longitude, it.latitude)))
                waypointSet.add(Waypoint(point))
                requestRoutes(waypointSet)
            }
            return false
        }
    }

    private val onMapClick = object : MapViewObserver(), OnMapClickListener {
        override fun onAttached(mapView: MapView) {
            mapView.gestures.addOnMapClickListener(this)
        }
        override fun onDetached(mapView: MapView) {
            mapView.gestures.removeOnMapClickListener(this)
        }
        override fun onMapClick(point: Point): Boolean {
            val waypoint = mapOf(
                "latitude" to point.latitude().toString(),
                "longitude" to point.longitude().toString()
            )
            sendEvent(MapBoxEvents.ON_MAP_TAP, JSONObject(waypoint).toString())
            return false
        }
    }
}
