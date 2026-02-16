package com.adaxiomtech.simple_custom_focus_camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import kotlin.math.abs
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.nio.ByteBuffer

/**
 * Main activity for the Flutter application.
 * This is kept for the example app. The plugin code is in CustomCameraPlugin.kt
 */
class MainActivity : FlutterActivity() {
    companion object {
        private const val CAMERA_CHANNEL = "com.adaxiomtech.simple_custom_focus_camera/camera"
        private const val CAMERA_VIEW_TYPE = "custom_camera_view"
    }

    private var cameraViewInstance: CustomCameraView? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        setupMethodChannel(flutterEngine)
        registerCameraViewFactory(flutterEngine)
    }

    /**
     * Sets up the method channel for camera control communication.
     */
    private fun setupMethodChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CAMERA_CHANNEL)
            .setMethodCallHandler { call, result ->
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

    /**
     * Registers the custom camera view factory with Flutter.
     */
    private fun registerCameraViewFactory(flutterEngine: FlutterEngine) {
        // This MainActivity is used for the example app, so we need a dummy permission manager
        // or one that interacts with this activity. 
        // ideally the plugin registers the factory, not the activity.
        // But since this code is in MainActivity.kt AND CustomCameraPlugin.kt, 
        // we are likely editing the library code which confusingly has a MainActivity.kt.
        // The Plugin registers the factory in CustomCameraPlugin.kt, so this method 
        // in 'MainActivity' (likely just a file name) might be dead code or used in example.
        // However, the CustomCameraView class IS defined here.
    }
}

/**
 * Factory class for creating custom camera view instances.
 */
class CustomCameraViewFactory(
    private val permissionManager: PermissionManager?,
    private val onViewCreated: (CustomCameraView) -> Unit
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val cameraView = CustomCameraView(context, viewId, permissionManager, args as? Map<*, *>)
        onViewCreated(cameraView)
        // Check permissions immediately upon creation
        cameraView.checkPermissionAndOpen()
        return cameraView
    }
}

/**
 * Custom camera view implementation using Android Camera2 API.
 * Provides manual focus control locked to near focus distance.
 */
