package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.permissions.application.interfaces.PermissionSource
import com.margelo.nitro.blebonded.features.permissions.domain.PermissionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A [PermissionSource] that reports whatever status the test sets, answers a request with [afterRequest], then lists changes. */
class FakePermissionSource(
  private val status: PermissionStatus,
  private val afterRequest: PermissionStatus = PermissionStatus.GRANTED,
  private val changes: Flow<PermissionStatus> = emptyFlow(),
) : PermissionSource {
  override suspend fun currentStatus(): PermissionStatus = status

  override suspend fun request(): PermissionStatus = afterRequest

  override fun statusChanges(): Flow<PermissionStatus> = changes
}
