import BondedFeatures
import Foundation
import NitroModules

/// The `Gatt` HybridObject: calls the `gatt` feature's use cases and maps between them and the generated Nitro types.
/// Nothing else lives here (decision A5).
final class HybridGatt: HybridGattSpec {
  static func read(id: String, service: String, characteristic: String, timeoutMs: Double) throws -> any Promise<ArrayBuffer> {
    let address = try CharacteristicAddress(service, characteristic)
    return Promise_ArrayBuffer.async {
      do {
        let value = try await CompositionRoot.shared.readCharacteristic(address, on: DeviceId(id), timeout: .milliseconds(Int64(timeoutMs)))
        return try ArrayBuffer.copy(data: Data(value))
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func write(
    id: String,
    service: String,
    characteristic: String,
    value: ArrayBuffer,
    withResponse: Bool,
    timeoutMs: Double
  ) throws -> any Promise<Void> {
    let address = try CharacteristicAddress(service, characteristic)
    let bytes = [UInt8](value.toData(copyIfNeeded: true))
    return Promise_void.async {
      do {
        try await CompositionRoot.shared.writeCharacteristic(
          bytes,
          to: address,
          on: DeviceId(id),
          withResponse: withResponse,
          timeout: .milliseconds(Int64(timeoutMs))
        )
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func writeChunked(
    id: String,
    service: String,
    characteristic: String,
    value: ArrayBuffer,
    withResponse: Bool,
    timeoutMs: Double
  ) throws -> any Promise<Void> {
    let address = try CharacteristicAddress(service, characteristic)
    let bytes = [UInt8](value.toData(copyIfNeeded: true))
    return Promise_void.async {
      do {
        try await CompositionRoot.shared.writeChunked(
          bytes,
          to: address,
          on: DeviceId(id),
          withResponse: withResponse,
          timeout: .milliseconds(Int64(timeoutMs))
        )
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func getMaxWriteLength(id: String) throws -> any Promise<Double> {
    Promise_double.async {
      do {
        return Double(try await CompositionRoot.shared.getMaxWriteLength(on: DeviceId(id)))
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func readRssi(id: String, timeoutMs: Double) throws -> any Promise<Double> {
    Promise_double.async {
      do {
        return Double(try await CompositionRoot.shared.readRssi(on: DeviceId(id), timeout: .milliseconds(Int64(timeoutMs))))
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }
}

extension HybridGatt {
  static func subscribe(
    id: String,
    service: String,
    characteristic: String,
    onValues: any Callback<([ArrayBuffer]), Void>,
    onError: any Callback<(String), Void>,
    onEnd: any Callback<(), Void>
  ) throws -> any Promise<Double> {
    let address = try CharacteristicAddress(service, characteristic)
    return Promise_double.async {
      let subscription: (id: Int, events: AsyncStream<SubscriptionEvent>)
      do {
        subscription = try await CompositionRoot.shared.subscribe(to: address, on: DeviceId(id))
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
      let batcher = ValueBatcher { values in
        let buffers = values.compactMap { try? ArrayBuffer.copy(data: Data($0)) }
        onValues.callAndForget(buffers)
      }
      // The stream ends when the subscription is removed or the device is forgotten; so does this task.
      let events = subscription.events
      Task {
        for await event in events {
          switch event {
          case let .value(value):
            await batcher.add(value)
          case let .failed(error):
            await batcher.flush()
            onError.callAndForget(error.jsMessage)
          case .ended:
            await batcher.flush()
            onEnd.callAndForget()
          }
        }
        await batcher.flush()
      }
      return Double(subscription.id)
    }
  }

  static func unsubscribe(subscription: Double) throws -> any Promise<Void> {
    Promise_void.async {
      await CompositionRoot.shared.unsubscribe(Int(subscription))
    }
  }
}

/// Collects notified values and hands them to JavaScript in one call about every 16 ms (decision A4), in order, so a
/// fast device doesn't flood the bridge.
actor ValueBatcher {
  private let deliver: @Sendable ([[UInt8]]) -> Void
  private var pending: [[UInt8]] = []
  private var isScheduled = false

  init(deliver: @escaping @Sendable ([[UInt8]]) -> Void) {
    self.deliver = deliver
  }

  func add(_ value: [UInt8]) {
    pending.append(value)
    guard !isScheduled else { return }
    isScheduled = true
    Task {
      try? await Task.sleep(for: .milliseconds(16))
      self.flush()
    }
  }

  func flush() {
    isScheduled = false
    guard !pending.isEmpty else { return }
    let values = pending
    pending = []
    deliver(values)
  }
}

extension CharacteristicAddress {
  /// From the UUIDs JavaScript passed, short or full; a malformed one rejects with a message naming it.
  init(_ service: String, _ characteristic: String) throws {
    guard let serviceUuid = ServiceUuid(service) else {
      throw RuntimeError(BleError(code: .unknown, message: "Not a service UUID: \(service)").jsMessage)
    }
    guard let characteristicUuid = CharacteristicUuid(characteristic) else {
      throw RuntimeError(BleError(code: .unknown, message: "Not a characteristic UUID: \(characteristic)").jsMessage)
    }
    self.init(service: serviceUuid, characteristic: characteristicUuid)
  }
}
