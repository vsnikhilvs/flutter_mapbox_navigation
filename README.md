[![Pub][pub_badge]][pub] [![BuyMeACoffee][buy_me_a_coffee_badge]][buy_me_a_coffee]

# flutter_mapbox_navigation

Add Turn By Turn Navigation to Your Flutter Application Using MapBox. Never leave your app when you need to navigate your users to a location.

## Features

* A full-fledged turn-by-turn navigation UI for Flutter that’s ready to drop into your application
* [Professionally designed map styles](https://www.mapbox.com/maps/) for daytime and nighttime driving
* Worldwide driving, cycling, and walking directions powered by [open data](https://www.mapbox.com/about/open/) and user feedback
* Traffic avoidance and proactive rerouting based on current conditions in [over 55 countries](https://docs.mapbox.com/help/how-mapbox-works/directions/#traffic-data)
* Natural-sounding turn instructions powered by [Amazon Polly](https://aws.amazon.com/polly/) (no configuration needed)
* [Support for over two dozen languages](https://docs.mapbox.com/ios/navigation/overview/localization-and-internationalization/)

## IOS Configuration

1. Go to your [Mapbox account dashboard](https://account.mapbox.com/) and create an access token that has the `DOWNLOADS:READ` scope. **PLEASE NOTE: This is not the same as your production Mapbox API token. Make sure to keep it private and do not insert it into any Info.plist file.** Create a file named `.netrc` in your home directory if it doesn’t already exist, then add the following lines to the end of the file:
   ```
   machine api.mapbox.com
     login mapbox
     password PRIVATE_MAPBOX_API_TOKEN
   ```
   where _PRIVATE_MAPBOX_API_TOKEN_ is your Mapbox API token with the `DOWNLOADS:READ` scope.
   
1. Mapbox APIs and vector tiles require a Mapbox account and API access token. In the project editor, select the application target, then go to the Info tab. Under the “Custom iOS Target Properties” section, set `MBXAccessToken` to your access token. You can obtain an access token from the [Mapbox account page](https://account.mapbox.com/access-tokens/).

1. In order for the SDK to track the user’s location as they move along the route, set `NSLocationWhenInUseUsageDescription` to:
   > Shows your location on the map and helps improve OpenStreetMap.

1. Users expect the SDK to continue to track the user’s location and deliver audible instructions even while a different application is visible or the device is locked. Go to the Capabilities tab. Under the Background Modes section, enable “Audio, AirPlay, and Picture in Picture” and “Location updates”. (Alternatively, add the `audio` and `location` values to the `UIBackgroundModes` array in the Info tab.)


## Android Configuration

1. Mapbox APIs and vector tiles require a Mapbox account and API access token. Add a new resource file called `mapbox_access_token.xml` with it's full path being `<YOUR_FLUTTER_APP_ROOT>/android/app/src/main/res/values/mapbox_access_token.xml`. Then add a string resource with name "mapbox_access_token" and your token as it's value as shown below. You can obtain an access token from the [Mapbox account page](https://account.mapbox.com/access-tokens/).
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <string name="mapbox_access_token" translatable="false" tools:ignore="UnusedResources">ADD_MAPBOX_ACCESS_TOKEN_HERE</string>
</resources>
```

2. Add the following permissions to the app level Android Manifest
```xml
<manifest>
    ...
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    ...
</manifest>
```

3. Add the MapBox Downloads token with the ```downloads:read``` scope to your gradle.properties file in Android folder to enable downloading the MapBox binaries from the repository. To secure this token from getting checked into source control, you can add it to the gradle.properties of your GRADLE_HOME which is usually at $USER_HOME/.gradle for Mac. This token can be retrieved from your [MapBox Dashboard](https://account.mapbox.com/access-tokens/). You can review the [Token Guide](https://docs.mapbox.com/accounts/guides/tokens/) to learn more about download tokens
```text
MAPBOX_DOWNLOADS_TOKEN=sk.XXXXXXXXXXXXXXX
```

After adding the above, your gradle.properties file may look something like this:
```text
org.gradle.jvmargs=-Xmx1536M
android.useAndroidX=true
android.enableJetifier=true
MAPBOX_DOWNLOADS_TOKEN=sk.epe9nE9peAcmwNzKVNqSbFfp2794YtnNepe9nE9peAcmwNzKVNqSbFfp2794YtnN.-HrbMMQmLdHwYb8r
```

4. Update `MainActivity.kt` to extends `FlutterFragmentActivity` vs `FlutterActivity`. Otherwise you'll get `Caused by: java.lang.IllegalStateException: Please ensure that the hosting Context is a valid ViewModelStoreOwner`.
```kotlin
//import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterFragmentActivity

class MainActivity: FlutterFragmentActivity() {
}
```

5. Add `implementation platform("org.jetbrains.kotlin:kotlin-bom:1.8.0")` to `android/app/build.gradle`

## Usage

#### Set Default Route Options (Optional)
```dart
    MapBoxNavigation.instance.setDefaultOptions(MapBoxOptions(
                     initialLatitude: 36.1175275,
                     initialLongitude: -115.1839524,
                     zoom: 13.0,
                     tilt: 0.0,
                     bearing: 0.0,
                     enableRefresh: false,
                     alternatives: true,
                     voiceInstructionsEnabled: true,
                     bannerInstructionsEnabled: true,
                     allowsUTurnAtWayPoints: true,
                     mode: MapBoxNavigationMode.drivingWithTraffic,
                     mapStyleUrlDay: "https://url_to_day_style",
                     mapStyleUrlNight: "https://url_to_night_style",
                     units: VoiceUnits.imperial,
                     simulateRoute: true,
                     language: "en"))
```

#### Listen for Events

```dart
  MapBoxNavigation.instance.registerRouteEventListener(_onRouteEvent);
  Future<void> _onRouteEvent(e) async {

        _distanceRemaining = await _directions.distanceRemaining;
        _durationRemaining = await _directions.durationRemaining;
    
        switch (e.eventType) {
          case MapBoxEvent.progress_change:
            var progressEvent = e.data as RouteProgressEvent;
            _arrived = progressEvent.arrived;
            if (progressEvent.currentStepInstruction != null)
              _instruction = progressEvent.currentStepInstruction;
            break;
          case MapBoxEvent.route_building:
          case MapBoxEvent.route_built:
            _routeBuilt = true;
            break;
          case MapBoxEvent.route_build_failed:
            _routeBuilt = false;
            break;
          case MapBoxEvent.navigation_running:
            _isNavigating = true;
            break;
          case MapBoxEvent.on_arrival:
            _arrived = true;
            if (!_isMultipleStop) {
              await Future.delayed(Duration(seconds: 3));
              await _controller.finishNavigation();
            } else {}
            break;
          case MapBoxEvent.navigation_finished:
          case MapBoxEvent.navigation_cancelled:
            _routeBuilt = false;
            _isNavigating = false;
            break;
          default:
            break;
        }
        //refresh UI
        setState(() {});
      }
```

#### Begin Navigating

```dart

    final cityhall = WayPoint(name: "City Hall", latitude: 42.886448, longitude: -78.878372);
    final downtown = WayPoint(name: "Downtown Buffalo", latitude: 42.8866177, longitude: -78.8814924);

    var wayPoints = List<WayPoint>();
    wayPoints.add(cityHall);
    wayPoints.add(downtown);
    
    await MapBoxNavigation.instance.startNavigation(wayPoints: wayPoints);
```

#### Screenshots
![Navigation View](screenshots/screenshot1.png?raw=true "iOS View") | ![Android View](screenshots/screenshot2.png?raw=true "Android View")
|:---:|:---:|
| iOS View | Android View |



## Embedding Navigation View


#### Declare Controller
```dart
      MapBoxNavigationViewController _controller;
```

#### Add Navigation View to Widget Tree
```dart
            Container(
                color: Colors.grey,
                child: MapBoxNavigationView(
                    options: _options,
                    onRouteEvent: _onRouteEvent,
                    onCreated:
                        (MapBoxNavigationViewController controller) async {
                      _controller = controller;
                    }),
              ),
```
#### Build Route

```dart
        var wayPoints = List<WayPoint>();
                            wayPoints.add(_origin);
                            wayPoints.add(_stop1);
                            wayPoints.add(_stop2);
                            wayPoints.add(_stop3);
                            wayPoints.add(_stop4);
                            wayPoints.add(_origin);
                            _controller.buildRoute(wayPoints: wayPoints);
```

#### Start Navigation

```dart
    _controller.startNavigation();
```

### Additional IOS Configuration
Add the following to your `info.plist` file

```xml
    <dict>
        ...
        <key>io.flutter.embedded_views_preview</key>
        <true/>
        ...
    </dict>
```

### Embedding Navigation Screenshots
![Navigation View](screenshots/screenshot3.png?raw=true "Embedded iOS View") | ![Navigation View](screenshots/screenshot4.png?raw=true "Embedded Android View")
|:---:|:---:|
| Embedded iOS View | Embedded Android View |

## Multiple Markers Support

The plugin now supports adding multiple user markers to both full-screen and embedded navigation views. This is useful for displaying multiple users' locations in real-time during navigation.

### Features

- **Multiple Markers**: Add up to 50 markers simultaneously
- **Custom Icons**: Support for network URLs, asset paths, and base64 encoded images
- **Real-time Updates**: Update marker positions efficiently with throttling
- **Clustering**: Automatic marker clustering at different zoom levels
- **Performance Optimized**: Batch operations, caching, and update throttling

### Adding Markers

#### Full-Screen Navigation

```dart
// Create markers
final markers = [
  MapMarker(
    id: 'user1',
    latitude: 37.7749,
    longitude: -122.4194,
    title: 'User 1',
    iconSource: MarkerIconSource.networkUrl,
    iconData: 'https://example.com/avatar1.png',
    iconWidth: 40,
    iconHeight: 40,
  ),
  MapMarker(
    id: 'user2',
    latitude: 37.7849,
    longitude: -122.4294,
    title: 'User 2',
    iconSource: MarkerIconSource.assetPath,
    iconData: 'assets/user_icon.png',
  ),
];

// Add markers with optional clustering
await MapBoxNavigation.instance.addMarkers(
  markers: markers,
  clustering: ClusteringOptions(
    enabled: true,
    clusterRadius: 50,
    clusterMaxZoom: 14,
  ),
);
```

#### Embedded Navigation

```dart
// Using the embedded controller
await _controller.addMarkers(
  markers: markers,
  clustering: ClusteringOptions(enabled: true),
);
```

### Updating Marker Positions

Update marker positions in real-time:

```dart
final updatedMarkers = [
  MapMarker(
    id: 'user1',
    latitude: 37.7750, // Updated position
    longitude: -122.4195,
  ),
];

// Full-screen navigation
await MapBoxNavigation.instance.updateMarkers(markers: updatedMarkers);

// Embedded navigation
await _controller.updateMarkers(markers: updatedMarkers);
```

### Removing Markers

```dart
// Remove specific markers
await MapBoxNavigation.instance.removeMarkers(
  markerIds: ['user1', 'user2'],
);

// Clear all markers
await MapBoxNavigation.instance.clearAllMarkers();
```

### Icon Sources

Markers support multiple icon sources:

```dart
// Network URL
MapMarker(
  id: 'user1',
  latitude: 37.7749,
  longitude: -122.4194,
  iconSource: MarkerIconSource.networkUrl,
  iconData: 'https://example.com/icon.png',
)

// Avatar URL (renders a circular dot marker with the avatar inside)
MapMarker(
  id: 'participant1',
  latitude: 37.7749,
  longitude: -122.4194,
  avatarUrl: 'https://example.com/avatar.png',
)

// Asset path
MapMarker(
  id: 'user2',
  latitude: 37.7849,
  longitude: -122.4294,
  iconSource: MarkerIconSource.assetPath,
  iconData: 'assets/marker_icon.png',
)

// Base64 string
MapMarker(
  id: 'user3',
  latitude: 37.7949,
  longitude: -122.4394,
  iconSource: MarkerIconSource.base64,
  iconData: 'iVBORw0KGgoAAAANSUhEUgAA...', // Base64 encoded image
)

// Default icon with color tint
MapMarker(
  id: 'user4',
  latitude: 37.8049,
  longitude: -122.4494,
  iconSource: MarkerIconSource.defaultIcon,
  color: 0xFF2196F3, // Blue color
)
```

### Clustering Options

Configure marker clustering behavior:

```dart
await MapBoxNavigation.instance.setClusteringOptions(
  ClusteringOptions(
    enabled: true,        // Enable/disable clustering
    clusterRadius: 50,    // Cluster radius in pixels
    clusterMaxZoom: 14,   // Max zoom level for clustering
  ),
);
```

### Performance Considerations

- **Batch Updates**: Marker operations are automatically batched for better performance
- **Update Throttling**: Position updates are throttled to 2 updates per second
- **Position Threshold**: Only markers that moved more than 5 meters are updated
- **Icon Caching**: Icons are cached (max 50 entries) to reduce network/disk access
- **Clustering**: Automatically enabled when more than 10 markers are present

### Example: Real-time User Tracking

```dart
// Set up periodic updates
Timer.periodic(Duration(seconds: 2), (timer) async {
  final userLocations = await fetchUserLocations();
  
  final markers = userLocations.map((user) => MapMarker(
    id: user.id,
    latitude: user.latitude,
    longitude: user.longitude,
    title: user.name,
    avatarUrl: user.avatarUrl,
  )).toList();
  
  // Update markers (only changed positions will be updated)
  await MapBoxNavigation.instance.updateMarkers(markers: markers);
});
```

## To Do
* [DONE] Android Implementation
* [DONE] Add more settings like Navigation Mode (driving, walking, etc)
* [DONE] Stream Events like relevant navigation notifications, metrics, current location, etc. 
* [DONE] Embeddable Navigation View 
* [DONE] Multiple Markers Support
* Offline Routing

<!-- Links -->
[pub_badge]: https://img.shields.io/pub/v/flutter_mapbox_navigation.svg
[pub]: https://pub.dev/packages/flutter_mapbox_navigation
[buy_me_a_coffee]: https://www.buymeacoffee.com/eopeter
[buy_me_a_coffee_badge]: https://img.buymeacoffee.com/button-api/?text=Donate&emoji=&slug=eopeter&button_colour=29b6f6&font_colour=000000&font_family=Cookie&outline_colour=000000&coffee_colour=FFDD00