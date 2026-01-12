/// Icon source types for marker icons
enum MarkerIconSource {
  /// Network URL - icon will be loaded from a URL
  networkUrl,
  /// Asset path - icon from app assets
  assetPath,
  /// Base64 string - icon encoded as base64
  base64,
  /// Default icon - use Mapbox default marker
  defaultIcon,
}

/// A `MapMarker` object represents a marker/annotation on the navigation map.
/// It can display user locations, points of interest, or other custom markers
/// with optional custom icons.
class MapMarker {
  /// Constructor
  MapMarker({
    required this.id,
    required this.latitude,
    required this.longitude,
    this.title,
    this.subtitle,
    this.iconSource = MarkerIconSource.defaultIcon,
    this.iconData,
    this.avatarUrl,
    this.iconWidth = 40,
    this.iconHeight = 40,
    this.color,
  });

  /// Create [MapMarker] from a JSON map
  MapMarker.fromJson(Map<String, dynamic> json)
      : id = json['id'] as String? ?? '',
        latitude = (json['latitude'] is String)
            ? double.tryParse(json['latitude'] as String)
            : json['latitude'] as double?,
        longitude = (json['longitude'] is String)
            ? double.tryParse(json['longitude'] as String)
            : json['longitude'] as double?,
        title = json['title'] as String?,
        subtitle = json['subtitle'] as String?,
        iconSource = _parseIconSource(json['iconSource'] as String?),
        iconData = json['iconData'] as String?,
        avatarUrl = json['avatarUrl'] as String?,
        iconWidth = json['iconWidth'] as int? ?? 40,
        iconHeight = json['iconHeight'] as int? ?? 40,
        color = json['color'] as int?;

  /// Helper method to parse icon source from string
  static MarkerIconSource _parseIconSource(String? iconSourceStr) {
    if (iconSourceStr != null) {
      return MarkerIconSource.values.firstWhere(
        (e) => e.toString().split('.').last == iconSourceStr,
        orElse: () => MarkerIconSource.defaultIcon,
      );
    }
    return MarkerIconSource.defaultIcon;
  }

  /// Unique identifier for the marker
  String id;

  /// Marker latitude
  double? latitude;

  /// Marker longitude
  double? longitude;

  /// Optional title text displayed with the marker
  String? title;

  /// Optional subtitle text displayed with the marker
  String? subtitle;

  /// Source type for the marker icon
  MarkerIconSource iconSource;

  /// Icon data (URL, asset path, or base64 string depending on iconSource)
  String? iconData;

  /// Optional participant/user avatar URL.
  ///
  /// When provided, the native implementations will render a circular marker
  /// that shows the avatar inside (with a dot/border style). If omitted, the
  /// marker falls back to the existing iconSource/iconData behavior.
  String? avatarUrl;

  /// Icon width in pixels (default: 40)
  int iconWidth;

  /// Icon height in pixels (default: 40)
  int iconHeight;

  /// Optional color for default icon tinting (0xRRGGBB format)
  int? color;

  /// Convert to JSON map for platform channel communication
  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'latitude': latitude,
      'longitude': longitude,
      if (title != null) 'title': title,
      if (subtitle != null) 'subtitle': subtitle,
      'iconSource': iconSource.toString().split('.').last,
      if (iconData != null) 'iconData': iconData,
      if (avatarUrl != null) 'avatarUrl': avatarUrl,
      'iconWidth': iconWidth,
      'iconHeight': iconHeight,
      if (color != null) 'color': color,
    };
  }

  @override
  String toString() {
    return 'MapMarker{id: $id, latitude: $latitude, longitude: $longitude}';
  }
}

