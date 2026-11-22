package com.margelo.nitro.blebonded

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The listeners JavaScript registered through a controller, by ID. Each listener collects a use case's flow;
 * removing it cancels the collection, which ends the native observation.
 */
class ListenerRegistry {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val nextId = AtomicLong(1)
  private val jobs = ConcurrentHashMap<Double, Job>()

  /** Starts collecting [flow] into [onValue] and returns the listener's ID. */
  fun <T> add(flow: Flow<T>, onValue: (T) -> Unit): Double {
    val id = nextId.getAndIncrement().toDouble()
    jobs[id] = scope.launch { flow.collect { onValue(it) } }
    return id
  }

  fun remove(id: Double) {
    jobs.remove(id)?.cancel()
  }
}
