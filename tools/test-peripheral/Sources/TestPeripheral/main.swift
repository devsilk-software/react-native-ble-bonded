import CoreBluetooth
import Foundation

// A test peripheral for Bonded's example app (README.md). Run with `swift run` from tools/test-peripheral.
//
// It advertises the name "Bonded Test" (or --name) and one service with four characteristics:
// - read:   a counter that grows on every read, as text
// - write:  accepts writes with and without response and prints them; checks that the counting pattern the example
//           app's chunked write sends (0, 1, 2, …) arrives in order
// - secure: needs an encrypted connection, so reading it pairs the phone; reads "bonded"
// - notify: sends a counter every second while a phone is subscribed; its user description (2901) reads "Counter"
//
// Type `quit` and Enter to stop. macOS may keep the radio link up after quitting, so the phone doesn't always see
// a disconnection (README.md).

@MainActor
enum TestService {
  static let service = CBUUID(string: "B0ED0001-0000-4000-8000-00000000B0ED")
  static let read = CBUUID(string: "B0ED0002-0000-4000-8000-00000000B0ED")
  static let write = CBUUID(string: "B0ED0003-0000-4000-8000-00000000B0ED")
  static let notify = CBUUID(string: "B0ED0004-0000-4000-8000-00000000B0ED")
  static let secure = CBUUID(string: "B0ED0006-0000-4000-8000-00000000B0ED")
}

/// Core Bluetooth calls the delegate on the main queue, so the conformance and all state are on the main actor.
@MainActor
final class Peripheral: NSObject, @MainActor CBPeripheralManagerDelegate {
  private let name: String
  private var manager: CBPeripheralManager?
  private var readCount = 0
  // Seconds since the tool started: one counter that never resets, so a resumed subscription jumps ahead.
  private let started = Date()
  private var notifyCount: Int { Int(Date().timeIntervalSince(started)) }
  private var subscribers: [CBCentral] = []
  private var ticker: Timer?
  private let notifyCharacteristic = CBMutableCharacteristic(
    type: TestService.notify, properties: [.notify, .read], value: nil, permissions: [.readable]
  )

  init(name: String) {
    self.name = name
    super.init()
    manager = CBPeripheralManager(delegate: self, queue: nil)
  }

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    stateChanged(peripheral.state)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: (any Error)?) {
    serviceAdded(failure: error.map { "Couldn't add the service: \($0.localizedDescription)" })
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: (any Error)?) {
    log(error.map { "Couldn't advertise: \($0.localizedDescription)" } ?? "Advertising as \"\(name)\". Type quit and Enter to stop.")
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    answerRead(request)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    answerWrites(requests)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    subscribed(central)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
    unsubscribed(central)
  }

  private func stateChanged(_ state: CBManagerState) {
    guard state == .poweredOn else {
      log("Bluetooth isn't ready (state \(state.rawValue)); check that it's on and that this terminal may use it.")
      return
    }
    let service = CBMutableService(type: TestService.service, primary: true)
    // Its user description, for reading a descriptor. macOS keeps it read-only, so writing it shows the device's refusal.
    notifyCharacteristic.descriptors = [CBMutableDescriptor(type: CBUUID(string: CBUUIDCharacteristicUserDescriptionString), value: "Counter")]
    service.characteristics = [
      CBMutableCharacteristic(type: TestService.read, properties: [.read], value: nil, permissions: [.readable]),
      CBMutableCharacteristic(type: TestService.write, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable]),
      notifyCharacteristic,
      // Needs an encrypted connection, so reading it starts pairing: the trigger for device.createBond().
      CBMutableCharacteristic(type: TestService.secure, properties: [.read], value: Data("bonded".utf8), permissions: [.readEncryptionRequired]),
    ]
    manager?.add(service)
  }

  private func serviceAdded(failure: String?) {
    if let failure { return log(failure) }
    manager?.startAdvertising([
      CBAdvertisementDataLocalNameKey: name,
      CBAdvertisementDataServiceUUIDsKey: [TestService.service],
    ])
  }

  private func answerRead(_ request: CBATTRequest) {
    let value: Data
    switch request.characteristic.uuid {
    case TestService.read:
      readCount += 1
      value = Data("read \(readCount)".utf8)
    case TestService.notify:
      value = Data("tick \(notifyCount)".utf8)
    default:
      manager?.respond(to: request, withResult: .attributeNotFound)
      return
    }
    guard request.offset <= value.count else {
      manager?.respond(to: request, withResult: .invalidOffset)
      return
    }
    request.value = value.subdata(in: request.offset..<value.count)
    manager?.respond(to: request, withResult: .success)
    log("Read \(request.characteristic.uuid): \(String(decoding: value, as: UTF8.self))")
  }

  private func answerWrites(_ requests: [CBATTRequest]) {
    for request in requests {
      let value = [UInt8](request.value ?? Data())
      let shown = value.count <= 32 ? value.map { String(format: "%02x", $0) }.joined(separator: " ") : "\(value.count) bytes"
      let pattern = countPattern(value).map { "; \($0)" } ?? ""
      log("Write \(request.characteristic.uuid): \(shown)\(pattern)")
    }
    if let first = requests.first {
      manager?.respond(to: first, withResult: .success)
    }
  }

  /// Bytes of the counting pattern (0, 1, 2, … 255, 0, …) taken in order so far.
  private var patternBytes = 0

  /// Follows the counting pattern across writes: a write that counts on from the last one adds to it, one that starts
  /// at 0 starts again. Returns what to print, or `nil` for a write that isn't part of the pattern.
  private func countPattern(_ value: [UInt8]) -> String? {
    guard value.count >= 2, zip(value, value.dropFirst()).allSatisfy({ $1 == $0 &+ 1 }) else { return nil }
    let expected = UInt8(truncatingIfNeeded: patternBytes)
    if value[0] != expected {
      guard value[0] == 0 else {
        defer { patternBytes = 0 }
        return "OUT OF ORDER: expected \(expected) after \(patternBytes) bytes, got \(value[0])"
      }
      patternBytes = 0
    }
    patternBytes += value.count
    return "\(patternBytes) bytes of the counting pattern in order"
  }

  private func subscribed(_ central: CBCentral) {
    subscribers.append(central)
    log("Phone subscribed to notifications (\(subscribers.count) subscribed)")
    guard ticker == nil else { return }
    ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
      MainActor.assumeIsolated { self.tick() }
    }
  }

  private func unsubscribed(_ central: CBCentral) {
    subscribers.removeAll { $0.identifier == central.identifier }
    log("Phone unsubscribed (\(subscribers.count) subscribed)")
    if subscribers.isEmpty {
      ticker?.invalidate()
      ticker = nil
    }
  }

  private func tick() {
    _ = manager?.updateValue(Data("tick \(notifyCount)".utf8), for: notifyCharacteristic, onSubscribedCentrals: nil)
  }
}

func log(_ message: String) {
  let time = Date().formatted(date: .omitted, time: .standard)
  print("[\(time)] \(message)")
  fflush(stdout)
}

let arguments = CommandLine.arguments
let name = arguments.firstIndex(of: "--name").flatMap { arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil } ?? "Bonded Test"
let peripheral = Peripheral(name: name)

// Commands arrive on standard input; Core Bluetooth runs on the main run loop.
Thread.detachNewThread {
  while let line = readLine() {
    if line.trimmingCharacters(in: .whitespaces) == "quit" {
      log("Quitting")
      exit(0)
    }
  }
}
RunLoop.main.run()
