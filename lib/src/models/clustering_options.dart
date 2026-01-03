/// Configuration options for marker clustering behavior
class ClusteringOptions {
  /// Constructor
  ClusteringOptions({
    this.enabled = true,
    this.clusterRadius = 50,
    this.clusterMaxZoom = 14,
  });

  /// Create [ClusteringOptions] from a JSON map
  ClusteringOptions.fromJson(Map<String, dynamic> json)
      : enabled = json['enabled'] as bool? ?? true,
        clusterRadius = json['clusterRadius'] as int? ?? 50,
        clusterMaxZoom = json['clusterMaxZoom'] as int? ?? 14;

  /// Whether clustering is enabled (default: true)
  bool enabled;

  /// Cluster radius in pixels (default: 50)
  int clusterRadius;

  /// Maximum zoom level at which clustering occurs (default: 14)
  /// Above this zoom level, markers are shown individually
  int clusterMaxZoom;

  /// Convert to JSON map for platform channel communication
  Map<String, dynamic> toMap() {
    return {
      'enabled': enabled,
      'clusterRadius': clusterRadius,
      'clusterMaxZoom': clusterMaxZoom,
    };
  }

  @override
  String toString() {
    return 'ClusteringOptions{enabled: $enabled, radius: $clusterRadius, maxZoom: $clusterMaxZoom}';
  }
}

