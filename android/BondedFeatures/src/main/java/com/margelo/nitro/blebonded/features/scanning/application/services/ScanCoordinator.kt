package com.margelo.nitro.blebonded.features.scanning.application.services

import com.margelo.nitro.blebonded.features.scanning.application.interfaces.ScanEnvironment
import com.margelo.nitro.blebonded.features.scanning.application.interfaces.ScanSource
import com.margelo.nitro.blebonded.features.scanning.domain.Advertisement
import com.margelo.nitro.blebonded.features.scanning.domain.ScanEvent
import com.margelo.nitro.blebonded.features.scanning.domain.ScanRequest
import com.margelo.nitro.blebonded.features.scanning.domain.ScanStartLimiter
import com.margelo.nitro.blebonded.features.scanning.domain.ScanStopReason
import com.margelo.nitro.blebonded.features.scanning.domain.ScannedDevice
import com.margelo.nitro.blebonded.shared.application.EventBus
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.DomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs one scan at a time: starting a new one replaces the running one. Applies the name filter, reports each
 * device once unless duplicates are allowed, stops after the timeout, pauses an unfiltered scan while the app
 * is in the background, spaces scan starts within Android's limit (decision R3), and publishes when a scan
 * starts and stops.
 *
 * Every operation that starts or stops the platform's scanner holds one lock, so a late timeout or failure can
 * never stop a newer scan.
 */
class ScanCoordinator(
  private val source: ScanSource,
  private val environment: ScanEnvironment,
  private val bus: EventBus,
  private val scope: CoroutineScope,
  private val limiter: ScanStartLimiter = ScanStartLimiter(),
) {
  private val transitions = Mutex()
  private var nextId = 1

  @Volatile private var current: Session? = null

  private class Session(val id: Int, val request: ScanRequest) {
    val events = Channel<ScanEvent>(Channel.UNLIMITED)
    val seen = HashSet<DeviceId>()

    /** Counts the times the platform's scanner was started for this scan; reports from an older run are stale. */
    var run = 0
    var isPaused = false
    var sourceJob: Job? = null
    val jobs = mutableListOf<Job>()
  }

  /**
   * Starts a scan, replacing the running one, and returns its ID and what it reports. Throws a [BleError] when
   * the phone can't scan, or for an unfiltered scan while the app is in the background.
   */
  suspend fun start(request: ScanRequest): ScanSession =
    transitions.withLock {
      source.readiness().error?.let { throw it }
      if (!request.isFiltered && environment.isInBackground()) {
        throw BleError(BleError.Code.UNSUPPORTED, "Scanning in the background needs a service filter", operation = "scan")
      }
      end(ScanStopReason.REPLACED, null)

      val session = Session(nextId++, request)
      current = session
      val waitMs = runSource(session)
      request.timeoutMs?.let { timeoutMs ->
        session.jobs +=
          scope.launch {
            delay(timeoutMs)
            endLater(session.id, ScanStopReason.TIMEOUT, null)
          }
      }
      if (!request.isFiltered) {
        session.jobs +=
          scope.launch(start = CoroutineStart.UNDISPATCHED) {
            environment.backgroundChanges().collect { setBackground(it, session.id) }
          }
      }
      val kind = if (request.isFiltered) "filtered" else "unfiltered"
      bus.publish(DomainEvent(DomainEvent.Kind.SCAN_STARTED, detail = if (waitMs > 0) "$kind, delayed $waitMs ms" else kind))
      ScanSession(session.id, session.events.receiveAsFlow())
    }

  /** Stops the scan with this ID; does nothing when it has already ended. */
  suspend fun stop(id: Int) {
    transitions.withLock {
      if (current?.id == id) end(ScanStopReason.STOPPED, null)
    }
  }

  /** Ends the current scan, whichever it is. Called with the lock held, never from one of the scan's own jobs. */
  private suspend fun end(reason: ScanStopReason, error: BleError?) {
    val session = current ?: return
    current = null
    session.jobs.forEach { it.cancel() }
    session.sourceJob?.cancelAndJoin()
    session.events.trySend(ScanEvent.Stopped(reason, error))
    session.events.close()
    bus.publish(DomainEvent(DomainEvent.Kind.SCAN_STOPPED, detail = reason.publicName, errorCode = error?.code))
  }

  /** Ends the scan with this ID from a coroutine of its own, so ending can cancel the job that asked for it. */
  private fun endLater(id: Int, reason: ScanStopReason, error: BleError?, run: Int? = null) {
    scope.launch {
      transitions.withLock {
        val session = current
        if (session == null || session.id != id) return@withLock
        if (run != null && (session.run != run || session.isPaused)) return@withLock
        end(reason, error ?: if (reason == ScanStopReason.FAILED) failure() else null)
      }
    }
  }

  private fun failure(): BleError =
    source.readiness().error ?: BleError(BleError.Code.UNKNOWN, "Scanning stopped unexpectedly", operation = "scan")

  /** Starts the platform's scanner for [session], after the wait Android's limit needs; returns that wait. */
  private fun runSource(session: Session): Long {
    session.run += 1
    session.isPaused = false
    val run = session.run
    val now = environment.now()
    val waitMs = limiter.delayBeforeStart(now)
    limiter.recordStart(now + waitMs)
    session.sourceJob =
      scope.launch {
        delay(waitMs)
        var error: BleError? = null
        try {
          source.advertisements(session.request.services, session.request.allowDuplicates).collect { receive(session, run, it) }
        } catch (failure: BleError) {
          error = failure
        }
        endLater(session.id, ScanStopReason.FAILED, error, run)
      }
    return waitMs
  }

  private fun receive(session: Session, run: Int, advertisement: Advertisement) {
    val device =
      synchronized(session) {
        if (current !== session || session.run != run) return
        if (session.request.name?.matches(advertisement.name) == false) return
        if (!session.request.allowDuplicates && !session.seen.add(advertisement.id)) return
        ScannedDevice.from(advertisement, session.request.fullAdvertisement)
      }
    session.events.trySend(ScanEvent.Device(device))
  }

  private suspend fun setBackground(isInBackground: Boolean, id: Int) {
    transitions.withLock {
      val session = current
      if (session == null || session.id != id) return@withLock
      if (isInBackground && !session.isPaused) {
        session.isPaused = true
        session.sourceJob?.cancelAndJoin()
      } else if (!isInBackground && session.isPaused) {
        runSource(session)
      }
    }
  }
}

/** A running scan: its ID, for stopping it, and what it reports. */
data class ScanSession(val id: Int, val events: Flow<ScanEvent>)
