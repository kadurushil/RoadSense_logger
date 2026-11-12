package com.bajajauto.roadsense.ui.common

import androidx.compose.runtime.Immutable

/**
 * Immutable wrapper around standard [List] ensuring the Compose compiler treats
 * the parameter as stable and skippable during recomposition passes.
 */
@Immutable
data class StableList<out T>(val items: List<T> = emptyList()) : List<T> by items

fun <T> List<T>.toStableList(): StableList<T> = StableList(this)

