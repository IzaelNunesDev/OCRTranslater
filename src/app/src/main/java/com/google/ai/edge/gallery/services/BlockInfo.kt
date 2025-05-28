package com.google.ai.edge.gallery.services

import android.graphics.Rect

data class BlockInfo(
    val boundingBox: Rect,
    val originalText: String,
    val translatedText: String,
    val lastSeenTimestamp: Long,
    val stableId: String
)
