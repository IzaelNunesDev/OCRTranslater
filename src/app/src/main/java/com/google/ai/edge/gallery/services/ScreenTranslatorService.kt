package com.google.ai.edge.gallery.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.Activity
// Duplicate Context import removed
// Duplicate Intent import removed
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
// Duplicate Build import removed
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.media.Image
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ContextThemeWrapper
// WindowManager is already imported
// PixelFormat is already imported
// Intent is already imported
// R file import will be com.google.ai.edge.gallery.R
import com.google.android.material.floatingactionbutton.FloatingActionButton // Using FAB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
// import android.view.WindowInsets // Not strictly needed for raw screen size
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.Text // Added for Text.TextBlock parameter
// Keep existing android.media.Image import (implicitly available)
import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper // Ensure this is present
import com.google.ai.edge.gallery.ui.llmchat.LlmInference // Added for LlmChatModelHelper callback
import com.google.ai.edge.gallery.utils.OverlayManager
import android.graphics.Rect // Added for OverlayManager
import android.os.Handler
// android.os.Looper is already imported earlier
// kotlinx.coroutines.CoroutineScope (already imported)
// kotlinx.coroutines.Dispatchers (already imported)
// kotlinx.coroutines.Job (SupervisorJob is used, which is a Job)
// kotlinx.coroutines.launch (already imported)
// kotlinx.coroutines.cancel (already imported)

class ScreenTranslatorService : Service() {

