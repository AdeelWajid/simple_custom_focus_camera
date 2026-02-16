import 'dart:io';
import 'dart:typed_data';

import 'package:simple_custom_focus_camera/simple_custom_focus_camera.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Custom Camera Example',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const CameraExamplePage(),
    );
  }
}

class CameraExamplePage extends StatefulWidget {
  const CameraExamplePage({super.key});

  @override
  State<CameraExamplePage> createState() => _CameraExamplePageState();
}

class _CameraExamplePageState extends State<CameraExamplePage> {
  CameraFacing _currentFacing = CameraFacing.back;
  double? _focusDistance = 0.1; // meters
  bool _useManualFocus = false;

  void _toggleCameraFacing() {
    setState(() {
      _currentFacing = _currentFacing == CameraFacing.back
          ? CameraFacing.front
          : CameraFacing.back;
    });
  }

  /// Toggles the camera focus mode between auto and manual.
  ///
  /// When manual focus is enabled, the user can adjust the focus distance
  /// by dragging the focus slider. When auto focus is enabled, the camera
  /// will automatically adjust the focus distance based on the scene.
  void _toggleFocusMode() {
    setState(() {
      _useManualFocus = !_useManualFocus;
    });
  }

  void _handleImageCaptured(Uint8List imageBytes) {
    debugPrint('Image captured: ${imageBytes.length} bytes');
    // Handle captured image here
  }

  void _handleSave(Uint8List imageBytes) async {
    debugPrint('Save requested: ${imageBytes.length} bytes');
    // save image logic
    final dict = await getApplicationDocumentsDirectory();
    final path = dict.path;
    final file = File('$path/captured_image.jpg');
    file
        .writeAsBytes(imageBytes)
        .then((_) {
          debugPrint('Image saved to ${file.path}');
        })
        .catchError((e) {
          debugPrint('Error saving image: $e');
        });

    //show image in dialog
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Captured Image'),
        content: Image.memory(imageBytes),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final config = _useManualFocus && _focusDistance != null
        ? CustomCameraConfig.manualFocus(
            focusDistanceMeters: _focusDistance!,
            facing: _currentFacing,
          )
        : CustomCameraConfig.autoFocus(facing: _currentFacing);

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // Complete camera screen with all UI in Flutter
          CustomCameraScreen(
            config: config,
            captureButton: const Icon(
              Icons.camera,
              color: Colors.white,
              size: 64,
            ),
            retakeButton: const Icon(
              Icons.refresh,
              color: Colors.white,
              size: 48,
            ),

            saveButton: const Icon(Icons.save, color: Colors.white, size: 48),
            onImageCaptured: _handleImageCaptured,
            onSave: _handleSave,
          ),

          //focus slider overlay
          if (_useManualFocus)
            Positioned(
              bottom: 32,
              left: 16,
              right: 16,
              child: Column(
                children: [
                  const Text(
                    'Focus Distance (meters)',
                    style: TextStyle(color: Colors.white),
                  ),
                  Slider(
                    value: _focusDistance ?? 0.1,
                    min: 0.1,
                    max: 10.0,
                    divisions: 100,
                    label: '${_focusDistance?.toStringAsFixed(1)} m',
                    onChanged: (value) {
                      setState(() {
                        _focusDistance = value;
                      });
                    },
                  ),
                ],
              ),
            ),
          // Controls overlay
          Positioned(
            top: 16,
            left: 16,
            right: 16,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                IconButton(
                  icon: Icon(
                    _currentFacing == CameraFacing.back
                        ? Icons.camera_front
                        : Icons.camera_rear,
                    color: Colors.white,
                  ),
                  onPressed: _toggleCameraFacing,
                ),
                Row(
                  children: [
                    Text(
                      _useManualFocus ? 'Manual Focus' : 'Auto Focus',
                      style: const TextStyle(color: Colors.white),
                    ),
                    Switch(
                      value: _useManualFocus,
                      onChanged: (_) => _toggleFocusMode(),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
