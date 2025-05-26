package com.google.ai.edge.gallery.utils

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View // Ensure View is imported
import android.view.WindowManager
import android.widget.TextView
import com.google.ai.edge.gallery.R // Assuming R is correctly generated for the new layout
// Remove java.util.ArrayList as it's no longer used for overlayViews
import android.util.Log // Added for logging
import com.google.ai.edge.gallery.services.BlockInfo // Added for syncOverlays

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayViews = mutableMapOf<String, View>() // Changed to MutableMap

    // Modified to align with map and accept stableId
    fun addOverlay(stableId: String, originalBounds: Rect, textToDisplay: String) {
        val inflater = LayoutInflater.from(context)
        val overlayView = inflater.inflate(R.layout.translation_balloon, null)
        val textView = overlayView.findViewById<TextView>(R.id.translated_text_view)
        textView.text = textToDisplay // Use the new parameter

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.x = originalBounds.left
        params.y = originalBounds.bottom

        // If an old view exists for this ID, remove it first to avoid duplicates.
        overlayViews[stableId]?.let { oldView ->
            try {
                windowManager.removeView(oldView)
            } catch (e: Exception) { Log.e("OverlayManager", "Error removing old view for $stableId during add", e) }
        }

        windowManager.addView(overlayView, params)
        overlayViews[stableId] = overlayView // Store/replace in map
        Log.d("OverlayManager", "Added/Updated overlay for ID: $stableId, text: ${textToDisplay.take(20)}")
    }

    private fun removeOverlayById(stableId: String) {
        overlayViews.remove(stableId)?.let { viewToRemove ->
            try {
                windowManager.removeView(viewToRemove)
                Log.d("OverlayManager", "Removed overlay for ID: $stableId")
            } catch (e: Exception) {
                Log.e("OverlayManager", "Error removing overlay for ID $stableId", e)
            }
        }
    }

    fun syncOverlays(currentBlocks: Map<String, BlockInfo>, seenBlockStableIdsInCurrentFrame: Set<String>) {
        val existingOverlayIds = overlayViews.keys.toMutableSet()
        for (stableId in existingOverlayIds) {
            if (stableId !in seenBlockStableIdsInCurrentFrame) {
                removeOverlayById(stableId)
            }
        }

        for ((stableId, blockInfo) in currentBlocks) {
            val existingView = overlayViews[stableId]
            if (existingView != null) {
                // Update existing view
                val textView = existingView.findViewById<TextView>(R.id.translated_text_view)
                textView.text = blockInfo.translatedText
                // Optional: Update layout params if position changed significantly
                // val params = existingView.layoutParams as WindowManager.LayoutParams
                // if (params.x != blockInfo.boundingBox.left || params.y != blockInfo.boundingBox.bottom) {
                //     params.x = blockInfo.boundingBox.left
                //     params.y = blockInfo.boundingBox.bottom
                //     windowManager.updateViewLayout(existingView, params)
                // }
                Log.d("OverlayManager", "Updated overlay for ID: $stableId, text: ${blockInfo.translatedText.take(20)}")
            } else {
                // Add new view for this blockInfo
                Log.d("OverlayManager", "Adding new overlay for ID: $stableId, text: ${blockInfo.translatedText.take(20)}")
                addOverlay(stableId, blockInfo.boundingBox, blockInfo.translatedText)
            }
        }
        Log.d("OverlayManager", "Sync complete. Current overlay count: ${overlayViews.size}")
    }

    fun updateOverlayText(stableId: String, box: Rect, newText: String) {
        val viewToUpdate = overlayViews[stableId]
        if (viewToUpdate != null) {
            val textView = viewToUpdate.findViewById<TextView>(R.id.translated_text_view)
            textView.text = newText
            // Optionally update position
            // val params = viewToUpdate.layoutParams as WindowManager.LayoutParams
            // params.x = box.left
            // params.y = box.bottom
            // windowManager.updateViewLayout(viewToUpdate, params)
            Log.d("OverlayManager", "Updated text for overlay ID '$stableId' to '${newText.take(20)}...'")
        } else {
            Log.d("OverlayManager", "updateOverlayText called for ID '$stableId', but no view found. Adding new one.")
            addOverlay(stableId, box, newText)
        }
    }

    fun removeAllOverlays() {
        val idsToRemove = overlayViews.keys.toMutableSet() // Avoid concurrent modification
        for (stableId in idsToRemove) {
            removeOverlayById(stableId)
        }
        // overlayViews.clear() // removeOverlayById already removes from map. Explicit clear is okay for safety.
        // Let's keep it for ensuring the map is definitely empty, as per instructions.
        overlayViews.clear()
        Log.d("OverlayManager", "removeAllOverlays called. All overlays removed.")
    }
}
