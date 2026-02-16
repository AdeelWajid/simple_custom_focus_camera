import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'custom_camera_config.dart';
import 'custom_camera_controller.dart';

/// Custom camera widget that displays native Android camera preview
class CustomCameraWidget extends StatefulWidget {
  /// Configuration for the camera
  final CustomCameraConfig config;

  /// Controller for camera operations
  final CustomCameraController? controller;

  /// Callback when image is captured
  final Function(Uint8List imageBytes)? onImageCaptured;

  /// Widget to display when camera is loading
  final Widget? loadingWidget;

  /// Widget to display when camera error occurs
  final Widget Function(String error)? errorWidget;

  const CustomCameraWidget({
    super.key,
    this.config = const CustomCameraConfig(),
    this.controller,
    this.onImageCaptured,
    this.loadingWidget,
    this.errorWidget,
  });

  @override
  State<CustomCameraWidget> createState() => _CustomCameraWidgetState();
}

class _CustomCameraWidgetState extends State<CustomCameraWidget> {
  static const String _cameraChannel = 'com.adaxiomtech.simple_custom_focus_camera/camera';
  static const String _cameraViewType = 'custom_camera_view';
  static const MethodChannel _platform =
      MethodChannel(_cameraChannel);

  bool _isInitialized = false;
  String? _errorMessage;
  late CustomCameraController _controller;

  @override
  void initState() {
    super.initState();
    _controller = widget.controller ?? CustomCameraController();
    if (widget.onImageCaptured != null) {
      _controller.onImageCaptured = widget.onImageCaptured;
    }
    _initializeCamera();
  }

  @override
  void didUpdateWidget(CustomCameraWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    // If camera facing changed, we need to recreate the view.
    // Do NOT call _initializeCamera() here - the old view is disposed when we set
    // _isInitialized = false, so method channel calls would hit a disposed view.
    // The new AndroidView will be created with creationParams (new config) and
    // will open the correct camera from the start.
    if (oldWidget.config.facing != widget.config.facing) {
      setState(() {
        _isInitialized = false;
      });
      // Delay before showing new view so the old camera is fully released.
      // Some devices (e.g. Xiaomi) need more time before opening the other camera.
      Future.delayed(const Duration(milliseconds: 500), () {
        if (mounted) {
          setState(() {
            _isInitialized = true;
            _errorMessage = null;
          });
        }
      });
    } else if (oldWidget.config.focusDistanceMeters != widget.config.focusDistanceMeters ||
        oldWidget.config.useManualFocus != widget.config.useManualFocus) {
      // Only focus settings changed - reconfigure without recreating
      _reconfigureCamera();
    }
    // Update controller callback if changed
    if (widget.onImageCaptured != null) {
      _controller.onImageCaptured = widget.onImageCaptured;
    }
  }

  Future<void> _initializeCamera() async {
    try {
      // Send configuration to native side
      await _platform.invokeMethod('configureCamera', widget.config.toMap());
      await _platform.invokeMethod('initializeCamera');
      if (mounted) {
        setState(() {
          _isInitialized = true;
          _errorMessage = null;
        });
      }
    } on PlatformException catch (e) {
      _handleError('Camera initialization failed: ${e.message}');
    } catch (e) {
      _handleError('An unexpected error occurred: $e');
    }
  }

  Future<void> _reconfigureCamera() async {
    try {
      // Send new configuration to native side
      await _platform.invokeMethod('configureCamera', widget.config.toMap());
      debugPrint('Camera reconfigured: facing=${widget.config.facing}, manualFocus=${widget.config.useManualFocus}, distance=${widget.config.focusDistanceMeters}');
    } on PlatformException catch (e) {
      _handleError('Camera reconfiguration failed: ${e.message}');
    } catch (e) {
      _handleError('An unexpected error occurred during reconfiguration: $e');
    }
  }

  void _handleError(String message) {
    if (mounted) {
      setState(() {
        _errorMessage = message;
        _isInitialized = false;
      });
    }
    debugPrint('CustomCamera error: $message');
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_errorMessage != null) {
      if (widget.errorWidget != null) {
        return widget.errorWidget!(_errorMessage!);
      }
      return _buildErrorWidget();
    }

    if (!_isInitialized) {
      return widget.loadingWidget ?? _buildLoadingWidget();
    }

    return _buildCameraView();
  }

  Widget _buildLoadingWidget() {
    return const Center(
      child: CircularProgressIndicator(
        color: Colors.white,
      ),
    );
  }

  Widget _buildErrorWidget() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(
            Icons.error_outline,
            color: Colors.white,
            size: 64,
          ),
          const SizedBox(height: 16),
          Text(
            _errorMessage ?? 'Unknown error',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 16,
            ),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Widget _buildCameraView() {
    // Use a key based on camera facing to force recreation when camera changes
    // Focus changes don't require view recreation
    final viewKey = ValueKey('camera_${widget.config.facing}');
    
    return SizedBox.expand(
      child: AndroidView(
        key: viewKey,
        viewType: _cameraViewType,
        creationParams: widget.config.toMap(),
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: (int id) {
          debugPrint('CustomCamera AndroidView created with id: $id, config: ${widget.config.toMap()}');
        },
      ),
    );
  }
}
