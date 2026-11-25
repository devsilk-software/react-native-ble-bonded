import BondedFeatures
import Foundation
import NitroModules

/// The `Scanner` HybridObject: calls the `scanning` feature's use cases and maps between them and the generated
/// Nitro types. Nothing else lives here (decision A5).
final class HybridScanner: HybridScannerSpec {
  static func start(
    options: NativeScanOptions,
    onDevice: any Callback<(NativeScannedDevice), Void>,
    onStop: any Callback<(NativeScanStop), Void>
  ) throws -> any Promise<Double> {
    let request = try ScanRequest(options)
    return Promise_double.async {
      let session: ScanSession
      do {
        session = try await CompositionRoot.shared.startScan(request)
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
      // The session's stream finishes with the scan, so this task ends too; nothing needs removing.
      let events = session.events
      Task {
        for await event in events {
          switch event {
          case let .device(device):
            if let device = try? NativeScannedDevice(device) {
              onDevice.callAndForget(device)
            }
          case let .stopped(reason, error):
            onStop.callAndForget(NativeScanStop(reason, error: error))
          }
        }
      }
      return Double(session.id)
    }
  }

  static func stop(id: Double) throws {
    Task { await CompositionRoot.shared.stopScan(Int(id)) }
  }
}

extension BleError {
  /// How a `BleError` crosses to JavaScript: `[code] message`, `[code:platformCode] message`, and the remaining fields
  /// as JSON before the closing bracket when there are any (`[valueTooLong {"length":600,"maxLength":182}] …`), which
  /// `src/api` turns back into a `BleError`.
  var jsMessage: String {
    let platform = platformCode.map { ":\($0)" } ?? ""
    var fields: [String: any Sendable] = [:]
    if let attError { fields["attError"] = attError }
    if let length { fields["length"] = length }
    if let maxLength { fields["maxLength"] = maxLength }
    if let chunkIndex { fields["chunkIndex"] = chunkIndex }
    if let chunkCount { fields["chunkCount"] = chunkCount }
    if let sentLength { fields["sentLength"] = sentLength }
    let json = fields.isEmpty ? "" : (try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys]))
      .flatMap { String(data: $0, encoding: .utf8) }
      .map { " " + $0 } ?? ""
    return "[\(code.rawValue)\(platform)\(json)] \(message)"
  }
}

extension ScanRequest {
  init(_ options: NativeScanOptions) throws {
    let services = try options.services.map { text in
      guard let uuid = ServiceUuid(text) else {
        throw RuntimeError(BleError(code: .unknown, message: "Not a service UUID: \(text)", operation: "scan").jsMessage)
      }
      return uuid
    }
    let name: NameFilter? =
      if let exact = options.nameEquals {
        .exact(exact)
      } else if options.nameStartsWith != nil || options.nameEndsWith != nil || options.nameIncludes != nil {
        .pattern(startsWith: options.nameStartsWith, endsWith: options.nameEndsWith, includes: options.nameIncludes)
      } else {
        nil
      }
    self.init(
      services: services,
      name: name,
      allowDuplicates: options.allowDuplicates,
      fullAdvertisement: options.fullAdvertisement,
      timeout: options.timeoutMs > 0 ? .milliseconds(Int64(options.timeoutMs)) : nil
    )
  }
}

extension NativeScannedDevice {
  init(_ device: ScannedDevice) throws {
    let buffer = { (bytes: [UInt8]) in try ArrayBuffer.copy(data: Data(bytes)) }
    let full = device.full
    self.init(
      id: device.id.rawValue,
      name: device.name,
      rssi: Double(device.rssi),
      serviceUuids: device.serviceUuids.map(\.rawValue),
      manufacturerData: try device.manufacturerData.map {
        ManufacturerData(companyId: Double($0.companyId), data: try buffer($0.data))
      },
      serviceData: try full.map { full in
        try full.serviceData.map { NativeServiceData(uuid: $0.key.rawValue, data: try buffer($0.value)) }
      },
      txPowerLevel: full?.txPowerLevel.map(Double.init),
      isConnectable: full?.isConnectable,
      rawManufacturerData: try full?.rawManufacturerData.map(buffer),
      solicitedServiceUuids: full?.solicitedServiceUuids.map(\.rawValue),
      overflowServiceUuids: full?.overflowServiceUuids?.map(\.rawValue),
      rawScanRecord: nil,
      primaryPhy: nil,
      secondaryPhy: nil,
      timestamp: full.map { Double($0.timestamp) }
    )
  }
}

extension NativeScanStop {
  init(_ reason: BondedFeatures.ScanStopReason, error: BleError?) {
    let stopReason: NitroBleBonded.ScanStopReason =
      switch reason {
      case .stopped: .stopped
      case .replaced: .replaced
      case .timeout: .timeout
      case .failed: .failed
      }
    self.init(
      reason: stopReason,
      errorCode: error?.code.rawValue,
      errorMessage: error?.message,
      platformCode: error?.platformCode.map(Double.init)
    )
  }
}
