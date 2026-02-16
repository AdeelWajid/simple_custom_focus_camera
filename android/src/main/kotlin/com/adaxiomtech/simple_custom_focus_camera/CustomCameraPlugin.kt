package com.adaxiomtech.simple_custom_focus_camera

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.platform.PlatformViewRegistry

interface PermissionManager {
    fun hasCameraPermission(): Boolean
    fun requestCameraPermission(result: (Boolean) -> Unit)
}

/** CustomCameraPlugin */
class CustomCameraPlugin : FlutterPlugin, ActivityAware, PluginRegistry.RequestPermissionsResultListener, PermissionManager {
    private lateinit var methodChannel: MethodChannel
    private var cameraViewInstance: CustomCameraView? = null
    private var activity: Activity? = null
    private val permissionListeners = mutableListOf<(Boolean) -> Unit>()
    private var isRequestingPermission = false

    companion object {
        private const val CAMERA_CHANNEL = "com.adaxiomtech.simple_custom_focus_camera/camera"
        private const val CAMERA_VIEW_TYPE = "custom_camera_view"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val messenger = binding.binaryMessenger
        val platformViewRegistry = binding.platformViewRegistry

        // Register platform view factory
        platformViewRegistry.registerViewFactory(
            CAMERA_VIEW_TYPE,
            CustomCameraViewFactory(this) { view ->
                cameraViewInstance = view
            }
        )

        // Setup method channel
        methodChannel = MethodChannel(messenger, CAMERA_CHANNEL)
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "configureCamera" -> {
                    val args = call.arguments as? Map<*, *>
                    if (args != null) {
                        cameraViewInstance?.configure(args)
                    }
                    result.success(null)
                }
                "initializeCamera" -> result.success(null)
                "disposeCamera" -> result.success(null)
                "captureImage" -> {
                    if (cameraViewInstance == null) {
                        result.error("CAMERA_NOT_READY", "Camera view not initialized", null)
                        return@setMethodCallHandler
                    }
                    cameraViewInstance?.captureImage { imageBytes ->
                        if (imageBytes != null) {
                            result.success(imageBytes)
                        } else {
                            result.error("CAPTURE_ERROR", "Failed to capture image", null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        cameraViewInstance = null
    }

    // ActivityAware implementation
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    // PermissionManager implementation
    override fun hasCameraPermission(): Boolean {
        val currentActivity = activity ?: return false
        return ContextCompat.checkSelfPermission(
            currentActivity,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestCameraPermission(result: (Boolean) -> Unit) {
        val currentActivity = activity
        if (currentActivity == null) {
            android.util.Log.e("CustomCameraPlugin", "Activity is null, cannot request permissions")
            result(false)
            return
        }

        if (hasCameraPermission()) {
            android.util.Log.d("CustomCameraPlugin", "Already has camera permission")
            result(true)
            return
        }

        permissionListeners.add(result)

        if (isRequestingPermission) {
            android.util.Log.d("CustomCameraPlugin", "Permission request already in progress, adding listener to queue")
            return
        }

        isRequestingPermission = true
        android.util.Log.d("CustomCameraPlugin", "Requesting camera permission")
        
        try {
            ActivityCompat.requestPermissions(
                currentActivity,
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } catch (e: Exception) {
            android.util.Log.e("CustomCameraPlugin", "Error requesting permissions", e)
            isRequestingPermission = false
            notifyListeners(false)
        }
    }

    // RequestPermissionsResultListener implementation
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            isRequestingPermission = false
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            android.util.Log.d("CustomCameraPlugin", "Permission result: granted=$granted")
            notifyListeners(granted)
            return true
        }
        return false
    }

    private fun notifyListeners(granted: Boolean) {
        val listeners = ArrayList(permissionListeners)
        permissionListeners.clear()
        listeners.forEach { it.invoke(granted) }
    }
}
