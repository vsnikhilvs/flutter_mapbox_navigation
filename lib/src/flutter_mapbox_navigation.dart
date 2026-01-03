// ignore_for_file: use_setters_to_change_properties

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_mapbox_navigation/src/flutter_mapbox_navigation_platform_interface.dart';
import 'package:flutter_mapbox_navigation/src/models/models.dart';

/// Turn-By-Turn Navigation Provider
class MapBoxNavigation {
  static final MapBoxNavigation _instance = MapBoxNavigation();

  /// get current instance of this class
  static MapBoxNavigation get instance => _instance;

  MapBoxOptions _defaultOptions = MapBoxOptions(
    zoom: 15,
    tilt: 0,
    bearing: 0,
    enableRefresh: false,
    alternatives: true,
    voiceInstructionsEnabled: true,
    bannerInstructionsEnabled: true,
    allowsUTurnAtWayPoints: true,
    mode: MapBoxNavigationMode.drivingWithTraffic,
    units: VoiceUnits.imperial,
    simulateRoute: false,
    animateBuildRoute: true,
    longPressDestinationEnabled: true,
    language: 'en',
  );

  /// setter to set default options
  void setDefaultOptions(MapBoxOptions options) {
    _defaultOptions = options;
  }

  /// Getter to retriev default options
  MapBoxOptions getDefaultOptions() {
    return _defaultOptions;
  }

  ///Current Device OS Version
  Future<String?> getPlatformVersion() {
    return FlutterMapboxNavigationPlatform.instance.getPlatformVersion();
  }

  ///Total distance remaining in meters along route.
  Future<double?> getDistanceRemaining() {
    return FlutterMapboxNavigationPlatform.instance.getDistanceRemaining();
  }

  ///Total seconds remaining on all legs.
  Future<double?> getDurationRemaining() {
    return FlutterMapboxNavigationPlatform.instance.getDurationRemaining();
  }

  ///Adds waypoints or stops to an on-going navigation
  ///
  /// [wayPoints] must not be null and have at least 1 item. The way points will
  /// be inserted after the currently navigating waypoint
  /// in the existing navigation
  Future<dynamic> addWayPoints({required List<WayPoint> wayPoints}) async {
    return FlutterMapboxNavigationPlatform.instance
        .addWayPoints(wayPoints: wayPoints);
  }

  /// Free-drive mode is a unique Mapbox Navigation SDK feature that allows
  /// drivers to navigate without a set destination.
  /// This mode is sometimes referred to as passive navigation.
  /// Begins to generate Route Progress
  ///
  Future<bool?> startFreeDrive({MapBoxOptions? options}) async {
    options ??= _defaultOptions;
    return FlutterMapboxNavigationPlatform.instance.startFreeDrive(options);
  }

  ///Show the Navigation View and Begins Direction Routing
  ///
  /// [wayPoints] must not be null and have at least 2 items. A collection of
  /// [WayPoint](longitude, latitude and name). Must be at least 2 or
  /// at most 25. Cannot use drivingWithTraffic mode if more than 3-waypoints.
  /// [options] options used to generate the route and used while navigating
  /// Begins to generate Route Progress
  ///
  Future<bool?> startNavigation({
    required List<WayPoint> wayPoints,
    MapBoxOptions? options,
  }) async {
    options ??= _defaultOptions;
    return FlutterMapboxNavigationPlatform.instance
        .startNavigation(wayPoints, options);
  }

  ///Ends Navigation and Closes the Navigation View
  Future<bool?> finishNavigation() async {
    return FlutterMapboxNavigationPlatform.instance.finishNavigation();
  }

  /// Will download the navigation engine and the user's region
  /// to allow offline routing
  Future<bool?> enableOfflineRouting() async {
    return FlutterMapboxNavigationPlatform.instance.enableOfflineRouting();
  }

  /// Event listener for RouteEvents
  Future<dynamic> registerRouteEventListener(
    ValueSetter<RouteEvent> listener,
  ) async {
    return FlutterMapboxNavigationPlatform.instance
        .registerRouteEventListener(listener);
  }

  /// Add multiple markers to the navigation map
  ///
  /// [markers] List of markers to add. Each marker must have a unique id.
  /// [clustering] Optional clustering configuration. If not provided, default
  /// clustering will be used (enabled by default).
  ///
  /// Example:
  /// ```dart
  /// await MapBoxNavigation.instance.addMarkers(
  ///   markers: [
  ///     MapMarker(
  ///       id: 'user1',
  ///       latitude: 37.7749,
  ///       longitude: -122.4194,
  ///       title: 'User 1',
  ///       iconSource: MarkerIconSource.networkUrl,
  ///       iconData: 'https://example.com/avatar.png',
  ///     ),
  ///   ],
  /// );
  /// ```
  Future<bool?> addMarkers({
    required List<MapMarker> markers,
    ClusteringOptions? clustering,
  }) async {
    return FlutterMapboxNavigationPlatform.instance.addMarkers(
      markers: markers,
      clustering: clustering,
    );
  }

  /// Update existing markers with new positions
  ///
  /// [markers] List of markers to update. Each marker must have an existing id.
  /// Only markers with changed positions will be updated for performance.
  ///
  /// Example:
  /// ```dart
  /// await MapBoxNavigation.instance.updateMarkers(
  ///   markers: [
  ///     MapMarker(
  ///       id: 'user1',
  ///       latitude: 37.7750, // Updated position
  ///       longitude: -122.4195,
  ///     ),
  ///   ],
  /// );
  /// ```
  Future<bool?> updateMarkers({required List<MapMarker> markers}) async {
    return FlutterMapboxNavigationPlatform.instance.updateMarkers(
      markers: markers,
    );
  }

  /// Remove markers by their ids
  ///
  /// [markerIds] List of marker ids to remove from the map.
  ///
  /// Example:
  /// ```dart
  /// await MapBoxNavigation.instance.removeMarkers(
  ///   markerIds: ['user1', 'user2'],
  /// );
  /// ```
  Future<bool?> removeMarkers({required List<String> markerIds}) async {
    return FlutterMapboxNavigationPlatform.instance.removeMarkers(
      markerIds: markerIds,
    );
  }

  /// Clear all markers from the navigation map
  ///
  /// Example:
  /// ```dart
  /// await MapBoxNavigation.instance.clearAllMarkers();
  /// ```
  Future<bool?> clearAllMarkers() async {
    return FlutterMapboxNavigationPlatform.instance.clearAllMarkers();
  }

  /// Set clustering options for markers
  ///
  /// [options] Clustering configuration. This affects how markers are grouped
  /// together at different zoom levels.
  ///
  /// Example:
  /// ```dart
  /// await MapBoxNavigation.instance.setClusteringOptions(
  ///   ClusteringOptions(
  ///     enabled: true,
  ///     clusterRadius: 50,
  ///     clusterMaxZoom: 14,
  ///   ),
  /// );
  /// ```
  Future<bool?> setClusteringOptions(ClusteringOptions options) async {
    return FlutterMapboxNavigationPlatform.instance.setClusteringOptions(
      options,
    );
  }
}
