/// Configuration class for CustomCamera
class CustomCameraConfig {
  /// Camera facing direction
  final CameraFacing facing;

  /// Focus distance in meters (null for auto-focus)
  /// Set to a value like 0.1 for near focus, or null for auto-focus
  final double? focusDistanceMeters;

  /// Whether to use manual focus mode
  /// If true and focusDistanceMeters is set, uses manual focus
  /// If false or focusDistanceMeters is null, uses auto-focus
  final bool useManualFocus;

  const CustomCameraConfig({
    this.facing = CameraFacing.back,
    this.focusDistanceMeters,
    this.useManualFocus = false,
  });

  /// Creates a config with auto-focus
  const CustomCameraConfig.autoFocus({
    this.facing = CameraFacing.back,
  })  : focusDistanceMeters = null,
        useManualFocus = false;

  /// Creates a config with manual focus at specified distance
  const CustomCameraConfig.manualFocus({
    required this.focusDistanceMeters,
    this.facing = CameraFacing.back,
  })  : useManualFocus = true;

  Map<String, dynamic> toMap() {
    return {
      'facing': facing == CameraFacing.front ? 'front' : 'back',
      'focusDistanceMeters': focusDistanceMeters,
      'useManualFocus': useManualFocus,
    };
  }
}

/// Camera facing direction
enum CameraFacing {
  front,
  back,
}
