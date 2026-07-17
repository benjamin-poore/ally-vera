package com.allyvera.processing

data class NsfwScores(
    val drawings: Float = 0f,
    val hentai: Float = 0f,
    val neutral: Float = 0f,
    val porn: Float = 0f,
    val sexy: Float = 0f
) {
    val maxScore: Float get() = listOf(drawings, hentai, neutral, porn, sexy).maxOrNull() ?: 0f
    val dominantCategory: String get() = when (maxScore) {
        drawings -> "drawings"
        hentai -> "hentai"
        neutral -> "neutral"
        porn -> "porn"
        sexy -> "sexy"
        else -> "unknown"
    }
}
