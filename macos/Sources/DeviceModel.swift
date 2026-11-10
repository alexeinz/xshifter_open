import AppKit
import Combine
import CoreBluetooth
import Foundation

@MainActor
// CoreBluetooth is constructed with queue:.main; delegate isolation is checked at runtime.
final class DeviceModel: NSObject, ObservableObject, @preconcurrency CBCentralManagerDelegate,
  @preconcurrency CBPeripheralDelegate
{
  struct Found: Identifiable {
    let peripheral: CBPeripheral
    let rssi: Int
    var id: UUID { peripheral.identifier }
  }
  @Published var devices: [Found] = []
  @Published var scanning = false
  @Published var authenticated = false
  @Published var demo = false
  @Published var bench = false
  @Published var working = false
  @Published var status = "Ready · connect or explore the demo"
  @Published var deviceName = "No device connected"
  @Published var firmware = "Not read"
  @Published var peer = "Not read"
  @Published var battery: Int?
  @Published var batteryDate: Date?
  @Published var live: GearTable?
  @Published var profile = Profile()
  @Published var selectedGear = 0
  @Published var log: [String] = []
  @Published var lastBackup: URL?
  private var central: CBCentralManager?
  private var peripheral: CBPeripheral?
  private var write: CBCharacteristic?
  private var queue: [(Data, Double)] = []
  private var writing = false
  private var writeDelay: Double = 0
  private var timeout: DispatchWorkItem?
  private var readTimeout: DispatchWorkItem?
  private var versionTimeout: DispatchWorkItem?
  private var scheduled: [DispatchWorkItem] = []
  private var reader = TableReader()
  private var awaiting = false
  private var expected: [Int]?
  private var expectedAdjustment: Int?
  private var tableDate: Date?
  private var identity = Data()
  private var generation = 0
  private var workflow = false
  var canWrite: Bool { (demo || authenticated && bench) && !working && live != nil }
  var canDisconnect: Bool { demo || authenticated || scanning || peripheral != nil }
  var liveIsFresh: Bool { demo || tableDate.map { Date().timeIntervalSince($0) < 15 } ?? false }

  override init() {
    super.init()
    if let data = UserDefaults.standard.data(forKey: "draft"), let p = try? Profile.decode(data) {
      profile = p
    }
    if CommandLine.arguments.contains("--demo") { startDemo() }
  }
  func record(_ value: String) {
    log.insert(Date().formatted(date: .omitted, time: .standard) + "  " + value, at: 0)
    if log.count > 120 { log.removeLast(log.count - 120) }
  }
  func report(_ error: Error) {
    status = error.localizedDescription
    record(status)
  }
  private func after(_ delay: Double, _ action: @escaping () -> Void) {
    let token = generation
    let item = DispatchWorkItem { [weak self] in
      guard let self, self.generation == token else { return }
      action()
    }
    scheduled.append(item)
    DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
  }
  private func arm(_ seconds: Double, _ message: String) {
    timeout?.cancel()
    let item = DispatchWorkItem { [weak self] in self?.disconnect(message) }
    timeout = item
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
  }
  func scan() {
    guard !working else { return }
    disconnect("Searching for XShifter…")
    demo = false
    devices = []
    scanning = true
    if central == nil {
      central = CBCentralManager(delegate: self, queue: .main)
    } else {
      beginScan()
    }
  }
  private func beginScan() {
    guard scanning, let central else { return }
    guard central.state == .poweredOn else {
      if central.state != .unknown && central.state != .resetting {
        scanning = false
        status = "Bluetooth unavailable or permission denied · check System Settings"
      }
      return
    }
    central.scanForPeripherals(withServices: [CBUUID(string: Wire.service)])
    status = "Searching · wake XShifter and close the Android app"
    after(12) { [weak self] in
      self?.central?.stopScan()
      self?.scanning = false
    }
  }
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    if central.state == .poweredOn {
      beginScan()
    } else if central.state != .unknown && central.state != .resetting {
      disconnect("Bluetooth unavailable or permission denied")
    }
  }
  func centralManager(
    _ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    if let i = devices.firstIndex(where: { $0.id == p.identifier }) {
      devices[i] = Found(peripheral: p, rssi: RSSI.intValue)
    } else {
      devices.append(Found(peripheral: p, rssi: RSSI.intValue))
    }
  }
  func connect(_ found: Found) {
    disconnect("Connecting…")
    demo = false
    scanning = false
    central?.stopScan()
    peripheral = found.peripheral
    peripheral?.delegate = self
    deviceName = found.peripheral.name ?? "XShifter"
    let key = "identity:" + found.id.uuidString
    if let stored = UserDefaults.standard.data(forKey: key), stored.count == 8 {
      identity = stored
    } else {
      identity = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
      UserDefaults.standard.set(identity, forKey: key)
    }
    central?.connect(found.peripheral)
    arm(15, "Connection timed out")
  }
  func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
    guard p === peripheral else { return }
    status = "Discovering services…"
    p.discoverServices([CBUUID(string: Wire.service)])
  }
  func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?)
  {
    guard p === peripheral else { return }
    disconnect(error?.localizedDescription ?? "Connection failed")
  }
  func centralManager(
    _ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?
  ) {
    guard p === peripheral else { return }
    disconnect(error?.localizedDescription ?? "Disconnected")
  }
  func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
    guard p === peripheral else { return }
    guard error == nil,
      let s = p.services?.first(where: { $0.uuid == CBUUID(string: Wire.service) })
    else {
      disconnect("XShifter service not found")
      return
    }
    p.discoverCharacteristics([CBUUID(string: Wire.write), CBUUID(string: Wire.notify)], for: s)
  }
  func peripheral(
    _ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?
  ) {
    guard p === peripheral else { return }
    write = service.characteristics?.first(where: { $0.uuid == CBUUID(string: Wire.write) })
    guard error == nil, let w = write, w.properties.contains(.write),
      let n = service.characteristics?.first(where: { $0.uuid == CBUUID(string: Wire.notify) }),
      n.properties.contains(.notify)
    else {
      disconnect("Unsupported Bluetooth characteristics")
      return
    }
    p.setNotifyValue(true, for: n)
  }
  func peripheral(
    _ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?
  ) {
    guard p === peripheral, c.uuid == CBUUID(string: Wire.notify) else { return }
    guard error == nil, c.isNotifying else {
      disconnect("Could not enable notifications")
      return
    }
    timeout?.cancel()
    status = "Identifying app…"
    do {
      try send(Wire.login(identity))
      after(10) { [weak self] in
        if self?.authenticated == false {
          self?.disconnect("No login reply · close other XShifter apps and retry")
        }
      }
    } catch { report(error) }
  }
  private func send(_ data: Data, delay: Double = 0) throws {
    guard peripheral != nil, write != nil else { throw ShiftError.invalid("Not connected") }
    guard queue.count < 32 else { throw ShiftError.invalid("Too many pending commands") }
    queue.append((data, delay))
    working = true
    pump()
  }
  private func pump() {
    guard !writing, !queue.isEmpty, let p = peripheral, let c = write else { return }
    let item = queue.removeFirst()
    writing = true
    writeDelay = item.1
    record("TX " + Wire.hex(item.0))
    p.writeValue(item.0, for: c, type: .withResponse)
    arm(8, "Bluetooth write timed out · disconnected; commands were not replayed")
  }
  func peripheral(
    _ p: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?
  ) {
    guard p === peripheral else { return }
    timeout?.cancel()
    guard error == nil else {
      disconnect("Bluetooth command failed: " + error!.localizedDescription)
      return
    }
    after(writeDelay) { [weak self] in
      guard let self else { return }
      self.writing = false
      if self.queue.isEmpty {
        self.working = self.awaiting || self.workflow
      } else {
        self.pump()
      }
    }
  }
  func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
    guard p === peripheral, c.uuid == CBUUID(string: Wire.notify), error == nil, let data = c.value
    else { return }
    let b = [UInt8](data)
    record("RX " + Wire.hex(data))
    if b.count == 18 && Array(b.prefix(2)) == [0xdf, 0x89] && !authenticated {
      authenticated = true
      status = "Connected · reading device"
      readState()
      readVersion()
      return
    }
    guard authenticated else { return }
    if let v = Wire.firmware(b) {
      firmware = v
      versionTimeout?.cancel()
    }
    if let cv = Wire.battery(b) {
      battery = cv
      batteryDate = Date()
    }
    if b.count >= 18 && Array(b.prefix(2)) == [0xaa, 0xcf] {
      let ver =
        String(bytes: b.dropFirst(12).filter { $0 >= 32 && $0 <= 126 }, encoding: .ascii)
        ?? "Unknown"
      peer = "Pod firmware " + ver + " · ID " + Wire.hex(Data(b[3..<11]))
    }
    if b.count == 3 && Array(b.prefix(2)) == [0xee, 0xce], var table = live {
      table.gear = Int(b[2])
      live = table
    }
    if awaiting {
      do {
        if let table = try reader.accept(b) {
          awaiting = false
          readTimeout?.cancel()
          live = table
          tableDate = Date()
          if let proposed = expected {
            status =
              table.positions == proposed && table.adjustment == expectedAdjustment
              ? "Readback matches · power-cycle persistence not tested"
              : "Readback differs · review calibration before use"
            expected = nil
            expectedAdjustment = nil
          } else {
            status = "Calibration read · \(table.positions.count) gears"
          }
          selectedGear = min(max(0, table.gear), max(0, table.positions.count - 1))
          working = writing || !queue.isEmpty || workflow
        }
      } catch { report(error) }
    }
  }
  func disconnect(_ message: String = "Disconnected") {
    generation += 1
    scheduled.forEach { $0.cancel() }
    scheduled = []
    timeout?.cancel()
    readTimeout?.cancel()
    versionTimeout?.cancel()
    central?.stopScan()
    scanning = false
    let old = peripheral
    peripheral = nil
    write = nil
    if let old { central?.cancelPeripheralConnection(old) }
    queue = []
    writing = false
    working = false
    workflow = false
    awaiting = false
    expected = nil
    expectedAdjustment = nil
    authenticated = false
    bench = false
    demo = false
    live = nil
    tableDate = nil
    battery = nil
    batteryDate = nil
    firmware = "Not read"
    peer = "Not read"
    status = message
  }
  func readState() {
    if demo {
      status = "Demo · simulated data only"
      return
    }
    guard authenticated, !awaiting, !workflow else { return }
    reader = TableReader()
    awaiting = true
    tableDate = nil
    working = true
    do {
      try send(Wire.read)
      try send(Wire.peer)
    } catch {
      report(error)
      awaiting = false
      working = writing || !queue.isEmpty
      return
    }
    readTimeout?.cancel()
    let item = DispatchWorkItem { [weak self] in
      guard let self else { return }
      self.awaiting = false
      self.expected = nil
      self.expectedAdjustment = nil
      self.working = self.writing || !self.queue.isEmpty || self.workflow
      self.status = "No complete gear-table reply · changes remain unverified"
    }
    readTimeout = item
    DispatchQueue.main.asyncAfter(deadline: .now() + 12, execute: item)
  }
  func readVersion(_ attempt: Int = 0) {
    guard authenticated else { return }
    versionTimeout?.cancel()
    if attempt >= 3 {
      firmware = "No supported reply"
      return
    }
    firmware = "Reading…"
    do { try send(Wire.version) } catch {
      report(error)
      return
    }
    let item = DispatchWorkItem { [weak self] in self?.readVersion(attempt + 1) }
    versionTimeout = item
    DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: item)
  }
  func startDemo() {
    disconnect()
    demo = true
    bench = true
    deviceName = "Demo drivetrain"
    live = GearTable(positions: (0..<11).map { $0 * 1500 }, adjustment: 0, gear: 3)
    battery = 1200
    batteryDate = Date()
    firmware = "Demo · not a device version"
    selectedGear = 3
    status = "Demo · no Bluetooth or motor commands"
    loadDeviceDraft()
  }
  func requireBench() throws {
    guard canWrite else {
      throw ShiftError.invalid(
        "Connect, read the device, and enable Bench mode; wait for pending operations")
    }
    guard liveIsFresh else {
      readState()
      throw ShiftError.invalid("Refreshing device state · retry after readback")
    }
  }
  func shift() {
    do {
      try requireBench()
      guard let table = live else { return }
      let command = try Wire.shift(selectedGear, count: table.positions.count)
      if demo {
        live?.gear = selectedGear
        return
      }
      try send(command)
      workflow = true
      tableDate = nil
      after(1.3) { [weak self] in
        self?.workflow = false
        self?.readState()
      }
    } catch { report(error) }
  }
  func trim(_ delta: Int) {
    do {
      try requireBench()
      guard var table = live, table.positions.indices.contains(selectedGear) else { return }
      guard table.gear == selectedGear else {
        throw ShiftError.invalid("Shift to the selected gear before trimming")
      }
      table.positions[selectedGear] += delta
      try Wire.validate(table.positions)
      if demo {
        live = table
        return
      }
      try backup()
      expected = table.positions
      expectedAdjustment = table.adjustment
      try send(Wire.trim(selectedGear, delta: delta))
      tableDate = nil
      workflow = true
      after(1.3) { [weak self] in
        self?.workflow = false
        self?.readState()
      }
    } catch { report(error) }
  }
  func commit() {
    do {
      try requireBench()
      if demo {
        status = "Demo tuning saved in memory only"
        return
      }
      try backup()
      expected = live?.positions
      expectedAdjustment = live?.adjustment
      try send(Wire.commit)
      workflow = true
      tableDate = nil
      after(1.3) { [weak self] in
        self?.workflow = false
        self?.readState()
      }
    } catch { report(error) }
  }
  func applyProfile() {
    do {
      try requireBench()
      try profile.validate(requireCalibrated: true)
      let frames = try Wire.upload(profile.positions, adjustment: profile.adjustment ?? 0)
      if demo {
        live = GearTable(positions: profile.positions, adjustment: profile.adjustment ?? 0, gear: 0)
        status = "Demo table applied only in memory"
        return
      }
      try backup()
      expected = profile.positions
      expectedAdjustment = profile.adjustment ?? 0
      workflow = true
      working = true
      tableDate = nil
      for frame in frames { try send(frame, delay: 1.1) }
      try send(Wire.commit, delay: 1.3)
      waitForUpload()
      status = "Writing table · keep Mac awake and app open"
    } catch { report(error) }
  }
  private func waitForUpload() {
    after(0.1) { [weak self] in
      guard let self else { return }
      if self.writing || !self.queue.isEmpty {
        self.waitForUpload()
      } else {
        self.workflow = false
        self.readState()
      }
    }
  }
  func loadDeviceDraft() {
    guard let table = live, !table.positions.isEmpty else { return }
    profile.count = table.positions.count
    profile.positions = table.positions
    profile.teeth = []
    profile.calibrated = true
    profile.adjustment = table.adjustment
    profile.device = demo ? nil : peripheral?.identifier.uuidString
    if !demo { persistDraft() }
  }
  func persistDraft() {
    if let data = try? profile.encoded() { UserDefaults.standard.set(data, forKey: "draft") }
  }
  private func backup() throws {
    guard let table = live, !table.positions.isEmpty else { return }
    var p = Profile()
    p.name = "Backup · " + deviceName
    p.count = table.positions.count
    p.positions = table.positions
    p.adjustment = table.adjustment
    p.calibrated = true
    p.device = peripheral?.identifier.uuidString
    let base = try FileManager.default.url(
      for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
    )
    .appendingPathComponent("XShifter Open/Backups", isDirectory: true)
    try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    let url = base.appendingPathComponent(
      "xshifter-\(Int(Date().timeIntervalSince1970*1000))-\(UUID().uuidString.prefix(6)).json")
    try p.encoded().write(to: url, options: .atomic)
    lastBackup = url
    record("Backup saved: " + url.lastPathComponent)
  }
  func importProfile() {
    let panel = NSOpenPanel()
    panel.allowedContentTypes = [.json]
    panel.allowsMultipleSelection = false
    guard panel.runModal() == .OK, let url = panel.url else { return }
    do {
      let data = try Data(contentsOf: url, options: .mappedIfSafe)
      profile = try Profile.decode(data)
      persistDraft()
      status = "Imported profile · no device changes"
    } catch { report(error) }
  }
  func exportProfile() {
    let panel = NSSavePanel()
    panel.allowedContentTypes = [.json]
    panel.nameFieldStringValue = "xshifter-profile.json"
    guard panel.runModal() == .OK, let url = panel.url else { return }
    do {
      try profile.encoded().write(to: url, options: .atomic)
      persistDraft()
      status = "Profile exported · compatible with Android"
    } catch { report(error) }
  }
  func showBackups() {
    if let lastBackup {
      NSWorkspace.shared.activateFileViewerSelecting([lastBackup])
    } else {
      status = "A local backup is saved automatically before each device calibration change"
    }
  }
}
