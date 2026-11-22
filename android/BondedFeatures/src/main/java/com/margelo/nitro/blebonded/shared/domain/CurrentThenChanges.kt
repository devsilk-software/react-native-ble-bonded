package com.margelo.nitro.blebonded.shared.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/** The observation rule every Bonded listener follows: the current value at once, then each change, with repeats suppressed. */
fun <T> currentThenChanges(current: suspend () -> T, changes: Flow<T>): Flow<T> =
  changes.onStart { emit(current()) }.distinctUntilChanged()
