/// What a subscription reports.
public enum SubscriptionEvent: Sendable, Equatable {
  case value([UInt8])
  /// Switching notifications back on after a reconnect failed; the subscription stays and tries again.
  case failed(BleError)
}
