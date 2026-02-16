import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Main entry point of the application
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const CameraApp());
}

/// Main camera application widget
class CameraApp extends StatefulWidget {
  const CameraApp({super.key});

  @override
  State<CameraApp> createState() => _CameraAppState();
}

class _CameraAppState extends State<CameraApp> {
  static const String _cameraChannel = 'com.adaxiomtech.simple_custom_focus_camera/camera';
  static const String _cameraViewType = 'custom_camera_view';
  static const MethodChannel _platform = MethodChannel(_cameraChannel);

  bool _isInitialized = false;
  String? _errorMessage;
  bool _isCapturing = false;
  Uint8List? _capturedImage;

  @override
  void initState() {
    super.initState();
    _initializeCamera();
  }

  /// Initializes the camera through platform channel
  Future<void> _initializeCamera() async {
    try {
      await _platform.invokeMethod('initializeCamera');
      if (mounted) {
        setState(() {
          _isInitialized = true;
          _errorMessage = null;
        });
      }
    } on PlatformException catch (e) {
      _handleInitializationError(e);
    } catch (e) {
      _handleGeneralError(e);
    }
  }

  /// Handles platform-specific exceptions during initialization
  void _handleInitializationError(PlatformException e) {
    if (mounted) {
      setState(() {
        _errorMessage = 'Camera initialization failed: ${e.message}';
        _isInitialized = false;
      });
    }
    debugPrint('Camera initialization error: ${e.message}');
  }

  /// Handles general errors during initialization
  void _handleGeneralError(Object error) {
    if (mounted) {
      setState(() {
        _errorMessage = 'An unexpected error occurred';
        _isInitialized = false;
      });
    }
    debugPrint('General error: $error');
  }

  @override
  void dispose() {
    _disposeCamera();
    super.dispose();
  }

  /// Disposes camera resources through platform channel
  Future<void> _disposeCamera() async {
    try {
      await _platform.invokeMethod('disposeCamera');
    } catch (e) {
      debugPrint('Error disposing camera: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_errorMessage != null) {
      return _buildErrorScreen();
    }

    if (!_isInitialized) {
      return _buildLoadingScreen();
    }

    return _buildCameraScreen();
  }

  /// Builds the loading screen shown during camera initialization
  Widget _buildLoadingScreen() {
    return const MaterialApp(
      home: Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: CircularProgressIndicator(
            color: Colors.white,
          ),
        ),
      ),
    );
  }

  /// Builds the error screen shown when camera initialization fails
  Widget _buildErrorScreen() {
    return MaterialApp(
      home: Scaffold(
        backgroundColor: Colors.black,
        body: Center(
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
        ),
      ),
    );
  }

  /// Builds the main camera preview screen
  Widget _buildCameraScreen() {
    return MaterialApp(
      home: Scaffold(
        backgroundColor: Colors.black,
        body: SafeArea(
          child: Stack(
            children: [
              _buildCameraView(),
              if (_capturedImage != null) _buildCapturedImageOverlay(),
              if (_capturedImage == null) _buildCaptureButton(),
            ],
          ),
        ),
      ),
    );
  }

  /// Builds the Android native camera view
  Widget _buildCameraView() {
    return SizedBox.expand(
      child: AndroidView(
        viewType: _cameraViewType,
        creationParams: <String, dynamic>{},
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: (int id) {
          debugPrint('AndroidView created with id: $id');
        },
      ),
    );
  }

  /// Builds the capture button
  Widget _buildCaptureButton() {
    return Positioned(
      bottom: 20,
      left: 0,
      right: 0,
      child: Center(
        child: GestureDetector(
          onTap: _isCapturing ? null : _captureImage,
          child: Container(
            width: 70,
            height: 70,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.white,
              border: Border.all(
                color: Colors.grey.shade300,
                width: 4,
              ),
            ),
            child: _isCapturing
                ? const Padding(
                    padding: EdgeInsets.all(20.0),
                    child: CircularProgressIndicator(
                      strokeWidth: 3,
                      valueColor: AlwaysStoppedAnimation<Color>(Colors.black),
                    ),
                  )
                : Container(
                    margin: const EdgeInsets.all(8),
                    decoration: const BoxDecoration(
                      shape: BoxShape.circle,
                      color: Colors.white,
                    ),
                  ),
          ),
        ),
      ),
    );
  }

  /// Builds overlay to show captured image
  Widget _buildCapturedImageOverlay() {
    return Positioned.fill(
      child: Container(
        color: Colors.black87,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Image.memory(
                    _capturedImage!,
                    fit: BoxFit.contain,
                  ),
                ),
              ),
            ),
            SafeArea(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 20.0),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    ElevatedButton.icon(
                      onPressed: () {
                        setState(() {
                          _capturedImage = null;
                        });
                      },
                      icon: const Icon(Icons.close),
                      label: const Text('Retake'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.red,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 24,
                          vertical: 12,
                        ),
                      ),
                    ),
                    ElevatedButton.icon(
                      onPressed: _saveImage,
                      icon: const Icon(Icons.save),
                      label: const Text('Save'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.green,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 24,
                          vertical: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Captures an image from the camera
  Future<void> _captureImage() async {
    if (_isCapturing) return;

    setState(() {
      _isCapturing = true;
      _capturedImage = null;
    });

    try {
      final result = await _platform.invokeMethod<Uint8List>('captureImage');
      
      if (mounted) {
        setState(() {
          _isCapturing = false;
          if (result != null) {
            _capturedImage = result;
            debugPrint('Image captured successfully: ${result.length} bytes');
          } else {
            _errorMessage = 'Failed to capture image';
          }
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _isCapturing = false;
          _errorMessage = 'Capture error: ${e.message}';
        });
      }
      debugPrint('Capture error: ${e.message}');
    } catch (e) {
      if (mounted) {
        setState(() {
          _isCapturing = false;
          _errorMessage = 'Unexpected error: $e';
        });
      }
      debugPrint('Unexpected error: $e');
    }
  }

  /// Saves the captured image (placeholder - implement actual save logic)
  void _saveImage() {
    if (_capturedImage == null) return;
    
    // TODO: Implement actual image saving logic
    // You can use packages like path_provider and image_gallery_saver
    debugPrint('Saving image: ${_capturedImage!.length} bytes');
    
    // For now, just clear the captured image
    setState(() {
      _capturedImage = null;
    });
    
    // Note: To show a snackbar, you would need to pass BuildContext
    // or use a GlobalKey<ScaffoldState>
  }
}
