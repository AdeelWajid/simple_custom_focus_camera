# simple_custom_focus_camera

A Flutter package for custom camera functionality with native Android implementation. This package is specifically designed for use cases requiring manual focus control (e.g., locking focus to a near distance) and provides a highly-optimized camera preview that avoids common stretching and cropping issues.

![Preview](demo.gif)

## Features

- **Aspect-Ratio Fixed Preview**: Automatically handles various camera sensor orientations and aspect ratios to ensure the preview is never stretched or skewed.
- **Center-Fit Transform**: The preview fits the view perfectly without the common width/height cropping issues.
- **Configurable Focus Distance**: Lock the camera to a specific focus distance (in meters), ideal for scanning or close-up photography.
- **Manual Focus Support**: Toggle between continuous auto-focus and manual focus.
- **Front/Back Camera Selection**: Easily switch between front and back-facing cameras.
- **Still Image Capture**: Directly capture images from the camera stream.

## Installation

Add `simple_custom_focus_camera` to your `pubspec.yaml`:

```yaml
dependencies:
  simple_custom_focus_camera: ^1.0.0
```

## Android Setup

Ensure your `minSdkVersion` is at least **21** (Required for Camera2 API).

Add the following permission to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## Usage

### Simple Implementation

```dart
import 'package:flutter/material.dart';
import 'package:simple_custom_focus_camera/simple_custom_focus_camera.dart';

class CameraScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: CustomCameraView(
        facing: 'back',
        useManualFocus: true,
        focusDistanceMeters: 0.1, // Focus distance in meters
        onImageCaptured: (Uint8List bytes) {
          // Handle captured image
        },
      ),
    );
  }
}
```

### Advanced Control

You can use the `CustomCameraView` to configure settings dynamically:

```dart
// Example of dynamic configuration via MethodChannel or controller (depending on plugin implementation)
```

## How it works

This plugin addresses a common problem in Android Camera2 implementations where the `TextureView` preview appears stretched or cropped when the view aspect ratio doesn't match the sensor's supported output sizes. It specifically calculates a `fit-inside` transform matrix and selects optimal preview sizes based on the actual `TextureView` dimensions, accounting for sensor rotation.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
