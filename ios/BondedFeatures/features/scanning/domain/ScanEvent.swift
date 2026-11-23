/// What a running scan reports.
public enum ScanEvent: Sendable, Equatable {
  case device(ScannedDevice)
  /// The scan has ended; nothing follows. `error` is set when it ended because something failed.
  case stopped(ScanStopReason, error: BleError?)
}

/// Why a scan ended.
public enum ScanStopReason: String, Sendable, Equatable {
  /// The app stopped it.
  case stopped
  /// A new scan replaced it.
  case replaced
  case timeout
  /// Bluetooth was switched off or permission was revoked.
  case failed
}

/// Whether the phone can scan right now, and if not, why.
public enum ScanReadiness: Sendable, Equatable {
  case ready
  case bluetoothOff
  case unauthorized
  case unsupported

  /// The error a scan reports when it can't run; `nil` when ready.
  public var error: BleError? {
    switch self {
    case .ready: nil
    case .bluetoothOff: BleError(code: .bluetoothOff, message: "Bluetooth is off", operation: "scan")
    case .unauthorized: BleError(code: .unauthorized, message: "Bluetooth permission is not granted", operation: "scan")
    case .unsupported: BleError(code: .unsupported, message: "This phone has no Bluetooth Low Energy", operation: "scan")
    }
  }
}
