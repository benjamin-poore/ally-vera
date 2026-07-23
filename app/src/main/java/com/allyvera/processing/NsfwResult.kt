package com.allyvera.processing

/**
 * 5-class scores from SigLIP2-x256 explicit-content.
 * Order matches model logits: Anime, Hentai, Normal, Pornography, Enticing.
 */
data class ClassScores(
    val animePicture: Float = 0f,
    val hentai: Float = 0f,
    val normal: Float = 0f,
    val pornography: Float = 0f,
    val enticingOrSensual: Float = 0f,
) {
    fun asLabeledList(): List<Pair<String, Float>> = listOf(
        "Anime Picture" to animePicture,
        "Hentai" to hentai,
        "Normal" to normal,
        "Pornography" to pornography,
        "Enticing or Sensual" to enticingOrSensual,
    )

    val topLabel: String
        get() = asLabeledList().maxBy { it.second }.first

    val topScore: Float
        get() = asLabeledList().maxBy { it.second }.second

    /** Max across corresponding classes (for strip aggregation). */
    fun maxWith(other: ClassScores): ClassScores = ClassScores(
        animePicture = maxOf(animePicture, other.animePicture),
        hentai = maxOf(hentai, other.hentai),
        normal = maxOf(normal, other.normal),
        pornography = maxOf(pornography, other.pornography),
        enticingOrSensual = maxOf(enticingOrSensual, other.enticingOrSensual),
    )

    companion object {
        fun fromLogits(logits: FloatArray): ClassScores {
            require(logits.size >= 5) { "Expected 5 logits, got ${logits.size}" }
            val probs = softmax(logits)
            return ClassScores(
                animePicture = probs[0],
                hentai = probs[1],
                normal = probs[2],
                pornography = probs[3],
                enticingOrSensual = probs[4],
            )
        }

        private fun softmax(logits: FloatArray): FloatArray {
            var max = Float.NEGATIVE_INFINITY
            for (v in logits) if (v > max) max = v
            var sum = 0.0
            val exps = DoubleArray(logits.size)
            for (i in logits.indices) {
                exps[i] = kotlin.math.exp((logits[i] - max).toDouble())
                sum += exps[i]
            }
            return FloatArray(logits.size) { (exps[it] / sum).toFloat() }
        }
    }
}

enum class ContentSeverity {
    SAFE, SEXY, EXPLICIT
}

/**
 * Multi-class NSFW result from SigLIP2-x256.
 */
data class NsfwResult(
    val scores: ClassScores = ClassScores(),
) {
    val classification: ContentSeverity
        get() = when (scores.topLabel) {
            "Hentai", "Pornography" -> ContentSeverity.EXPLICIT
            "Enticing or Sensual" -> ContentSeverity.SEXY
            else -> ContentSeverity.SAFE
        }

    /** Explicit-class peak (Hentai or Pornography). */
    val nsfw: Float get() = maxOf(scores.hentai, scores.pornography)

    val isNsfw: Boolean get() = classification == ContentSeverity.EXPLICIT
}
