package com.dhaval.echo.domain.search

import java.time.LocalDateTime

/**
 * Encapsulates all searchable criteria in Echo.
 */
data class SearchFilter(
    val query: String = "",
    val startDate: LocalDateTime? = null,
    val endDate: LocalDateTime? = null,
    val tags: List<String> = emptyList(),
    val minDurationMillis: Long? = null,
    val maxDurationMillis: Long? = null,
    val onlyFavorites: Boolean = false,
    val language: String? = null
)
