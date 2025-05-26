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

        var isProcessingEnabled = true // Default to true when service starts
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
                LlmChatModelHelper.initialize(applicationContext, selectedGemmaModel!!) { success, llmInference, errorMessage ->
                    Log.d(TAG, "LlmChatModelHelper.initialize callback for ${modelToInitialize.name}. Success: $success, Error: $errorMessage, Instance: $llmInference")
                    // Check if the model we just spent time initializing is STILL the active one for the service.
                    if (selectedGemmaModel == modelToInitialize) {
                        if (success) {
                            Log.d(TAG, "Gemma model initialized successfully: ${modelToInitialize.name}")
                            isGemmaModelInitialized = true // Set global flag as this is the active model
                            gemmaInitializationSignal.complete(true) // Complete the signal for this model
                            selectedGemmaModel?.instance = llmInference // Use instance from callback
                            Log.i(TAG, "Gemma model (${selectedGemmaModel?.name}) initialized successfully. Instance: ${selectedGemmaModel?.instance}")
                        } else {
                            Log.e(TAG, "Failed to initialize Gemma model: ${modelToInitialize.name}, Error: $errorMessage")
                            isGemmaModelInitialized = false // Corrected: should be false on failure
                            // modelToInitialize.instance should be null if LlmChatModelHelper.initialize failed.
                            if (gemmaInitializationSignal.isActive) gemmaInitializationSignal.complete(false)
                            Log.e(TAG, "Gemma model (${selectedGemmaModel?.name}) initialization failed: $errorMessage")
                        }
                    } else { // This initialization is for a STALE model
                        // The selectedGemmaModel for the service has changed while this one was initializing.
                        // This modelToInitialize is now stale.
                        Log.w(TAG, "Initialization of STALE model ${modelToInitialize.name} (callback success: $success, error: $errorMessage) complete, but active model is now ${selectedGemmaModel?.name ?: "null"}.")
                        if (success && llmInference != null) {
                            // This stale model was successfully initialized by LlmChatModelHelper,
                            // but the service has moved on. We must clean up the LlmInference instance.
                            Log.d(TAG, "Cleaning up LlmInference instance of stale model: ${modelToInitialize.name}")
                            // Create a temporary Model object that ONLY holds this instance for cleanup.
                            // This assumes LlmChatModelHelper.cleanUp can handle a Model where only 'instance' is relevant.
                            val tempModelForStaleCleanup = modelToInitialize.copy(instance = llmInference)
                            LlmChatModelHelper.cleanUp(tempModelForStaleCleanup)
                        }
                        // Complete the gemmaInitializationSignal associated with THIS initialization attempt (for modelToInitialize) as false,
                        // because modelToInitialize is not the active model.
                        if (gemmaInitializationSignal.isActive) { // gemmaInitializationSignal here is the one captured at launch of this coroutine
                            gemmaInitializationSignal.complete(false)
                        }
                    }
                    // TODO: Notify user about initialization failure if necessary, perhaps if selectedGemmaModel == modelToInitialize and error occurred.
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
        imageReader?.setOnImageAvailableListener({
            reader ->
            if (isProcessingFrame.compareAndSet(false, true)) {
                var originalImage: Image? = null
                try {
                    originalImage = reader?.acquireLatestImage()
                    if (originalImage != null) {
                        val planes = originalImage.planes
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
                                tempBitmap.recycle() // Recycle the larger, intermediate bitmap
                            }
                        } else {
                            finalBitmapToProcess = tempBitmap // No crop, final is the original
                        }
                        // Pass originalImage to be closed by processImageForTranslation or its callees
                        processImageForTranslation(finalBitmapToProcess) // Removed originalImage from params here, will be closed in finally
                    } else {
                        Log.w(TAG, "acquireLatestImage returned null.")
                        isProcessingFrame.set(false) // Reset if no image acquired
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image in onImageAvailable", e)
                    isProcessingFrame.set(false) // Reset on error
                } finally {
                    originalImage?.close() // Always close the original image
                    // isProcessingFrame is reset inside processImageForTranslation or if originalImage is null/error
                }
            } else {
                // Frame processing is already in progress, acquire and immediately close this new frame.
                reader?.acquireLatestImage()?.use { /* it.close() is called by use */ }
                Log.d(TAG, "Dropping frame as another is already being processed.")
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
                
                serviceScope.launch(Dispatchers.IO) { // Explicitly move to background
                    try {
                        imageReaderHandler.post { overlayManager?.removeAllOverlays() } // Keep UI on main

                        val modelReady = withTimeoutOrNull(MODEL_INIT_TIMEOUT_MS) {
                            currentSignalForJob.await() // Await the signal of the model captured at the start of this job
                        }

                        // After await, check if the model we intended to use (currentModelForJob) has its instance ready.
                        if (modelReady != true || currentModelForJob.instance == null) {
                            Log.w(TAG, "Gemma model '${currentModelForJob.name}' instance not ready after waiting. Skipping. modelReady: $modelReady, instanceNull: ${currentModelForJob.instance == null}")
                            // No return@launch here, let finally block handle cleanup
                        } else {
                            Log.d(TAG, "Gemma model '${currentModelForJob.name}' is ready. Proceeding with translation.")
                            for (block in visionText.textBlocks) {
                                Log.d(TAG, "Block text: '${block.text}', BoundingBox: ${block.boundingBox}")

                                if (currentModelForJob.instance == null) {
                                    Log.e(
                                        TAG,
                                        "CRITICAL: Instance of '${currentModelForJob.name}' became null unexpectedly within translation loop for block '${block.text.take(20)}...'. Skipping block."
                                    )
                                    continue
                                }

                                val boundingBox = block.boundingBox
                                if (boundingBox != null) {
                            val textToTranslate = block.text
                            val translatedTextBuilder = StringBuilder()
                            val imageForLlm: Bitmap? = null // Placeholder for now, LLM image input not used in this flow yet

                            val prompt = "Translate to Portuguese: $textToTranslate"

                            LlmChatModelHelper.runInference(
                                model = currentModelForJob,
                                input = prompt,
                                resultListener = { partialResult, done ->
                                    translatedTextBuilder.append(partialResult)
                                    if (done) {
                                        Log.i(TAG, "Translation for '${block.text}': ${translatedTextBuilder.toString()}")
                                        imageReaderHandler.post {
                                            overlayManager?.addOverlay(
                                                block.boundingBox!!,
                                                translatedTextBuilder.toString()
                                            )
                                        }
                                    }
                                },
                                cleanUpListener = {
                                    Log.d(TAG, "LLM Inference clean up for block: ${block.text}")
                                },
                        }
                    }
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

    override fun onBind(intent: Intent?): IBinder? {
        // This service does not provide binding, so return null
        return null
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
}
