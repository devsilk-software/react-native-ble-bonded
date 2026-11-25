package com.margelo.nitro.blebonded.mocks

import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicNotification
import com.margelo.nitro.blebonded.features.gatt.application.interfaces.CharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.application.interfaces.PieceFailure
import com.margelo.nitro.blebonded.features.gatt.domain.CharacteristicAddress
import com.margelo.nitro.blebonded.shared.domain.BleError
import com.margelo.nitro.blebonded.shared.domain.CharacteristicProperty
import com.margelo.nitro.blebonded.shared.domain.CharacteristicUuid
import com.margelo.nitro.blebonded.shared.domain.DescriptorUuid
import com.margelo.nitro.blebonded.shared.domain.DeviceId
import com.margelo.nitro.blebonded.shared.domain.ServiceUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A [CharacteristicSource] with the characteristics the test gives it, values it returns, and writes it records. */
class FakeCharacteristicSource(private val characteristics: Map<CharacteristicAddress, List<CharacteristicProperty>>) : CharacteristicSource {
  val values = HashMap<CharacteristicAddress, ByteArray>()
  val writes = CopyOnWriteArrayList<Pair<List<Byte>, Boolean>>()

  @Volatile var refusal: BleError? = null

  @Volatile var isAnswering = true

  @Volatile var maxLength = 20

  @Volatile var rssi = -60

  /** Each characteristic's descriptors and their values; a characteristic without an entry has none. */
  val descriptors = HashMap<CharacteristicAddress, LinkedHashMap<DescriptorUuid, ByteArray>>()
  val descriptorWrites = CopyOnWriteArrayList<Pair<DescriptorUuid, List<Byte>>>()

  /** The chunk of a chunked write that fails, counting from 0, and with what. */
  @Volatile var failingPiece: Pair<Int, BleError>? = null

  @Volatile var switchFailure: BleError? = null
  val notificationSwitches = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
  private val notified = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)

  /** While set, switching notifications on waits until [releaseSwitches]; [held] lists what waits. */
  @Volatile private var hold: CompletableDeferred<Unit>? = null
  val held = CopyOnWriteArrayList<CharacteristicAddress>()

  /** Every time notifications were switched, including the ones that failed. */
  val switchAttempts = AtomicInteger()

  fun holdSwitches() {
    hold = CompletableDeferred()
  }

  fun releaseSwitches() {
    hold?.complete(Unit)
    hold = null
  }

  override suspend fun setNotifications(isOn: Boolean, indicate: Boolean, address: CharacteristicAddress, id: DeviceId) {
    switchAttempts.incrementAndGet()
    if (isOn) switchFailure?.let { throw it }
    val waiting = hold
    if (isOn && waiting != null) {
      held.add(address)
      waiting.await()
    }
    notificationSwitches.add(isOn to indicate)
  }

  override fun notifications(): Flow<CharacteristicNotification> = notified

  suspend fun notify(value: ByteArray, address: CharacteristicAddress, id: DeviceId) {
    notified.emit(CharacteristicNotification(id, address, value))
  }

  override suspend fun properties(address: CharacteristicAddress, id: DeviceId): List<CharacteristicProperty>? = characteristics[address]

  override suspend fun maxWriteLength(id: DeviceId): Int = maxLength

  override suspend fun read(address: CharacteristicAddress, id: DeviceId): ByteArray {
    answer()
    return values[address] ?: ByteArray(0)
  }

  override suspend fun write(value: ByteArray, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean) {
    answer()
    writes.add(value.toList() to withResponse)
  }

  override suspend fun write(pieces: List<ByteArray>, address: CharacteristicAddress, id: DeviceId, withResponse: Boolean, timeoutMs: Long) {
    pieces.forEachIndexed { index, piece ->
      failingPiece?.takeIf { it.first == index }?.let { throw PieceFailure(index, it.second) }
      answer()
      writes.add(piece.toList() to withResponse)
    }
  }

  override suspend fun descriptors(address: CharacteristicAddress, id: DeviceId): List<DescriptorUuid>? =
    if (address !in characteristics) null else descriptors[address]?.keys?.toList().orEmpty()

  override suspend fun readDescriptor(descriptor: DescriptorUuid, address: CharacteristicAddress, id: DeviceId): ByteArray {
    answer()
    return descriptors[address]?.get(descriptor) ?: ByteArray(0)
  }

  override suspend fun writeDescriptor(value: ByteArray, descriptor: DescriptorUuid, address: CharacteristicAddress, id: DeviceId) {
    answer()
    descriptorWrites.add(descriptor to value.toList())
  }

  fun setDescriptor(descriptor: DescriptorUuid, value: ByteArray, address: CharacteristicAddress) {
    descriptors.getOrPut(address) { LinkedHashMap() }[descriptor] = value
  }

  override suspend fun readRssi(id: DeviceId): Int {
    answer()
    return rssi
  }

  private suspend fun answer() {
    refusal?.let { throw it }
    if (!isAnswering) awaitCancellation()
  }
}

val testCharacteristic = CharacteristicAddress(ServiceUuid.parse("B0ED0001-0000-4000-8000-00000000B0ED")!!, CharacteristicUuid.parse("2A37")!!)
val readOnlyCharacteristic = CharacteristicAddress(ServiceUuid.parse("180D")!!, CharacteristicUuid.parse("2A38")!!)

/** A characteristic that only indicates, to check Bonded picks indications when notify is missing. */
val indicateOnlyCharacteristic = CharacteristicAddress(ServiceUuid.parse("180D")!!, CharacteristicUuid.parse("2A39")!!)
