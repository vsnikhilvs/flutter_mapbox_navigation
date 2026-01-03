package com.eopeter.fluttermapboxnavigation.activity;

import android.app.Activity;
import android.content.Intent;

import com.eopeter.fluttermapboxnavigation.models.Waypoint;

import java.io.Serializable;
import java.util.List;

public class NavigationLauncher {
    public static final String KEY_ADD_WAYPOINTS = "com.my.mapbox.broadcast.ADD_WAYPOINTS";
    public static final String KEY_STOP_NAVIGATION = "com.my.mapbox.broadcast.STOP_NAVIGATION";
    
    // Marker operation broadcast keys
    public static final String KEY_ADD_MARKERS = "com.my.mapbox.broadcast.ADD_MARKERS";
    public static final String KEY_UPDATE_MARKERS = "com.my.mapbox.broadcast.UPDATE_MARKERS";
    public static final String KEY_REMOVE_MARKERS = "com.my.mapbox.broadcast.REMOVE_MARKERS";
    public static final String KEY_CLEAR_ALL_MARKERS = "com.my.mapbox.broadcast.CLEAR_ALL_MARKERS";
    public static final String KEY_SET_CLUSTERING_OPTIONS = "com.my.mapbox.broadcast.SET_CLUSTERING_OPTIONS";
    public static void startNavigation(Activity activity, List<Waypoint> wayPoints) {
        Intent navigationIntent = new Intent(activity, NavigationActivity.class);
        navigationIntent.putExtra("waypoints", (Serializable) wayPoints);
        activity.startActivity(navigationIntent);
    }

    public static void addWayPoints(Activity activity, List<Waypoint> wayPoints) {
        Intent navigationIntent = new Intent(activity, NavigationActivity.class);
        navigationIntent.setAction(KEY_ADD_WAYPOINTS);
        navigationIntent.putExtra("isAddingWayPoints", true);
        navigationIntent.putExtra("waypoints", (Serializable) wayPoints);
        activity.sendBroadcast(navigationIntent);
    }

    public static void stopNavigation(Activity activity) {
        Intent stopIntent = new Intent();
        stopIntent.setAction(KEY_STOP_NAVIGATION);
        activity.sendBroadcast(stopIntent);
    }
    
    // Marker operation methods
    public static void addMarkers(Activity activity, List<Map<String, Object>> markers, Map<String, Object> clustering) {
        Intent markerIntent = new Intent();
        markerIntent.setAction(KEY_ADD_MARKERS);
        markerIntent.putExtra("markers", (Serializable) markers);
        if (clustering != null) {
            markerIntent.putExtra("clustering", (Serializable) clustering);
        }
        activity.sendBroadcast(markerIntent);
    }
    
    public static void updateMarkers(Activity activity, List<Map<String, Object>> markers) {
        Intent markerIntent = new Intent();
        markerIntent.setAction(KEY_UPDATE_MARKERS);
        markerIntent.putExtra("markers", (Serializable) markers);
        activity.sendBroadcast(markerIntent);
    }
    
    public static void removeMarkers(Activity activity, List<String> markerIds) {
        Intent markerIntent = new Intent();
        markerIntent.setAction(KEY_REMOVE_MARKERS);
        markerIntent.putExtra("markerIds", (Serializable) markerIds);
        activity.sendBroadcast(markerIntent);
    }
    
    public static void clearAllMarkers(Activity activity) {
        Intent markerIntent = new Intent();
        markerIntent.setAction(KEY_CLEAR_ALL_MARKERS);
        activity.sendBroadcast(markerIntent);
    }
    
    public static void setClusteringOptions(Activity activity, boolean enabled, int radius, int maxZoom) {
        Intent markerIntent = new Intent();
        markerIntent.setAction(KEY_SET_CLUSTERING_OPTIONS);
        markerIntent.putExtra("enabled", enabled);
        markerIntent.putExtra("radius", radius);
        markerIntent.putExtra("maxZoom", maxZoom);
        activity.sendBroadcast(markerIntent);
    }
}