    // Floating Action Button variables
    private var windowManager: WindowManager? = null
    private var floatingButtonView: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    // private val ACTION_CAPTURE_AND_TRANSLATE = "ACTION_CAPTURE_AND_TRANSLATE" // Will be added in companion object

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't provide binding, so return null
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    // For screen dimensions
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var llmChatModelHelper: LlmChatModelHelper? = null // Retaining this as it might be used elsewhere, though not for init directly
    private var selectedGemmaModel: Model? = null
    @Volatile private var isGemmaModelInitialized = false // Mark as Volatile as it's accessed from different threads
    private var overlayManager: OverlayManager? = null
    private lateinit var imageReaderHandler: Handler
    private val isProcessingFrame = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)
    private val translationCache = mutableMapOf<String, String>() // Added translation cache

    // Coroutine scope for the service, tied to its lifecycle.
    // Use SupervisorJob so if one child coroutine fails, others are not affected.
    private val serviceJob = SupervisorJob()
    // Default dispatcher is Main, suitable for launching UI-related or main-thread-bound tasks.
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    @Volatile private var gemmaInitializationSignal = CompletableDeferred<Boolean>()

    companion object {
        private const val MODEL_INIT_TIMEOUT_MS = 30000L // 30 seconds
        const val CHANNEL_ID = "ScreenTranslatorChannel"
        const val ONGOING_NOTIFICATION_ID = 1001 // Example ID
        private const val TAG = "ScreenTranslatorService" // For logging
        const val EXTRA_RESULT_CODE = "mp_result_code"
        const val EXTRA_DATA_INTENT = "mp_data_intent"

        // Intent extras for passing model details
        const val EXTRA_MODEL_NAME = "st_model_name"
        const val EXTRA_MODEL_VERSION = "st_model_version"
        const val EXTRA_MODEL_DOWNLOAD_FILE_NAME = "st_model_download_file_name"
        const val EXTRA_MODEL_URL = "st_model_url"
        const val EXTRA_MODEL_SIZE_BYTES = "st_model_size_bytes"
        const val EXTRA_MODEL_IMPORTED = "st_model_imported"
        const val EXTRA_MODEL_LLM_SUPPORT_IMAGE = "st_model_llm_support_image"
        const val EXTRA_MODEL_NORMALIZED_NAME = "st_model_normalized_name"
        // Add other fields from Model class as needed, e.g., info, learnMoreUrl, configs etc.
        // For now, focusing on core fields for loading and basic operation.

        const val ACTION_CAPTURE_AND_TRANSLATE = "ACTION_CAPTURE_AND_TRANSLATE" // Action for FAB
        // var isProcessingEnabled = true // Removed: No longer controlling processing this way
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScreenTranslatorService onCreate. Service instance: $this")
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        llmChatModelHelper = LlmChatModelHelper
        overlayManager = OverlayManager(this)
        imageReaderHandler = Handler(Looper.getMainLooper())

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            // val windowInsets = metrics.windowInsets // Not needed for raw screen size
            // val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) // Not needed for raw screen size
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION") // Default display and getMetrics are deprecated but needed for older APIs
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
        Log.d(TAG, "Screen dimensions: $screenWidth x $screenHeight @ $screenDensity dpi")

        // Initialize WindowManager here as it's definitely needed if we show the button
        this.windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    private fun prepareGemmaModel() {
        // This function is now called AFTER attempting to load model from intent extras.
        // So, if selectedGemmaModel is still null here, it means no model was passed via intent,
        // or the service was started in a way that didn't include model details.
        // In such cases, we fall back to the hardcoded placeholder.
        if (selectedGemmaModel == null) {
            Log.d(TAG, "No model passed via intent, creating hardcoded placeholder selectedGemmaModel.")
            selectedGemmaModel = Model(
                name = "Gemma-3n-E2B-it-int4", 
                url = "google/gemma-3n-E2B-it-litert-preview",
                downloadFileName = "gemma-3n-E2B-it-int4.task",
                info = "Placeholder Gemma model for ScreenTranslatorService (fallback)",
                sizeInBytes = 0L, 
                version = "internal",
                llmSupportImage = false // Default for placeholder
            )
            // normalizedName will be set by Model's init block
            Log.d(TAG, "Created placeholder selectedGemmaModel: ${selectedGemmaModel?.name}")
        } else {
            Log.d(TAG, "Using selectedGemmaModel provided: ${selectedGemmaModel?.name}")
        }

        Log.d(TAG, "prepareGemmaModel: Called. selectedGemmaModel Name: ${selectedGemmaModel?.name}, isGemmaModelInitialized: $isGemmaModelInitialized")
        if (selectedGemmaModel != null && !isGemmaModelInitialized) {
            Log.d(TAG, "Starting Gemma model initialization: ${selectedGemmaModel!!.name}")
            gemmaInitializationSignal = CompletableDeferred() // Reset signal for this attempt
            val modelToInitialize = selectedGemmaModel!! // Capture the model for this specific initialization attempt
            // Launch initialization on a background thread (Dispatchers.IO)
            serviceScope.launch(Dispatchers.IO) {
                LlmChatModelHelper.initialize(applicationContext, modelToInitialize) { errorMessage ->
                    Log.d(TAG, "LlmChatModelHelper.initialize callback for ${modelToInitialize.name}. Error: $errorMessage")
                    // Check if the model we just spent time initializing is STILL the active one for the service.
                    if (selectedGemmaModel == modelToInitialize) {
                        if (errorMessage.isEmpty()) {
                            // Initialization successful, modelToInitialize.instance should now be populated by LlmChatModelHelper
                            Log.d(TAG, "Gemma model initialized successfully: ${modelToInitialize.name}. Instance: ${modelToInitialize.instance}")
                            isGemmaModelInitialized = true // Set global flag as this is the active model
                            gemmaInitializationSignal.complete(true) // Complete the signal for this model
                            // selectedGemmaModel?.instance is already set by LlmChatModelHelper if successful
                        } else {
                            Log.e(TAG, "Failed to initialize Gemma model: ${modelToInitialize.name}, Error: $errorMessage")
                            isGemmaModelInitialized = false
                            modelToInitialize.instance = null // Ensure instance is null on failure
                            if (gemmaInitializationSignal.isActive) gemmaInitializationSignal.complete(false)
                        }
                    } else { // This initialization is for a STALE model
                        Log.w(TAG, "Initialization of STALE model ${modelToInitialize.name} (callback error: $errorMessage) complete, but active model is now ${selectedGemmaModel?.name ?: "null"}.")
                        if (errorMessage.isEmpty() && modelToInitialize.instance != null) {
                            // This stale model was successfully initialized by LlmChatModelHelper,
                            // but the service has moved on. We must clean up its instance.
                            Log.d(TAG, "Cleaning up LlmInference instance of stale model: ${modelToInitialize.name}")
                            LlmChatModelHelper.cleanUp(modelToInitialize) // modelToInitialize still holds the instance here
                        }
                        // Complete the gemmaInitializationSignal associated with THIS initialization attempt (for modelToInitialize) as false,
                        // because modelToInitialize is not the active model.
                        if (gemmaInitializationSignal.isActive) { 
                            gemmaInitializationSignal.complete(false)
                        }
                    }
                    // TODO: Notify user about initialization failure if necessary
                }
            }
        } else if (selectedGemmaModel == null) {
            Log.d(TAG, "No model to initialize.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator Active")
            .setContentText("Tap to configure or stop the service.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a suitable icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable
            .build()

        startForeground(ONGOING_NOTIFICATION_ID, notification)
        Log.d(TAG, "startForeground called")

        // Handle specific actions first
        if (intent?.action == ACTION_CAPTURE_AND_TRANSLATE) {
            Log.d(TAG, "onStartCommand received ACTION_CAPTURE_AND_TRANSLATE")
            triggerCaptureAndTranslate()
            // After handling the action, we still want to ensure MediaProjection is set up if needed,
            // or that the service continues running as sticky.
            // So, we don't return immediately here unless this action is mutually exclusive
            // with MediaProjection setup, which it isn't necessarily.
        }

        // Existing logic for MediaProjection setup
        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && intent.hasExtra(EXTRA_DATA_INTENT)) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT) // Use getParcelableExtra with type for API 33+
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                if (mediaProjection != null) {
                    Log.i(TAG, "MediaProjection obtained.")
                    // Register a callback to stop the service if projection stops
                    // MUST be registered BEFORE createVirtualDisplay() is called (inside setupImageReaderAndVirtualDisplay)
                    mediaProjection?.registerCallback(this.mediaProjectionCallback, null) // Handler can be null

                    // Attempt to load model from intent BEFORE calling prepareGemmaModel
                    if (intent.hasExtra(EXTRA_MODEL_NAME)) {
                        try {
                            Log.d(TAG, "onCreate: Attempting to retrieve model from intent.")
                            val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                            if (modelName == null) {
                                Log.e(TAG, "onCreate: Model name is null in intent. Cannot initialize Gemma.")
                                // Optionally stop the service or handle error appropriately
                                stopSelf()
                                return START_STICKY
                            }
                            Log.d(TAG, "onCreate: Received modelName from intent: $modelName")
                            val modelVersion = intent.getStringExtra(EXTRA_MODEL_VERSION) ?: "_"
                            val modelDownloadFileName = intent.getStringExtra(EXTRA_MODEL_DOWNLOAD_FILE_NAME)!!
                            val modelUrl = intent.getStringExtra(EXTRA_MODEL_URL)!!
                            val modelSizeBytes = intent.getLongExtra(EXTRA_MODEL_SIZE_BYTES, 0L)
                            val modelImported = intent.getBooleanExtra(EXTRA_MODEL_IMPORTED, false)
                            val modelLlmSupportImage = intent.getBooleanExtra(EXTRA_MODEL_LLM_SUPPORT_IMAGE, false)
                            // val modelNormalizedName = intent.getStringExtra(EXTRA_MODEL_NORMALIZED_NAME) // Not needed to pass, Model init handles it

                            selectedGemmaModel = Model(
                                name = modelName,
                                version = modelVersion,
                                downloadFileName = modelDownloadFileName,
                                url = modelUrl,
                                sizeInBytes = modelSizeBytes,
                                imported = modelImported,
                                llmSupportImage = modelLlmSupportImage
                                // Other fields like info, learnMoreUrl, configs can be added if needed
                            )
                            Log.d(TAG, "Successfully created selectedGemmaModel from intent extras: ${selectedGemmaModel?.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error creating Model from intent extras. Will use placeholder if prepareGemmaModel creates one.", e)
                            selectedGemmaModel = null // Ensure fallback if parsing fails
                        }
                    } // Closes if (intent.hasExtra(EXTRA_MODEL_NAME))
                    // Removed extraneous else block that was here

                    prepareGemmaModel() // Now call prepareGemmaModel
                    setupImageReaderAndVirtualDisplay()
                } else {
                    Log.e(TAG, "Failed to obtain MediaProjection.")
                    stopSelf() // Stop if we can't get projection
                }
            } else {
                Log.e(TAG, "MediaProjection permission not granted or data intent missing.")
                stopSelf() // Stop if permission not granted
            }
        } else {
            // If service is started without projection data (e.g. first start to show notification, or restart)
            // This path should ideally not try to start projection, or be handled carefully.
            // For now, if it's not the intent from ActivityResult, it won't have the extras.
            Log.d(TAG, "Service started without MediaProjection data.")
        }

        // If the service is killed, it will be automatically restarted.
        return START_STICKY
    }
    
    private fun setupImageReaderAndVirtualDisplay() {
        Log.d(TAG, "Setting up ImageReader and VirtualDisplay.")
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (isProcessingFrame.compareAndSet(false, true)) {
                var image: Image? = null
                try {
                    if (captureRequested.compareAndSet(true, false)) {
                        Log.d(TAG, "Capture requested. Processing image.")
                        image = reader?.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * screenWidth

                            val bitmapWidth = screenWidth + rowPadding / pixelStride
                            val tempBitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
                            tempBitmap.copyPixelsFromBuffer(buffer)

                            val finalBitmapToProcess: Bitmap
                            if (rowPadding > 0) {
                                finalBitmapToProcess = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight)
                                if (!tempBitmap.isRecycled) {
                                    tempBitmap.recycle()
                                }
                            } else {
                                finalBitmapToProcess = tempBitmap
                            }
                            // processImageForTranslation will handle resetting isProcessingFrame and recycling finalBitmapToProcess
                            processImageForTranslation(finalBitmapToProcess)
                            // Note: image (original Image object) must be closed here or by processImageForTranslation.
                            // Current processImageForTranslation does not take the original Image object.
                            // So, it must be closed here after processImageForTranslation is called.
                            // However, processImageForTranslation is async. The 'image' might be closed before processing finishes.
                            // This is problematic. The original image from ImageReader should ideally be passed through
                            // and closed only after all its derived data (like bitmap) is fully processed or no longer needed.
                            // For now, adhering to prompt to call processImageForTranslation(bitmap) and close image afterwards.
                            // This implies processImageForTranslation must complete synchronously regarding bitmap usage,
                            // or bitmap must be copied if processImageForTranslation is fully async regarding bitmap.
                            // Given current structure, processImageForTranslation uses the bitmap immediately for MLKit.
                        } else {
                            Log.w(TAG, "Capture requested, but acquireLatestImage returned null.")
                            isProcessingFrame.set(false) // Reset as no processing will happen
                        }
                    } else {
                        // No capture requested, acquire and close to keep the queue clear.
                        Log.d(TAG, "No capture requested. Acquiring and closing image.")
                        image = reader?.acquireLatestImage()
                        // image?.close() is done in finally block
                        isProcessingFrame.set(false) // Reset as no processing was done
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onImageAvailable", e)
                    isProcessingFrame.set(false) // Reset on error
                } finally {
                    image?.close() // Close the original image in all cases if acquired
                }
            } else {
                // isProcessingFrame was already true, meaning a frame is currently being handled.
                // Acquire and immediately close this new frame to keep the queue clear.
                reader?.acquireLatestImage()?.use { it.close() } // 'use' will auto-close
                Log.d(TAG, "ImageReader queue: Dropping frame as another is already being processed.")
            }
        }, imageReaderHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay("ScreenTranslator", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
        Log.d(TAG, "VirtualDisplay created.")
    }

    private fun processImageForTranslation(finalBitmapToProcess: Bitmap) { // originalImage is now closed by caller
        val currentModelForJob = selectedGemmaModel // Capture the model at the start of this processing job
        val currentSignalForJob = gemmaInitializationSignal // Capture its signal, which should correspond to currentModelForJob's init attempt

        Log.d(TAG, "processImageForTranslation: Captured model: ${currentModelForJob?.name}, Captured signal: $currentSignalForJob")

        if (currentModelForJob == null) {
            Log.w(TAG, "No Gemma model selected at the start of processImageForTranslation. Skipping.")
            if (!finalBitmapToProcess.isRecycled) finalBitmapToProcess.recycle()
            isProcessingFrame.set(false) // Reset flag as we are aborting
            return
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val imageForMlKit = InputImage.fromBitmap(finalBitmapToProcess, 0)

        recognizer.process(imageForMlKit)
            .addOnSuccessListener { visionText ->
                Log.d(TAG, "ML Kit Text Recognition Success. Full text: ${visionText.text.substring(0, minOf(visionText.text.length, 100))}...")
                
                // Clear previous overlays first on the main thread
                imageReaderHandler.post { overlayManager?.removeAllOverlays() }

                serviceScope.launch(Dispatchers.IO) { // Explicitly move to background
                    try {
                        val modelReady = withTimeoutOrNull(MODEL_INIT_TIMEOUT_MS) {
                            currentSignalForJob.await() // Await the signal of the model captured at the start of this job
                        }

                        if (modelReady != true || currentModelForJob.instance == null) {
                            Log.w(TAG, "Gemma model '${currentModelForJob.name}' instance not ready after waiting. Skipping. modelReady: $modelReady, instanceNull: ${currentModelForJob.instance == null}")
                        } else {
                            Log.d(TAG, "Gemma model '${currentModelForJob.name}' is ready. Proceeding with translation for ${visionText.textBlocks.size} blocks.")
                            for (block in visionText.textBlocks) {
                                val textToTranslate = block.text
                                val boundingBox = block.boundingBox ?: continue // Skip if no bounding box
                                val blockId = стабильныйIdParaBloco(block) // Generate stable ID for the block

                                if (currentModelForJob.instance == null) { // Re-check instance before each inference
                                    Log.e(TAG, "CRITICAL: Instance of '${currentModelForJob.name}' became null before processing block '${textToTranslate.take(20)}...'. Skipping block.")
                                    continue
                                }

                                val cachedTranslation = translationCache[textToTranslate]
                                if (cachedTranslation != null) {
                                    Log.d(TAG, "Cache hit for '${textToTranslate.take(50)}...': '$cachedTranslation'")
                                    imageReaderHandler.post {
                                        overlayManager?.addOverlay(blockId, boundingBox, cachedTranslation)
                                    }
                                } else {
                                    Log.d(TAG, "Cache miss for '${textToTranslate.take(50)}...'. Translating...")
                                    // Add placeholder "Translating..." overlay
                                    imageReaderHandler.post {
                                        overlayManager?.addOverlay(blockId, boundingBox, "Translating...")
                                    }

                                    val prompt = "Translate to Portuguese: $textToTranslate"
                                    try {
                                        val fullTranslatedText = LlmChatModelHelper.runInferenceSuspending(
                                            model = currentModelForJob,
                                            input = prompt
                                            // No image needed here as we are translating text from OCR
                                        )
                                        Log.i(TAG, "Translation for '${textToTranslate.take(50)}...': '$fullTranslatedText'")
                                        translationCache[textToTranslate] = fullTranslatedText
                                        imageReaderHandler.post {
                                            overlayManager?.updateOverlayText(blockId, boundingBox, fullTranslatedText)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error during runInferenceSuspending for '${textToTranslate.take(50)}...': ${e.message}", e)
                                        // Optionally, update overlay to show error for this block
                                        imageReaderHandler.post {
                                            overlayManager?.updateOverlayText(blockId, boundingBox, "Error")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during translation processing in coroutine", e)
                        // Ensure isProcessingFrame is reset even if an error occurs within the coroutine
                        // The finally block below will handle this.
                    } finally {
                        // This finally block is for the serviceScope.launch(Dispatchers.IO)
                    // Ensure finalBitmapToProcess is recycled and isProcessingFrame is reset
                    // This will run after the translation logic or if an exception occurs within the coroutine
                    if (!finalBitmapToProcess.isRecycled) {
                        finalBitmapToProcess.recycle()
                        Log.d(TAG, "Recycled finalBitmapToProcess in coroutine finally block.")
                    }
                    isProcessingFrame.set(false)
                    Log.d(TAG, "Translation coroutine finished. isProcessingFrame reset to false.")
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e(TAG, "ML Kit Text Recognition Failed.", e)
            if (!finalBitmapToProcess.isRecycled) {
                finalBitmapToProcess.recycle()
                Log.d(TAG, "Recycled finalBitmapToProcess in ML Kit failure listener.")
            }
            isProcessingFrame.set(false) // Reset flag on ML Kit failure
            Log.d(TAG, "ML Kit failure. isProcessingFrame reset to false.")
        }
    } // End of processImageForTranslation

    private fun стабильныйIdParaBloco(block: Text.TextBlock): String {
        val box = block.boundingBox
        val centerX = box?.centerX()?.div(10) ?: 0
        val centerY = box?.centerY()?.div(10) ?: 0
        return "${block.text.hashCode()}_${centerX}_${centerY}"
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying ScreenTranslatorService.")
        hideFloatingButton() // Call hideFloatingButton at the beginning
        stopForeground(STOP_FOREGROUND_REMOVE)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(mediaProjectionCallback) // Unregister callback
        mediaProjection?.stop()
        overlayManager?.removeAllOverlays()

        selectedGemmaModel?.let {
            if (it.instance != null) {
                Log.d(TAG, "Cleaning up Gemma model instance for: ${it.name}")
                LlmChatModelHelper.cleanUp(it)
            }
            isGemmaModelInitialized = false
        }
        serviceJob.cancel() // Cancel all coroutines started by this scope
        Log.d(TAG, "ScreenTranslatorService onDestroy. All resources released and coroutines cancelled.")
    }

    // MediaProjection.Callback to handle explicit stop from notification or system
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped callback triggered. Stopping service.")
            stopSelf() // Stop the service if projection is stopped
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Screen Translator Service Channel"
            val descriptionText = "Channel for Screen Translator foreground service notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun triggerCaptureAndTranslate() {
        Log.d(TAG, "triggerCaptureAndTranslate() called. Setting captureRequested to true.")
        captureRequested.set(true)
        // The onImageAvailable listener will now pick up this request.
    }

    private fun showFloatingButton() {
        if (floatingButtonView != null) {
            Log.d(TAG, "Floating button already shown.")
            return
        }

        // Ensure windowManager is initialized (should be by onCreate)
        if (this.windowManager == null) {
            this.windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        // Apply a MaterialComponents theme for the FloatingActionButton
        val themedContext = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
        floatingButtonView = View.inflate(themedContext, R.layout.floating_button, null)

        floatingButtonParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100 // Initial Y position
        }

        floatingButtonView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.toFloat()
            private var initialTouchY: Float = 0.toFloat()
            private var isClick: Boolean = true // Flag to distinguish click from drag

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (floatingButtonParams == null || event == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isClick = true // Assume it's a click until moved
                        initialX = floatingButtonParams!!.x
                        initialY = floatingButtonParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true // Consume the event
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        // If moved beyond a threshold, consider it a drag, not a click
                        if (dx * dx + dy * dy > 20 * 20) { // Increased threshold slightly
                            isClick = false
                        }
                        if (!isClick) { // Only update position if it's a drag
                            floatingButtonParams!!.x = initialX + dx.toInt()
                            floatingButtonParams!!.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(floatingButtonView, floatingButtonParams)
                        }
                        return true // Consume the event
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            Log.d(TAG, "FloatingActionButton clicked via OnTouchListener!")
                            // Option 1: Call captureAndTranslateScreen directly if it's ready
                            // captureAndTranslateScreen() 
                            
                            // Option 2: Send intent (current behavior from removed OnClickListener)
                            Log.d(TAG, "Sending ACTION_CAPTURE_AND_TRANSLATE from OnTouchListener.")
                            val serviceIntent = Intent(applicationContext, ScreenTranslatorService::class.java).apply {
                                action = ACTION_CAPTURE_AND_TRANSLATE
                            }
                            startService(serviceIntent)
                            Log.d(TAG, "Sent ACTION_CAPTURE_AND_TRANSLATE to ScreenTranslatorService via startService from OnTouchListener.")
                        }
                        return true // Consume the event
                    }
                }
                return false // Don't consume if not handled
            }
        })

        try {
            windowManager?.addView(floatingButtonView, floatingButtonParams)
            Log.d(TAG, "Floating button added to window.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding floating button to window", e)
        }
    }

    private fun hideFloatingButton() {
        if (floatingButtonView != null && windowManager != null) {
            try {
                windowManager?.removeView(floatingButtonView)
                Log.d(TAG, "Floating button removed from window.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating button from window", e)
            } finally {
                floatingButtonView = null // Ensure it's nulled out even if removeView fails
            }
        }
    }
}
