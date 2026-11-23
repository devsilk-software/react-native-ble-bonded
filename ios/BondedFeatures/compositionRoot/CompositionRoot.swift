/// Creates the shared Bluetooth client, the event bus and the adapters, and builds each use case with them.
/// The Nitro controllers get their use cases from here.
public final class CompositionRoot: Sendable {
  public static let shared = CompositionRoot()

  public let getAdapterState: GetAdapterState
  public let observeAdapterState: ObserveAdapterState
  public let requestBluetoothOn: RequestBluetoothOn
  public let getPermissionStatus: GetPermissionStatus
  public let requestPermission: RequestPermission
  public let observePermission: ObservePermission
  public let getDiagnostics: GetDiagnostics
  public let clearDiagnostics: ClearDiagnostics
  public let observeDiagnostics: ObserveDiagnostics
  public let startScan: StartScan
  public let stopScan: StopScan
  public let connect: Connect
  public let disconnect: DisconnectDevice
  public let getServices: GetServices
  public let observeConnections: ObserveConnections

  private let configure: ConfigureDiagnostics
  private let publishers: EventPublishers
  private let transitions = Transitions()

  private init() {
    let central = BluetoothCentral()
    let bus = EventBus()

    let adapterStateSource = CoreBluetoothAdapterStateSource(central: central)
    getAdapterState = GetAdapterState(source: adapterStateSource)
    observeAdapterState = ObserveAdapterState(source: adapterStateSource)
    requestBluetoothOn = RequestBluetoothOn(source: adapterStateSource)

    let permissionSource = CoreBluetoothPermissionSource(central: central)
    getPermissionStatus = GetPermissionStatus(source: permissionSource)
    requestPermission = RequestPermission(source: permissionSource)
    observePermission = ObservePermission(source: permissionSource)

    let activity = AppLifecycle()
    let recorder = DiagnosticsRecorder(
      bus: bus,
      store: FileDiagnosticsStore(),
      environment: SystemDiagnosticsEnvironment(activity: activity)
    )
    configure = ConfigureDiagnostics(recorder: recorder)
    getDiagnostics = GetDiagnostics(recorder: recorder)
    clearDiagnostics = ClearDiagnostics(recorder: recorder)
    observeDiagnostics = ObserveDiagnostics(recorder: recorder)

    let scanner = ScanCoordinator(
      source: CoreBluetoothScanSource(central: central),
      environment: SystemScanEnvironment(activity: activity),
      bus: bus
    )
    startScan = StartScan(coordinator: scanner)
    stopScan = StopScan(coordinator: scanner)

    let connections = ConnectionCoordinator(source: CoreBluetoothConnectionSource(central: central), bus: bus)
    connect = Connect(coordinator: connections)
    disconnect = DisconnectDevice(coordinator: connections)
    getServices = GetServices(coordinator: connections)
    observeConnections = ObserveConnections(coordinator: connections)

    publishers = EventPublishers(
      adapterState: PublishAdapterStateEvents(observe: observeAdapterState, bus: bus),
      permission: PublishPermissionEvents(observe: observePermission, bus: bus)
    )
  }

  /// Turns diagnostics on or off. While on, Bonded observes the features natively, even without a JavaScript
  /// listener, so the record is complete; while off, nothing observes for diagnostics.
  /// Calls are applied one after another, in the order they arrive, so the recorder and the publishers always
  /// end in the same state.
  public func configureDiagnostics(enabled: Bool?, capacity: Int?) async {
    let configure = self.configure
    let publishers = self.publishers
    await transitions.run {
      await configure(enabled: enabled, capacity: capacity)
      if let enabled {
        await publishers.setRunning(enabled)
      }
    }
  }
}

/// Runs operations one after another, each to completion, in the order they were asked for.
private actor Transitions {
  private var last: Task<Void, Never>?

  func run(_ operation: @escaping @Sendable () async -> Void) async {
    let previous = last
    let task = Task {
      await previous?.value
      await operation()
    }
    last = task
    await task.value
  }
}

/// The features' event publishers, running only while something records their events.
private actor EventPublishers {
  private let adapterState: PublishAdapterStateEvents
  private let permission: PublishPermissionEvents
  private var tasks: [Task<Void, Never>] = []

  init(adapterState: PublishAdapterStateEvents, permission: PublishPermissionEvents) {
    self.adapterState = adapterState
    self.permission = permission
  }

  func setRunning(_ isRunning: Bool) {
    if isRunning, tasks.isEmpty {
      let adapterState = self.adapterState
      let permission = self.permission
      tasks = [Task { await adapterState() }, Task { await permission() }]
    } else if !isRunning {
      tasks.forEach { $0.cancel() }
      tasks.removeAll()
    }
  }
}