class CustomCameraView(
    private val context: Context,
    private val viewId: Int,
    private val permissionManager: PermissionManager?,
    private val initialArgs: Map<*, *>? = null
) : PlatformView, TextureView.SurfaceTextureListener {

    companion object {
        private const val BACKGROUND_THREAD_NAME = "CameraBackground"
    }
    
    private var cameraId: String = "0"
    private var focusDistanceMeters: Float? = null
    private var useManualFocus: Boolean = false
    
    private var previewSize: Size? = null
    private var sensorOrientation: Int = 0 // Ensure this is here
    private var previewSurface: Surface? = null

    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler: Handler = Handler(context.mainLooper)
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var imageReader: ImageReader? = null
    private var captureImageCallback: ((ByteArray?) -> Unit)? = null
    private var isOpeningCamera: Boolean = false

    init {
        // Parse initial configuration
        initialArgs?.let { parseConfiguration(it) }
        initializeTextureView()
        startBackgroundThread()
    }

    fun checkPermissionAndOpen() {
        android.util.Log.d("CustomCamera", "checkPermissionAndOpen called")
        if (permissionManager == null) {
            android.util.Log.w("CustomCamera", "PermissionManager is null")
            // Fallback for when used without plugin (e.g. direct)
            if (hasCameraPermission()) {
                openCamera()
            } else {
                android.util.Log.e("CustomCamera", "No permission manager and no permission")
            }
            return
        }

        if (permissionManager.hasCameraPermission()) {
            android.util.Log.d("CustomCamera", "Permission already granted, opening camera")
            openCamera()
        } else {
            android.util.Log.d("CustomCamera", "Requesting permission via manager")
            permissionManager.requestCameraPermission { granted ->
                android.util.Log.d("CustomCamera", "Permission callback: granted=$granted")
                if (granted) {
                    // Add a small delay to ensure the activity is fully resumed and surface is ready
                    mainHandler.postDelayed({
                         val isAvailable = textureView?.isAvailable == true
                         android.util.Log.d("CustomCamera", "Posting openCamera delayed. TextureView available: $isAvailable")
                         if (isAvailable) {
                            openCamera()
                        } else {
                            android.util.Log.w("CustomCamera", "TextureView not available, waiting for surface available callback")
                        }
                    }, 500)
                } else {
                    android.util.Log.e("CustomCamera", "Camera permission denied")
                }
            }
        }
    }
    
    // ... (rest of configuration methods)

    // ...



    /**
     * Configures the camera with new settings.
     */
    fun configure(args: Map<*, *>) {
        android.util.Log.d("CustomCamera", "Configuring camera with new settings")
        val oldCameraId = cameraId
        parseConfiguration(args)
        val cameraChanged = oldCameraId != cameraId
        
        // If camera facing changed, we need to close and reopen
        // If only focus changed, we can update the preview session
        if (cameraDevice != null) {
            if (cameraChanged) {
                android.util.Log.d("CustomCamera", "Camera ID changed ($oldCameraId -> $cameraId), closing and reopening")
                // Close camera synchronously to ensure cleanup
                closeCamera()
                // Wait a bit longer to ensure camera is fully released before reopening
                mainHandler.postDelayed({
                    // Double-check camera is still closed before reopening
                    if (cameraDevice == null) {
                        checkPermissionAndOpen()
                    } else {
                        android.util.Log.w("CustomCamera", "Camera device still exists, skipping reopen")
                    }
                }, 200)
            } else {
                android.util.Log.d("CustomCamera", "Only focus settings changed, updating preview session")
                // Update the preview session with new focus settings
                updatePreviewFocus()
            }
        } else {
            android.util.Log.d("CustomCamera", "Camera not open yet, will use new config on next open")
        }
    }
    
    /**
     * Updates the preview session with new focus settings without recreating the session.
     */
    private fun updatePreviewFocus() {
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder?.addTarget(surface)
            configureManualFocus(builder ?: return)
            
            val request = builder.build()
            session.setRepeatingRequest(request, null, backgroundHandler)
            android.util.Log.d("CustomCamera", "Preview focus updated")
        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error updating preview focus", e)
        }
    }

    /**
     * Parses configuration from Flutter arguments.
     */
    private fun parseConfiguration(args: Map<*, *>) {
        val facing = args["facing"] as? String
        val focusDistance = args["focusDistanceMeters"] as? Double
        val useManual = args["useManualFocus"] as? Boolean ?: false

        android.util.Log.d("CustomCamera", "Parsing config: facing=$facing, focusDistance=$focusDistance, useManual=$useManual")

        // Find camera ID based on facing direction
        val newCameraId = findCameraId(facing == "front")
        val cameraChanged = newCameraId != cameraId
        cameraId = newCameraId
        
        android.util.Log.d("CustomCamera", "Camera ID: $cameraId (changed: $cameraChanged)")
        
        // Set focus configuration
        focusDistanceMeters = focusDistance?.toFloat()
        useManualFocus = useManual && focusDistanceMeters != null
        
        android.util.Log.d("CustomCamera", "Focus config: useManualFocus=$useManualFocus, focusDistanceMeters=$focusDistanceMeters")
    }

    /**
     * Finds the camera ID based on facing direction.
     */
    private fun findCameraId(isFront: Boolean): String {
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null) {
                    val isFrontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT
                    if (isFront == isFrontFacing) {
                        return id
                    }
                }
            }
            // Fallback to first available camera
            return if (cameraIds.isNotEmpty()) cameraIds[0] else "0"
        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error finding camera ID", e)
            return "0"
        }
    }

    override fun getView(): View = textureView ?: TextureView(context)

    override fun dispose() {
        closeCamera()
        stopBackgroundThread()
    }

    // SurfaceTextureListener callbacks
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        android.util.Log.d("CustomCamera", "Surface texture available: $width x $height")
        // Delay opening so the previous camera can fully release (helps on Xiaomi etc.)
        mainHandler.postDelayed({
            if (textureView?.isAvailable == true) {
                checkPermissionAndOpen()
            }
        }, 300)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // Reconfigure transform when view size changes
        // Must run on main thread - TextureView operations require UI thread
        mainHandler.postDelayed({
            configureTextureViewTransform()
        }, 100)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // No action needed on texture update
    }

    /**
     * Initializes the TextureView and sets up the surface texture listener.
     */
    private fun initializeTextureView() {
        textureView = TextureView(context).apply {
            surfaceTextureListener = this@CustomCameraView
            // Ensure the view is visible and can render
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    /**
     * Configures the TextureView transform matrix to properly scale the preview.
     * This ensures the preview fills the screen without distortion (center crop).
     * This is optional - if it fails, preview will still work.
     */
    private fun configureTextureViewTransform() {
        try {
            val view = textureView ?: run {
                android.util.Log.w("CustomCamera", "TextureView is null, skipping transform")
                return
            }

            val size = previewSize ?: run {
                android.util.Log.w("CustomCamera", "Preview size is null, skipping transform")
                return
            }

            // Ensure we're on the main thread
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { configureTextureViewTransform() }
                return
            }

            val viewWidth = view.width
            val viewHeight = view.height

            if (viewWidth == 0 || viewHeight == 0) {
                android.util.Log.w("CustomCamera", "View dimensions are zero, skipping transform")
                return
            }

            val previewWidth = size.width
            val previewHeight = size.height

            if (previewWidth == 0 || previewHeight == 0) {
                android.util.Log.w("CustomCamera", "Preview size is zero, skipping transform")
                return
            }

            android.util.Log.d("CustomCamera", "Configuring transform: view=$viewWidth x $viewHeight, preview=$previewWidth x $previewHeight, sensorOrientation=$sensorOrientation")

            // When sensor orientation is 90° or 270°, the preview is rotated
            // We need to swap width/height for correct aspect ratio calculation
            val needsRotation = sensorOrientation == 90 || sensorOrientation == 270
            val effectivePreviewWidth = if (needsRotation) previewHeight else previewWidth
            val effectivePreviewHeight = if (needsRotation) previewWidth else previewHeight

            // Calculate aspect ratios using effective dimensions
            val viewAspect = viewWidth.toFloat() / viewHeight
            val previewAspect = effectivePreviewWidth.toFloat() / effectivePreviewHeight

            android.util.Log.d("CustomCamera", "Effective preview after rotation: $effectivePreviewWidth x $effectivePreviewHeight")
            android.util.Log.d("CustomCamera", "Aspect ratios: view=$viewAspect, preview=$previewAspect")

            // Create transform matrix
            val matrix = Matrix()

            // Calculate scale factors using effective dimensions
            val scaleX = viewWidth.toFloat() / effectivePreviewWidth
            val scaleY = viewHeight.toFloat() / effectivePreviewHeight
            
            // Use the MINIMUM scale for both dimensions to fit without cropping or stretching
            // This maintains aspect ratio and shows the full preview
            val scale = scaleX.coerceAtMost(scaleY)

            // Center the preview using effective dimensions
            val scaledWidth = effectivePreviewWidth * scale
            val scaledHeight = effectivePreviewHeight * scale
            val dx = (viewWidth - scaledWidth) / 2f
            val dy = (viewHeight - scaledHeight) / 2f

            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)

            // Apply the transform
            view.setTransform(matrix)
            android.util.Log.d("CustomCamera", "Transform applied: scale=$scale, dx=$dx, dy=$dy")


        } catch (e: Exception) {
            // If transform fails, preview will still show without scaling
            android.util.Log.e("CustomCamera", "Error configuring transform (non-fatal)", e)
        }
    }

    /**
     * Starts the background thread for camera operations.
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(BACKGROUND_THREAD_NAME).apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    /**
     * Stops the background thread safely.
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /**
     * Opens the camera device and initializes the preview session.
     */
    private fun openCamera() {
        if (!hasCameraPermission()) {
            android.util.Log.e("CustomCamera", "Cannot open camera - permission missing")
            return
        }

        try {
            cameraManager.openCamera(
                cameraId,
                createCameraStateCallback(),
                mainHandler // Use main handler for state callbacks to ensure serial execution with checkPermissionAndOpen
            )
        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error opening camera", e)
            e.printStackTrace()
            isOpeningCamera = false
        }
    }

    /**
     * Checks if the app has camera permission.
     */
    private fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Creates a callback for handling camera device state changes.
     */
    private fun createCameraStateCallback(): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                android.util.Log.d("CustomCamera", "Camera onOpened: ${camera.id}")
                cameraDevice = camera
                createCameraPreviewSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                android.util.Log.w("CustomCamera", "Camera onDisconnected: ${camera.id}")
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                android.util.Log.e("CustomCamera", "Camera onError: ${camera.id}, error: $error")
                camera.close()
                cameraDevice = null
            }
        }
    }

    /**
     * Creates and configures the camera preview session with manual focus.
     */
    private fun createCameraPreviewSession() {
        android.util.Log.d("CustomCamera", "createCameraPreviewSession starting")
        val surface = createPreviewSurface()
        if (surface == null) {
            android.util.Log.e("CustomCamera", "createPreviewSurface returned null")
            return
        }
        
        val captureRequestBuilder = try {
            createCaptureRequestBuilder(surface)
        } catch (e: CameraAccessException) {
            android.util.Log.e("CustomCamera", "Failed to create capture request builder", e)
            closeCamera()
            return
        }

        if (captureRequestBuilder == null) {
            android.util.Log.e("CustomCamera", "createCaptureRequestBuilder returned null")
            return
        }

        try {
            configureManualFocus(captureRequestBuilder)
            setupImageReader()
            startPreviewSession(surface, captureRequestBuilder)
        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error setting up preview session", e)
            closeCamera()
        }
    }
    
    /**
     * Sets up ImageReader for capturing still images.
     */
    private fun setupImageReader() {
        val characteristics = getCameraCharacteristics()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG)
        val largestSize = sizes.maxByOrNull { it.width * it.height } ?: return

        imageReader = ImageReader.newInstance(
            largestSize.width,
            largestSize.height,
            ImageFormat.JPEG,
            1
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let { processCapturedImage(it) }
            }, backgroundHandler)
        }
    }

    /**
     * Processes the captured image and converts it to ByteArray.
     */
    private fun processCapturedImage(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            mainHandler.post {
                captureImageCallback?.invoke(bytes)
                captureImageCallback = null
            }
        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error processing captured image", e)
            mainHandler.post {
                captureImageCallback?.invoke(null)
                captureImageCallback = null
            }
        }
    }

    /**
     * Captures an image and returns it via callback.
     */
    fun captureImage(callback: (ByteArray?) -> Unit) {
        if (cameraDevice == null || captureSession == null) {
            android.util.Log.e("CustomCamera", "Camera not ready for capture")
            callback(null)
            return
        }

        val imageReaderSurface = imageReader?.surface ?: run {
            android.util.Log.e("CustomCamera", "ImageReader not initialized")
            callback(null)
            return
        }

        captureImageCallback = callback

        try {
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ) ?: run {
                android.util.Log.e("CustomCamera", "Failed to create capture request")
                callback(null)
                return
            }

            captureRequestBuilder.addTarget(imageReaderSurface)
            captureRequestBuilder.addTarget(previewSurface!!)

            // Configure focus for capture
            val characteristics = getCameraCharacteristics()
            if (useManualFocus && isManualFocusSupported(characteristics) && focusDistanceMeters != null) {
                val focusDistance = calculateFocusDistance(characteristics, focusDistanceMeters!!)
                if (focusDistance > 0f) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                }
            }

            // Set JPEG orientation
            val jpegOrientation = getJpegOrientation(characteristics)
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            val captureRequest = captureRequestBuilder.build()
            captureSession?.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    android.util.Log.d("CustomCamera", "Image capture completed")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    android.util.Log.e("CustomCamera", "Image capture failed: ${failure.reason}")
                    mainHandler.post {
                        captureImageCallback?.invoke(null)
                        captureImageCallback = null
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error capturing image", e)
            callback(null)
        }
    }

    /**
     * Gets the JPEG orientation based on device rotation and camera sensor orientation.
     */
    private fun getJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val deviceRotation = windowManager.defaultDisplay.rotation

        return when (deviceRotation) {
            Surface.ROTATION_0 -> sensorOrientation
            Surface.ROTATION_90 -> (sensorOrientation + 90) % 360
            Surface.ROTATION_180 -> (sensorOrientation + 180) % 360
            Surface.ROTATION_270 -> (sensorOrientation + 270) % 360
            else -> sensorOrientation
        }
    }

    /**
     * Creates a Surface from the TextureView's SurfaceTexture.
     * Uses a preview size that matches common screen aspect ratios to reduce stretching.
     */
    private fun createPreviewSurface(): Surface? {
        val texture = textureView?.surfaceTexture ?: return null
        val view = textureView ?: return null
        val characteristics = getCameraCharacteristics()
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)

        // Use the actual TextureView dimensions, not screen dimensions
        val viewWidth = view.width
        val viewHeight = view.height
        
        android.util.Log.d("CustomCamera", "TextureView dimensions: $viewWidth x $viewHeight")

        // If view dimensions are not available yet, use screen dimensions as fallback
        val targetWidth: Int
        val targetHeight: Int
        
        if (viewWidth > 0 && viewHeight > 0) {
            targetWidth = viewWidth
            targetHeight = viewHeight
        } else {
            // Fallback to screen dimensions
            val displayMetrics = context.resources.displayMetrics
            targetWidth = displayMetrics.widthPixels
            targetHeight = displayMetrics.heightPixels
            android.util.Log.w("CustomCamera", "View dimensions not available, using screen: $targetWidth x $targetHeight")
        }

        // Calculate target aspect ratio
        val targetRatio = targetWidth.toFloat() / targetHeight
        
        android.util.Log.d("CustomCamera", "Target dimensions: $targetWidth x $targetHeight (ratio: $targetRatio)")
        android.util.Log.d("CustomCamera", "Sensor orientation: $sensorOrientation")
        
        // Log available sizes for debugging
        android.util.Log.d("CustomCamera", "Available preview sizes:")
        sizes.take(10).forEach {
            android.util.Log.d("CustomCamera", "  ${it.width} x ${it.height} (ratio: ${it.width.toFloat() / it.height})")
        }

        // Camera preview sizes are typically in sensor orientation (usually landscape)
        // For portrait views, we need to compare with the inverse ratio
        val needsRotation = sensorOrientation == 90 || sensorOrientation == 270
        
        val bestSize = sizes
            .filter { size ->
                // Calculate the aspect ratio we'll actually see after rotation
                val previewRatio = if (needsRotation) {
                    size.height.toFloat() / size.width  // Swap for rotation
                } else {
                    size.width.toFloat() / size.height
                }
                
                // Filter out sizes with extreme aspect ratio mismatches (>30% difference)
                val ratioDiff = abs(previewRatio - targetRatio) / targetRatio
                ratioDiff < 0.3
            }
            .minByOrNull { size ->
                // Find the best match by aspect ratio
                val previewRatio = if (needsRotation) {
                    size.height.toFloat() / size.width
                } else {
                    size.width.toFloat() / size.height
                }
                abs(previewRatio - targetRatio)
            } ?: sizes.maxByOrNull { it.width * it.height } ?: sizes[0]  // Fallback to largest if no good match

        android.util.Log.d("CustomCamera", "Selected preview size: ${bestSize.width} x ${bestSize.height} (ratio: ${bestSize.width.toFloat() / bestSize.height})")
        val effectiveRatio = if (needsRotation) {
            bestSize.height.toFloat() / bestSize.width
        } else {
            bestSize.width.toFloat() / bestSize.height
        }
        android.util.Log.d("CustomCamera", "Effective ratio after rotation: $effectiveRatio (target: $targetRatio)")
        previewSize = bestSize
        texture.setDefaultBufferSize(bestSize.width, bestSize.height)

        previewSurface = Surface(texture)
        return previewSurface
    }

    private fun configurePreviewRotation() {
        val view = textureView ?: return
        val size = previewSize ?: return
        val viewWidth = view.width
        val viewHeight = view.height

        val matrix = Matrix()

        // 1. Get Display Rotation
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val rotation = windowManager.defaultDisplay.rotation

        // 2. Define the bounding box of the preview
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())

        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

            // This is the "Magic" part: Scale the matrix so it fits without stretching
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

            val scale = Math.max(
                viewHeight.toFloat() / size.height,
                viewWidth.toFloat() / size.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        view.setTransform(matrix)
    }

    /**
     * Gets the optimal preview size from camera characteristics.
     * Selects the largest available preview size for best quality.
     */
    private fun getOptimalPreviewSize(): Size? {
        val characteristics = getCameraCharacteristics()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return null
        
        // Get the largest preview size for best quality
        return sizes.maxByOrNull { it.width * it.height }
    }

    /**
     * Creates a capture request builder for preview.
     */
    private fun createCaptureRequestBuilder(surface: Surface): CaptureRequest.Builder? {
        return cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(surface)
        }
    }

    /**
     * Configures manual focus mode with near focus distance.
     */
    private fun configureManualFocus(builder: CaptureRequest.Builder) {
        val characteristics = getCameraCharacteristics()
        val supportsManualFocus = isManualFocusSupported(characteristics)

        if (useManualFocus && supportsManualFocus && focusDistanceMeters != null) {
            val nearFocusDistance = calculateFocusDistance(characteristics, focusDistanceMeters!!)
            
            // Only set manual focus if we got a valid focus distance
            if (nearFocusDistance > 0f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, nearFocusDistance)
                android.util.Log.d("CustomCamera", "Manual focus configured: ${nearFocusDistance}D (~${focusDistanceMeters}m)")
            } else {
                // Fallback to continuous auto-focus if manual focus not supported
                android.util.Log.w("CustomCamera", "Manual focus not available, using continuous AF")
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        } else {
            // Use auto-focus
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            android.util.Log.d("CustomCamera", "Using continuous auto-focus")
        }
    }

    /**
     * Calculates the focus distance in diopters based on camera characteristics.
     * Converts meters to diopters (diopters = 1 / meters).
     * Uses the camera's minimum focus distance to clamp the value.
     */
    private fun calculateFocusDistance(characteristics: CameraCharacteristics, distanceMeters: Float): Float {
        val minFocusDistanceDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        
        // If device doesn't support manual focus, return 0 (will fallback to auto-focus)
        if (minFocusDistanceDiopters == null || minFocusDistanceDiopters <= 0f) {
            android.util.Log.w("CustomCamera", "Manual focus not supported, minFocusDistance: $minFocusDistanceDiopters")
            return 0f
        }
        
        // Convert meters to diopters: diopters = 1 / meters
        val targetDiopters = 1f / distanceMeters
        
        // Clamp to valid range: 0 (infinity) to minFocusDistanceDiopters (closest focus)
        val clampedDiopters = targetDiopters.coerceIn(0f, minFocusDistanceDiopters)
        
        android.util.Log.d("CustomCamera", "Focus calculation: target=${distanceMeters}m (${targetDiopters}D), " +
                "min=${minFocusDistanceDiopters}D, clamped=${clampedDiopters}D (~${if (clampedDiopters == 0f) "∞" else "${1f / clampedDiopters}m"})")
        
        return clampedDiopters
    }

    /**
     * Gets camera characteristics for the current camera.
     */
    private fun getCameraCharacteristics(): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(cameraId)
    }

    /**
     * Checks if manual focus is supported by the camera.
     */
    private fun isManualFocusSupported(characteristics: CameraCharacteristics): Boolean {
        val availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        return availableAfModes?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true
    }

    /**
     * Starts the camera preview session with the configured capture request.
     */
    private fun startPreviewSession(
        surface: Surface,
        captureRequestBuilder: CaptureRequest.Builder
    ) {
        val imageReaderSurface = imageReader?.surface
        val surfaces = if (imageReaderSurface != null) {
            listOf(surface, imageReaderSurface)
        } else {
            listOf(surface)
        }

        cameraDevice?.createCaptureSession(
            surfaces,
            createSessionStateCallback(captureRequestBuilder),
            backgroundHandler
        )
    }

    /**
     * Creates a callback for handling capture session state changes.
     */
    private fun createSessionStateCallback(
        captureRequestBuilder: CaptureRequest.Builder
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                android.util.Log.d("CustomCamera", "Camera session onConfigured")
                // Check if camera was closed while session was configuring
                if (cameraDevice == null) {
                    android.util.Log.w("CustomCamera", "Camera device is null when session configured - camera may have been closed")
                    try {
                        session.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return
                }

                captureSession = session
                startRepeatingRequest(session, captureRequestBuilder)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                android.util.Log.e("CustomCamera", "Camera session onConfigureFailed")
            }
        }
    }

    /**
     * Starts the repeating capture request for preview.
     */
    private fun startRepeatingRequest(
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder
    ) {
        try {
            // Double-check session is still valid before proceeding
            synchronized(this) {
                if (captureSession != session || cameraDevice == null) {
                    android.util.Log.w("CustomCamera", "Session or camera device is no longer valid, skipping startRepeatingRequest")
                    return
                }
            }

            val surface = previewSurface
            if (surface == null) {
                android.util.Log.e("CustomCamera", "Preview surface is null")
                return
            }
            
            // Ensure the builder has the surface target
            // The builder should already have it from createCaptureRequestBuilder, but ensure it's there
            try {
                builder.addTarget(surface)
            } catch (e: IllegalStateException) {
                // Surface might already be added, which is fine
                android.util.Log.d("CustomCamera", "Surface may already be added to builder")
            }
            
            // Configure focus BEFORE building the request
            val characteristics = getCameraCharacteristics()
            if (useManualFocus && isManualFocusSupported(characteristics) && focusDistanceMeters != null) {
                val focusDistance = calculateFocusDistance(characteristics, focusDistanceMeters!!)
                
                // Only set manual focus if we got a valid focus distance
                if (focusDistance > 0f) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    android.util.Log.d("CustomCamera", "Manual focus set: ${focusDistance}D (~${focusDistanceMeters}m)")
                } else {
                    // Fallback to continuous auto-focus
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    android.util.Log.w("CustomCamera", "Using continuous AF as fallback")
                }
            } else {
                // Use auto-focus
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            // Build and start the repeating request
            val captureRequest = builder.build()
            
            // Add callback to monitor capture progress
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Preview is working - this callback confirms frames are being captured
                }
                
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    android.util.Log.e("CustomCamera", "Capture failed: ${failure.reason}")
                }
            }
            
            // Final check before calling setRepeatingRequest
            synchronized(this) {
                if (captureSession != session || cameraDevice == null) {
                    android.util.Log.w("CustomCamera", "Session or camera device became invalid before setRepeatingRequest")
                    return
                }
            }

            session.setRepeatingRequest(captureRequest, captureCallback, backgroundHandler)

            android.util.Log.d("CustomCamera", "Preview started successfully - repeating request active")

            // Don't apply transform - it's causing black screen
            // The preview will show with correct aspect ratio from the camera
            // If stretching occurs, it's better than black screen
        } catch (e: IllegalStateException) {
            // Session was closed - this is expected when switching cameras
            android.util.Log.w("CustomCamera", "Session was closed before starting repeating request: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("CustomCamera", "Error starting repeating request", e)
            e.printStackTrace()
        }
    }

    /**
     * Closes the camera device and releases resources.
     * Catches and ignores device-specific errors (e.g. Xiaomi "Function not implemented"
     * when stopping repeating request) so camera switch still works.
     * Order: null out references first so callbacks see we're closing, then close device
     * (which disconnects and triggers session cleanup), then session/surfaces.
     */
    private fun closeCamera() {
        synchronized(this) {
            val sessionToClose = captureSession
            val deviceToClose = cameraDevice
            captureSession = null
            cameraDevice = null
            captureImageCallback = null
            
            // Close device first. On some devices (Xiaomi) session.close() throws
            // when it tries stopRepeating(). Closing the device triggers session
            // teardown; we then close session in try-catch to absorb any exception.
            try {
                deviceToClose?.close()
            } catch (e: Exception) {
                android.util.Log.d("CustomCamera", "Device close: ${e.message}")
            }
            
            try {
                sessionToClose?.close()
            } catch (e: Exception) {
                android.util.Log.d("CustomCamera", "Session close: ${e.message}")
            }
            
            try {
                previewSurface?.release()
            } catch (e: Exception) {
                android.util.Log.d("CustomCamera", "Surface release: ${e.message}")
            }
            previewSurface = null
            
            try {
                imageReader?.close()
            } catch (e: Exception) {
                android.util.Log.d("CustomCamera", "ImageReader close: ${e.message}")
            }
            imageReader = null
            
            android.util.Log.d("CustomCamera", "Camera closed")
        }
    }
}
