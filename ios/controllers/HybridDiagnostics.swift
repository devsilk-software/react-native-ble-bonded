import BondedFeatures
import NitroModules

/// The `Diagnostics` HybridObject: calls the `diagnostics` feature's use cases and maps their results to the
/// generated Nitro types. Nothing else lives here (decision A5).
final class HybridDiagnostics: HybridDiagnosticsSpec {
  private static let listeners = ListenerRegistry()

  static func configure(config: DiagnosticsConfig) throws -> any Promise<Void> {
    Promise_void.async {
      await CompositionRoot.shared.configureDiagnostics(enabled: config.enabled, capacity: config.capacity.map { Int($0) })
    }
  }

  static func getEvents(since: Double?) throws -> any Promise<[NitroBleBonded.DiagnosticEvent]> {
    Promise___NitroBleBonded__SwiftContainer_swift__Array___NitroBleBonded__DiagnosticEvent_.async {
      await CompositionRoot.shared.getDiagnostics(since: since.map { Int64($0) }).map(NitroBleBonded.DiagnosticEvent.init)
    }
  }

  static func clear() throws -> any Promise<Void> {
    Promise_void.async {
      await CompositionRoot.shared.clearDiagnostics()
    }
  }

  static func addListener(onEvent: any Callback<(NitroBleBonded.DiagnosticEvent), Void>) throws -> Double {
    listeners.add(CompositionRoot.shared.observeDiagnostics()) { event in
      onEvent.callAndForget(NitroBleBonded.DiagnosticEvent(event))
    }
  }

  static func removeListener(id: Double) throws {
    listeners.remove(id)
  }
}

extension NitroBleBonded.DiagnosticEvent {
  init(_ event: BondedFeatures.DiagnosticEvent) {
    self.init(
      type: event.kind == .stateChanged ? .statechanged : .permissionchanged,
      at: Double(event.at),
      appState: event.appActivity == .background ? .background : .foreground,
      deviceId: event.deviceId,
      code: event.code,
      platformCode: event.platformCode.map { Double($0) },
      detail: event.detail
    )
  }
}
