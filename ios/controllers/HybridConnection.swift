import BondedFeatures
import NitroModules

/// The `Connection` HybridObject: calls the `connection` feature's use cases and maps between them and the
/// generated Nitro types. Nothing else lives here (decision A5).
final class HybridConnection: HybridConnectionSpec {
  private static let listeners = ListenerRegistry()

  static func connect(id: String, options: NativeConnectOptions) throws -> any Promise<Void> {
    let options = ConnectOptions(
      timeout: options.timeoutMs > 0 ? .milliseconds(Int64(options.timeoutMs)) : nil,
      remember: options.remember,
      identity: options.identity.map {
        DeviceIdentity(name: $0.name, services: $0.services, companyId: $0.companyId.map { Int($0) })
      }
    )
    return Promise_void.async {
      do {
        try await CompositionRoot.shared.connect(DeviceId(id), options: options)
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func disconnect(id: String) throws -> any Promise<Void> {
    Promise_void.async {
      await CompositionRoot.shared.disconnect(DeviceId(id))
    }
  }

  static func getConnections() throws -> any Promise<[NativeConnectionChange]> {
    Promise___NitroBleBonded__SwiftContainer_swift__Array___NitroBleBonded__NativeConnectionChange_.async {
      await CompositionRoot.shared.observeConnections.current().map(NativeConnectionChange.init)
    }
  }

  static func forget(id: String) throws -> any Promise<Void> {
    Promise_void.async {
      await CompositionRoot.shared.forgetDevice(DeviceId(id))
    }
  }

  static func getServices(id: String) throws -> any Promise<[NativeService]> {
    Promise___NitroBleBonded__SwiftContainer_swift__Array___NitroBleBonded__NativeService_.async {
      do {
        return try await CompositionRoot.shared.getServices(DeviceId(id)).map(NativeService.init)
      } catch let error as BleError {
        throw RuntimeError(error.jsMessage)
      }
    }
  }

  static func addListener(onChange: any Callback<(NativeConnectionChange), Void>) throws -> any Promise<Double> {
    Promise_double.async {
      let changes = await CompositionRoot.shared.observeConnections()
      return listeners.add(changes) { change in
        onChange.callAndForget(NativeConnectionChange(change))
      }
    }
  }

  static func removeListener(id: Double) throws {
    listeners.remove(id)
  }

  /// Android's workarounds don't apply to Core Bluetooth.
  static func configureAndroid(options: NativeAndroidConnectionOptions) throws {}
}

extension NativeConnectionChange {
  init(_ change: ConnectionChange) {
    let state: NitroBleBonded.ConnectionState =
      switch change.state {
      case .disconnected: .disconnected
      case .connecting: .connecting
      case .connected: .connected
      case .disconnecting: .disconnecting
      }
    let reason: NitroBleBonded.DisconnectReason? = change.lastDisconnect.map {
      switch $0.reason {
      case .requested: .requested
      case .outOfRange: .outofrange
      case .deviceDisconnected: .devicedisconnected
      case .bluetoothOff: .bluetoothoff
      case .failed: .failed
      }
    }
    let error = change.lastDisconnect?.error
    self.init(
      id: change.id.rawValue,
      name: change.name,
      state: state,
      reason: reason,
      errorCode: error?.code.rawValue,
      errorMessage: error?.message,
      platformCode: error?.platformCode.map(Double.init),
      isRemembered: change.remembered != nil,
      identity: change.remembered.map {
        NativeDeviceIdentity(name: $0.identity.name, services: $0.identity.services, companyId: $0.identity.companyId.map(Double.init))
      },
      lastConnectedAt: change.remembered.map { Double($0.lastConnectedAt) }
    )
  }
}

extension NativeService {
  init(_ service: GattService) {
    self.init(
      uuid: service.uuid.rawValue,
      characteristics: service.characteristics.map { characteristic in
        NativeCharacteristic(
          uuid: characteristic.uuid.rawValue,
          properties: characteristic.properties.map(NitroBleBonded.CharacteristicProperty.init)
        )
      }
    )
  }
}

extension NitroBleBonded.CharacteristicProperty {
  init(_ property: BondedFeatures.CharacteristicProperty) {
    switch property {
    case .broadcast: self = .broadcast
    case .read: self = .read
    case .writeWithoutResponse: self = .writewithoutresponse
    case .write: self = .write
    case .notify: self = .notify
    case .indicate: self = .indicate
    case .authenticatedSignedWrites: self = .authenticatedsignedwrites
    case .extendedProperties: self = .extendedproperties
    }
  }
}
