package com.allyvera.processing

/**
 * Binary NSFW result from the Yahoo OpenNSFW model.
 * [nsfw] is the model's NSFW probability (0..1); [safe] is its complement.
 */
data class NsfwResult(
    val nsfw: Float = 0f
) {
    val safe: Float get() = 1f - nsfw

    /** Three-tier classification matching the reference thresholds. */
    val classification: NsfwClassification get() = when {
        nsfw < 0.4f -> NsfwClassification.SAFE
        nsfw < 0.7f -> NsfwClassification.QUESTIONABLE
        else -> NsfwClassification.NSFW
    }

    val isNsfw: Boolean get() = nsfw >= 0.7f
}

enum class NsfwClassification {
    SAFE, QUESTIONABLE, NSFW
}
