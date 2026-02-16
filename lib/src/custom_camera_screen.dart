import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'custom_camera_config.dart';
import 'custom_camera_controller.dart';
import 'custom_camera_widget.dart';

/// Complete camera screen widget with capture, retake, and save buttons
/// All UI is in Flutter, camera actions go to Android
class CustomCameraScreen extends StatefulWidget {
  /// Configuration for the camera
  final CustomCameraConfig config;

  /// Callback when image is captured
  final Function(Uint8List imageBytes)? onImageCaptured;

  /// Callback when save button is pressed
  /// Receives the captured image bytes
  final Function(Uint8List imageBytes)? onSave;

  /// Custom capture button widget
  final Widget? captureButton;

  /// Custom retake button widget
  final Widget? retakeButton;

  /// Custom save button widget
  final Widget? saveButton;

  /// Background color for the screen
  final Color backgroundColor;

  /// Background color for image preview overlay
  final Color previewOverlayColor;

  const CustomCameraScreen({
    super.key,
    this.config = const CustomCameraConfig(),
    this.onImageCaptured,
    this.onSave,
    this.captureButton,
    this.retakeButton,
    this.saveButton,
    this.backgroundColor = Colors.black,
    this.previewOverlayColor = Colors.black87,
  });

  @override
  State<CustomCameraScreen> createState() => _CustomCameraScreenState();
}

class _CustomCameraScreenState extends State<CustomCameraScreen> {
  final CustomCameraController _controller = CustomCameraController();
  bool _isCapturing = false;
  Uint8List? _capturedImage;

  @override
  void initState() {
    super.initState();
    _controller.onImageCaptured = (imageBytes) {
      setState(() {
        _capturedImage = imageBytes;
        _isCapturing = false;
      });
      widget.onImageCaptured?.call(imageBytes);
    };
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _captureImage() async {
    if (_isCapturing) return;

    setState(() {
      _isCapturing = true;
      _capturedImage = null;
    });

    try {
      final imageBytes = await _controller.captureImage();
      setState(() {
        _capturedImage = imageBytes;
        _isCapturing = false;
      });
      widget.onImageCaptured?.call(imageBytes);
    } catch (e) {
      setState(() {
        _isCapturing = false;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error capturing image: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  void _retakeImage() {
    setState(() {
      _capturedImage = null;
    });
  }

  void _saveImage() {
    if (_capturedImage != null) {
      widget.onSave?.call(_capturedImage!);
      // Clear preview after save so user can take another photo
      setState(() {
        _capturedImage = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // Use a key based on config to ensure widget rebuilds when config changes
    final configKey = ValueKey('camera_${widget.config.facing}_${widget.config.useManualFocus}_${widget.config.focusDistanceMeters}');
    
    return Scaffold(
      backgroundColor: widget.backgroundColor,
      body: SafeArea(
        child: Stack(
          children: [
            // Camera preview
            CustomCameraWidget(
              key: configKey,
              config: widget.config,
              controller: _controller,
              onImageCaptured: (imageBytes) {
                setState(() {
                  _capturedImage = imageBytes;
                });
                widget.onImageCaptured?.call(imageBytes);
              },
            ),
            // Image preview overlay
            if (_capturedImage != null) _buildImagePreview(),
            // Capture button (only show when no image is captured)
            if (_capturedImage == null) _buildCaptureButton(),
          ],
        ),
      ),
    );
  }

  Widget _buildCaptureButton() {
    if (widget.captureButton != null) {
      return Positioned(
        left: 0,
        right: 0,
        bottom: 0,
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.only(bottom: 20),
            child: Center(
              child: GestureDetector(
                onTap: _isCapturing ? null : _captureImage,
                behavior: HitTestBehavior.opaque,
                child: widget.captureButton!,
              ),
            ),
          ),
        ),
      );
    }

    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.only(bottom: 20),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                GestureDetector(
                  onTap: _isCapturing ? null : _captureImage,
                  child: AnimatedOpacity(
                    opacity: _isCapturing ? 0.7 : 1,
                    duration: const Duration(milliseconds: 200),
                    child: Container(
                      width: 72,
                      height: 72,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: Colors.white,
                        border: Border.all(
                          color: Colors.white70,
                          width: 4,
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.3),
                            blurRadius: 8,
                            offset: const Offset(0, 2),
                          ),
                        ],
                      ),
                      child: _isCapturing
                          ? const Padding(
                              padding: EdgeInsets.all(22.0),
                              child: CircularProgressIndicator(
                                strokeWidth: 2.5,
                                valueColor: AlwaysStoppedAnimation<Color>(Colors.black54),
                              ),
                            )
                          : Icon(
                              Icons.camera_alt_rounded,
                              size: 36,
                              color: Colors.black87,
                            ),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _isCapturing ? 'Capturing...' : 'Tap to capture',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.9),
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildImagePreview() {
    return Positioned.fill(
      child: Container(
        color: widget.previewOverlayColor,
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
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    // Retake button (wrap custom widget so tap triggers action)
                    widget.retakeButton != null
                        ? GestureDetector(
                            onTap: _retakeImage,
                            behavior: HitTestBehavior.opaque,
                            child: widget.retakeButton,
                          )
                        : _ActionButton(
                            icon: Icons.refresh_rounded,
                            label: 'Retake',
                            color: Colors.orange.shade700,
                            onPressed: _retakeImage,
                          ),
                    // Save button (wrap custom widget so tap triggers action)
                    widget.saveButton != null
                        ? GestureDetector(
                            onTap: _saveImage,
                            behavior: HitTestBehavior.opaque,
                            child: widget.saveButton,
                          )
                        : _ActionButton(
                            icon: Icons.check_circle_rounded,
                            label: 'Save',
                            color: Colors.green.shade700,
                            onPressed: _saveImage,
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
}

/// Styled action button for Retake / Save
class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onPressed;

  const _ActionButton({
    required this.icon,
    required this.label,
    required this.color,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: color,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, color: Colors.white, size: 22),
              const SizedBox(width: 8),
              Text(
                label,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
