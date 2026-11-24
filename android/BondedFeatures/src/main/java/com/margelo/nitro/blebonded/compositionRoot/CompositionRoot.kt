package com.margelo.nitro.blebonded.compositionRoot

import android.content.Context
import com.margelo.nitro.blebonded.features.bluetoothState.adapters.androidAdapterStateSource.AndroidAdapterStateSource
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.getAdapterState.GetAdapterState
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.observeAdapterState.ObserveAdapterState
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.publishAdapterStateEvents.PublishAdapterStateEvents
import com.margelo.nitro.blebonded.features.bluetoothState.application.useCases.requestBluetoothOn.RequestBluetoothOn
import com.margelo.nitro.blebonded.features.connection.adapters.androidConnectionSource.AndroidConnectionSource
import com.margelo.nitro.blebonded.features.connection.adapters.sharedPreferencesRememberedDeviceStore.SharedPreferencesRememberedDeviceStore
import com.margelo.nitro.blebonded.features.connection.application.services.ConnectionCoordinator
import com.margelo.nitro.blebonded.features.connection.application.useCases.configureConnections.ConfigureConnections
import com.margelo.nitro.blebonded.features.connection.application.useCases.connect.Connect
import com.margelo.nitro.blebonded.features.connection.application.useCases.disconnectDevice.DisconnectDevice
import com.margelo.nitro.blebonded.features.connection.application.useCases.forgetDevice.ForgetDevice
import com.margelo.nitro.blebonded.features.connection.application.useCases.getServices.GetServices
import com.margelo.nitro.blebonded.features.connection.application.useCases.observeConnections.ObserveConnections
import com.margelo.nitro.blebonded.features.diagnostics.adapters.fileDiagnosticsStore.FileDiagnosticsStore
import com.margelo.nitro.blebonded.features.gatt.adapters.androidCharacteristicSource.AndroidCharacteristicSource
import com.margelo.nitro.blebonded.features.gatt.application.useCases.readCharacteristic.ReadCharacteristic
import com.margelo.nitro.blebonded.features.gatt.application.useCases.writeCharacteristic.WriteCharacteristic
import com.margelo.nitro.blebonded.features.diagnostics.adapters.systemDiagnosticsEnvironment.SystemDiagnosticsEnvironment
import com.margelo.nitro.blebonded.features.diagnostics.application.services.DiagnosticsRecorder
import com.margelo.nitro.blebonded.features.diagnostics.application.useCases.clearDiagnostics.ClearDiagnostics
import com.margelo.nitro.blebonded.features.diagnostics.application.useCases.configureDiagnostics.ConfigureDiagnostics
import com.margelo.nitro.blebonded.features.diagnostics.application.useCases.getDiagnostics.GetDiagnostics
import com.margelo.nitro.blebonded.features.diagnostics.application.useCases.observeDiagnostics.ObserveDiagnostics
import com.margelo.nitro.blebonded.features.permissions.adapters.androidPermissionSource.AndroidPermissionSource
import com.margelo.nitro.blebonded.features.permissions.application.useCases.getPermissionStatus.GetPermissionStatus
import com.margelo.nitro.blebonded.features.permissions.application.useCases.observePermission.ObservePermission
import com.margelo.nitro.blebonded.features.permissions.application.useCases.publishPermissionEvents.PublishPermissionEvents
import com.margelo.nitro.blebonded.features.permissions.application.useCases.requestPermission.RequestPermission
import com.margelo.nitro.blebonded.features.scanning.adapters.androidScanSource.AndroidScanSource
import com.margelo.nitro.blebonded.features.scanning.adapters.systemScanEnvironment.SystemScanEnvironment
import com.margelo.nitro.blebonded.features.scanning.application.services.ScanCoordinator
import com.margelo.nitro.blebonded.features.scanning.application.useCases.startScan.StartScan
import com.margelo.nitro.blebonded.features.scanning.application.useCases.stopScan.StopScan
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothEnablePrompt
import com.margelo.nitro.blebonded.infrastructure.bluetooth.BluetoothRadio
import com.margelo.nitro.blebonded.infrastructure.bluetooth.GattClients
import com.margelo.nitro.blebonded.infrastructure.lifecycle.AppForeground
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionPrompt
import com.margelo.nitro.blebonded.infrastructure.permissions.PermissionStore
import com.margelo.nitro.blebonded.shared.domain.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Creates the shared Bluetooth client, the event bus and the adapters, and builds each use case with them. The Nitro
 * controllers get their use cases from here, passing the application context and the system dialogs,
 * which only the Nitro module can show, on first use.
 */
