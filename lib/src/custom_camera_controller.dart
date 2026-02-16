import 'dart:typed_data';
import 'package:flutter/services.dart';

/// Controller for CustomCamera widget
class CustomCameraController {
  static const MethodChannel _channel =
      MethodChannel('com.adaxiomtech.simple_custom_focus_camera/camera');

  /// Callback when image is captured
  Function(Uint8List imageBytes)? onImageCaptured;

  /// Initialize the camera
  Future<void> initialize() async {
    try {
      await _channel.invokeMethod('initializeCamera');
    } catch (e) {
      throw Exception('Failed to initialize camera: $e');
    }
  }

  /// Capture an image
  /// Returns the captured image as Uint8List
  Future<Uint8List> captureImage() async {
    try {
      final result = await _channel.invokeMethod<Uint8List>('captureImage');
      if (result == null) {
        throw Exception('Failed to capture image: returned null');
      }
      return result;
    } catch (e) {
      throw Exception('Failed to capture image: $e');
    }
  }

  /// Dispose camera resources
  Future<void> dispose() async {
    try {
      await _channel.invokeMethod('disposeCamera');
    } catch (e) {
      // Ignore errors during disposal
    }
  }
}
