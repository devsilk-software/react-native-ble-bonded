/// Where a device's connection stands. `connected` means ready: its services are discovered, so an app never sees
/// a half-ready device.
public enum ConnectionState: String, Sendable, Equatable {
  case disconnected
  case connecting
  case connected
  case disconnecting
}

/// Why a device disconnected.
public enum DisconnectReason: String, Sendable, Equatable {
  /// The app called `disconnect()`.
  case requested
  /// Out of range or a radio dropout.
  case outOfRange
  /// The device ended the connection.
  case deviceDisconnected
  case bluetoothOff
  /// Anything else, including a connection attempt that failed or timed out.
  case failed
}

/// The last time a device disconnected, and why. `error` is set for every reason but `requested`.
public struct Disconnect: Sendable, Equatable {
  public let reason: DisconnectReason
  public let error: BleError?

  public init(reason: DisconnectReason, error: BleError? = nil) {
    self.reason = reason
    self.error = error
  }
}

/// A device's connection as listeners see it.
public struct ConnectionChange: Sendable, Equatable {
  public let id: DeviceId
  public let name: String?
  public let state: ConnectionState
  public let lastDisconnect: Disconnect?
  /// Set while the device is remembered.
  public let remembered: RememberedDevice?

  public init(id: DeviceId, name: String?, state: ConnectionState, lastDisconnect: Disconnect?, remembered: RememberedDevice? = nil) {
    self.id = id
    self.name = name
    self.state = state
    self.lastDisconnect = lastDisconnect
    self.remembered = remembered
  }
}

/// How to connect.
public struct ConnectOptions: Sendable, Equatable {
  /// Give up after this long; `nil` to wait until the device appears.
  public let timeout: Duration?
  /// Remember the device once connected, so it can connect again after a relaunch.
  public let remember: Bool
  /// How the device was found, kept with it to find it again when its ID changes.
  public let identity: DeviceIdentity?

  public init(timeout: Duration? = .seconds(30), remember: Bool = true, identity: DeviceIdentity? = nil) {
    self.timeout = timeout
    self.remember = remember
    self.identity = identity
  }
}

/// How the platform should look for the device on one attempt. iOS always waits for the device; Android tries a
/// fast direct connection first and a waiting one after that.
public enum ConnectMode: Sendable, Equatable {
  case direct
  case waiting
}

/// Whether the phone can connect right now, and if not, why.
public enum ConnectionReadiness: Sendable, Equatable {
  case ready
  case bluetoothOff
  case unauthorized
  case unsupported

  public var error: BleError? {
    switch self {
    case .ready: nil
    case .bluetoothOff: BleError(code: .bluetoothOff, message: "Bluetooth is off", operation: "connect")
    case .unauthorized: BleError(code: .unauthorized, message: "Bluetooth permission is not granted", operation: "connect")
    case .unsupported: BleError(code: .unsupported, message: "This phone has no Bluetooth Low Energy", operation: "connect")
    }
  }
}

/// When to retry a failed attempt and how to look for the device next.
public struct RetryPolicy: Sendable, Equatable {
  /// Direct attempts before switching to a waiting one.
  public let directAttempts: Int

  public init(directAttempts: Int = 3) {
    self.directAttempts = directAttempts
  }

  /// The pause before attempt `attempt` (2 for the first retry): 250 ms, doubling, at most 2 s.
  public func backoff(beforeAttempt attempt: Int) -> Duration {
    .milliseconds(min(2000, 250 << max(0, attempt - 2)))
  }

  public func mode(forAttempt attempt: Int) -> ConnectMode {
    attempt <= directAttempts ? .direct : .waiting
  }
}