class CompositionRoot private constructor(context: Context, prompt: PermissionPrompt, enablePrompt: BluetoothEnablePrompt) {
  private val radio = BluetoothRadio(context.applicationContext)
  private val foreground = AppForeground(context)

  private val adapterStateSource = AndroidAdapterStateSource(radio, foreground, enablePrompt)
  val getAdapterState = GetAdapterState(adapterStateSource)
  val observeAdapterState = ObserveAdapterState(adapterStateSource)
  val requestBluetoothOn = RequestBluetoothOn(adapterStateSource)

  private val permissionSource = AndroidPermissionSource(radio, PermissionStore(context), prompt, foreground)
  val getPermissionStatus = GetPermissionStatus(permissionSource)
  val requestPermission = RequestPermission(permissionSource)
  val observePermission = ObservePermission(permissionSource)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val bus = EventBus()
  private val recorder =
    DiagnosticsRecorder(bus, FileDiagnosticsStore(context), SystemDiagnosticsEnvironment(foreground), scope)
  private val configure = ConfigureDiagnostics(recorder)
  val getDiagnostics = GetDiagnostics(recorder)
  val clearDiagnostics = ClearDiagnostics(recorder)
  val observeDiagnostics = ObserveDiagnostics(recorder)

  private val scanner = ScanCoordinator(AndroidScanSource(radio), SystemScanEnvironment(foreground), bus, scope)
  val startScan = StartScan(scanner)
  val stopScan = StopScan(scanner)

  private val gattClients = GattClients()
  private val connections =
    ConnectionCoordinator(
      AndroidConnectionSource(context.applicationContext, radio, gattClients),
      bus,
      scope,
      SharedPreferencesRememberedDeviceStore(context),
    )
  val connect = Connect(connections)
  val disconnect = DisconnectDevice(connections)
  val getServices = GetServices(connections)
  val observeConnections = ObserveConnections(connections)
  val configureConnections = ConfigureConnections(connections)
  val forgetDevice = ForgetDevice(connections)

  private val characteristics = AndroidCharacteristicSource(gattClients)
  val readCharacteristic = ReadCharacteristic(characteristics, bus)
  val writeCharacteristic = WriteCharacteristic(characteristics, bus)

  private val publishAdapterStateEvents = PublishAdapterStateEvents(observeAdapterState, bus)
  private val publishPermissionEvents = PublishPermissionEvents(observePermission, bus)
  private val transitions = Mutex()
  private var publishers: List<Job> = emptyList()

  /**
   * Turns diagnostics on or off. While on, Bonded observes the features natively, even without a JavaScript
   * listener, so the record is complete; while off, nothing observes for diagnostics. Calls are applied one
   * after another, so the recorder and the publishers always end in the same state.
   */
  suspend fun configureDiagnostics(enabled: Boolean?, capacity: Int?, gatt: Boolean? = null) =
    transitions.withLock {
      configure(enabled, capacity, gatt)
      if (enabled == null) return@withLock
      if (enabled && publishers.isEmpty()) {
        publishers = listOf(scope.launch { publishAdapterStateEvents() }, scope.launch { publishPermissionEvents() })
      } else if (!enabled) {
        publishers.forEach { it.cancel() }
        publishers = emptyList()
      }
    }

  companion object {
    @Volatile private var instance: CompositionRoot? = null

    fun get(context: Context, prompt: PermissionPrompt, enablePrompt: BluetoothEnablePrompt): CompositionRoot =
      instance ?: synchronized(this) {
        instance ?: CompositionRoot(context, prompt, enablePrompt).also { instance = it }
      }
  }
}
