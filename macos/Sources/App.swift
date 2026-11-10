import AppKit
import SwiftUI

private let lime = Color(red: 0.80, green: 0.98, blue: 0.34)
private let canvas = Color(red: 0.055, green: 0.075, blue: 0.08)
private let panel = Color(red: 0.085, green: 0.115, blue: 0.12)

@main
struct XShifterMacApp: App {
  @StateObject private var model = DeviceModel()
  var body: some Scene {
    WindowGroup("XShifter Open · Calibration") {
      Workbench(model: model)
        .frame(minWidth: 1060, minHeight: 700)
        .preferredColorScheme(.dark)
        .onDisappear { model.disconnect() }
        .onReceive(
          NotificationCenter.default.publisher(for: NSApplication.willTerminateNotification)
        ) { _ in model.disconnect() }
    }
    .defaultSize(width: 1280, height: 850)
    .commands {
      CommandGroup(replacing: .newItem) {
        Button("Import profile…") { model.importProfile() }.keyboardShortcut("o").disabled(
          model.working)
        Button("Export profile…") {
          NotificationCenter.default.post(
            name: Notification.Name("ExportXShifterDraft"), object: nil)
        }.keyboardShortcut("s").disabled(model.working)
      }
    }
  }
}

struct Workbench: View {
  @ObservedObject var model: DeviceModel
  @State private var selectedDevice: UUID?
  @State private var step = 30
  @State private var draftText: [String] = []
  @State private var teethText = ""
  @State private var showLog = false
  @State private var confirmation: Confirmation?
  struct Confirmation: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let action: () -> Void
  }
  private var rows: [Int] { Array(0..<max(model.live?.positions.count ?? 0, model.profile.count)) }
  var body: some View {
    HStack(spacing: 0) {
      sidebar.frame(width: 260)
      Divider()
      VStack(alignment: .leading, spacing: 18) {
        header
        HStack(spacing: 12) {
          info("DEVICE", model.deviceName)
          info("FIRMWARE", model.firmware)
          battery
        }
        HStack {
          Text("Calibration workbench").font(.title2.weight(.semibold))
          Spacer()
          if model.working { ProgressView().controlSize(.small) }
          Text(model.demo ? "SIMULATED" : model.authenticated ? "LIVE READBACK" : "OFFLINE")
            .font(.caption.weight(.semibold)).foregroundStyle(
              model.demo || model.authenticated ? lime : .secondary)
        }
        gearTable
        controls
        profileEditor
        HStack(spacing: 8) {
          Circle().fill(model.demo || model.authenticated ? lime : .gray).frame(width: 7, height: 7)
          Text(model.status).font(.callout).lineLimit(2).textSelection(.enabled)
          Spacer()
          Button(showLog ? "Hide log" : "Session log") { showLog.toggle() }.buttonStyle(.plain)
        }.padding(12).background(panel, in: RoundedRectangle(cornerRadius: 10))
        if showLog {
          ScrollView {
            Text(model.log.joined(separator: "\n")).font(.system(size: 11, design: .monospaced))
              .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
          }
          .frame(height: 110).padding(10).background(panel, in: RoundedRectangle(cornerRadius: 10))
        }
      }.padding(24).background(canvas)
    }
    .tint(lime)
    .onAppear { syncDraft() }
    .onChange(of: model.profile.positions) { _ in syncDraft() }
    .onChange(of: model.profile.count) { _ in syncDraft() }
    .onChange(of: model.profile.teeth) { _ in syncDraft() }
    .onReceive(NotificationCenter.default.publisher(for: Notification.Name("ExportXShifterDraft")))
    { _ in
      do {
        try captureDraftThrowing()
        model.exportProfile()
      } catch { model.report(error) }
    }
    .alert(item: $confirmation) { c in
      Alert(
        title: Text(c.title), message: Text(c.message),
        primaryButton: .default(Text("Continue"), action: c.action), secondaryButton: .cancel())
    }
  }
  private var sidebar: some View {
    VStack(alignment: .leading, spacing: 18) {
      HStack(spacing: 9) {
        Image(systemName: "point.3.connected.trianglepath.dotted").font(.title).foregroundStyle(
          lime)
        VStack(alignment: .leading, spacing: 3) {
          Text("XSHIFTER").font(.title3.weight(.bold))
          Text("OPEN / MAC").font(.caption).foregroundStyle(.secondary)
        }
      }.padding(.vertical, 8)
      Text("Your drivetrain.\nEvery position.").font(.title2.weight(.medium))
      Text("Connect").font(.headline)
      Button(model.scanning ? "Searching…" : "Find XShifter") { model.scan() }
        .disabled(model.scanning || model.working).frame(maxWidth: .infinity)
      if !model.devices.isEmpty {
        Picker("Device", selection: $selectedDevice) {
          Text("Select device").tag(nil as UUID?)
          ForEach(model.devices) { d in
            Text("\(d.peripheral.name ?? "XShifter") · \(d.rssi) dBm").tag(d.id as UUID?)
          }
        }.labelsHidden()
        Button("Connect selected") {
          if let d = model.devices.first(where: { $0.id == selectedDevice }) { model.connect(d) }
        }.disabled(selectedDevice == nil || model.working)
      }
      HStack {
        Button("Read device") { model.readState() }.disabled(!model.authenticated || model.working)
        Button("Disconnect") { model.disconnect() }.disabled(!model.canDisconnect)
      }
      Button("Read firmware version") { model.readVersion() }.disabled(
        !model.authenticated || model.working)
      Text(model.peer).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
      Divider()
      Toggle(
        "Bench mode",
        isOn: Binding(
          get: { model.bench },
          set: { value in
            if !value {
              model.bench = false
            } else {
              confirmation = Confirmation(
                title: "Enable calibration controls?",
                message:
                  "Place the bike on a stand with the drivetrain clear. Commands can move the derailleur. Calibration is not yet verified on your device.",
                action: { model.bench = true })
            }
          })
      ).disabled(!model.demo && !model.authenticated || model.working)
      Text(
        "Motor controls unlock only after connection and a complete device read. A backup is saved before calibration changes."
      )
      .font(.caption).foregroundStyle(.secondary)
      Divider()
      Button("Import Android profile…") { model.importProfile() }.disabled(model.working)
      Button("Export profile…") {
        do {
          try captureDraftThrowing()
          model.exportProfile()
        } catch { model.report(error) }
      }.disabled(model.working)
      Button("Show calibration backups") { model.showBackups() }
      Spacer(minLength: 10)
      Button(model.demo ? "Exit demo" : "Explore demo") {
        if model.demo { model.disconnect() } else { model.startDemo() }
      }.disabled(model.working)
      Text(
        "Close other XShifter apps before connecting. Profiles transfer by JSON; cable positions must be calibrated for your own bike."
      )
      .font(.caption).foregroundStyle(.secondary)
      Text(
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
          ?? "Development") + " · Test build"
      ).font(.caption2).foregroundStyle(.secondary)
    }.buttonStyle(.bordered).padding(20).background(panel)
  }
  private var header: some View {
    HStack(alignment: .firstTextBaseline) {
      VStack(alignment: .leading, spacing: 4) {
        Text("A better shift\nstarts here.").font(
          .system(size: 34, weight: .semibold, design: .rounded))
        Text("Desktop calibration for your XShifter").foregroundStyle(.secondary)
      }
      Spacer()
      VStack(alignment: .trailing, spacing: 5) {
        Text(
          model.live.flatMap {
            $0.positions.indices.contains($0.gear) ? String(format: "%02d", $0.gear + 1) : nil
          } ?? "—"
        )
        .font(.system(size: 52, weight: .semibold, design: .rounded)).monospacedDigit()
        .foregroundStyle(lime)
        Text(model.demo ? "SIMULATED GEAR" : "REPORTED GEAR").font(.caption2).foregroundStyle(
          .secondary)
      }
    }
  }
  private func info(_ title: String, _ value: String) -> some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(title).font(.caption2).foregroundStyle(.secondary)
      Text(value).font(.callout.weight(.medium)).lineLimit(2).textSelection(.enabled)
    }.frame(maxWidth: .infinity, minHeight: 52, alignment: .leading).padding(12).background(
      panel, in: RoundedRectangle(cornerRadius: 10))
  }
  private var battery: some View {
    TimelineView(.periodic(from: .now, by: 5)) { context in
      let fresh =
        model.demo
        || model.authenticated
          && (model.batteryDate.map { context.date.timeIntervalSince($0) <= 120 } ?? false)
      let pct = model.battery.map(Wire.percent)
      let color: Color =
        !fresh ? .secondary : pct.map { $0 <= 10 ? .red : $0 <= 20 ? .orange : lime } ?? .secondary
      HStack {
        Image(systemName: batterySymbol(pct)).font(.title2).foregroundStyle(color)
        VStack(alignment: .leading, spacing: 4) {
          Text("E-LINK BATTERY").font(.caption2).foregroundStyle(.secondary)
          Text(
            model.battery.map {
              String(
                format: "%.2f V · %@", Double($0) / 100,
                model.demo ? "demo" : fresh ? "estimated" : "last reading")
            } ?? "Waiting for device data"
          )
          .font(.caption).foregroundStyle(.secondary)
        }
        Spacer()
        Text(pct.map { "~\($0)%" } ?? "—").font(.title2.weight(.bold)).monospacedDigit()
          .foregroundStyle(color)
      }.frame(maxWidth: .infinity, minHeight: 52).padding(12).background(
        panel, in: RoundedRectangle(cornerRadius: 10))
    }
  }
  private var gearTable: some View {
    VStack(spacing: 0) {
      HStack {
        Text("GEAR").frame(width: 55, alignment: .leading)
        Text("SPROCKET").frame(width: 85, alignment: .leading)
        Text("DEVICE / MM").frame(maxWidth: .infinity, alignment: .trailing)
        Text("DRAFT / MM").frame(width: 110, alignment: .trailing)
        Text("DIFFERENCE").frame(width: 100, alignment: .trailing)
      }.font(.caption2.weight(.semibold)).foregroundStyle(.secondary).padding(.horizontal, 16)
        .padding(.vertical, 10)
      Divider()
      ScrollView {
        LazyVStack(spacing: 1) {
          ForEach(rows, id: \.self) { i in
            HStack {
              Text(String(format: "%02d", i + 1)).fontWeight(.semibold).frame(
                width: 55, alignment: .leading)
              Text(model.profile.teeth.indices.contains(i) ? "\(model.profile.teeth[i]) T" : "—")
                .foregroundStyle(.secondary).frame(width: 85, alignment: .leading)
              Text(
                model.live.flatMap { $0.positions.indices.contains(i) ? mm($0.positions[i]) : nil }
                  ?? "—"
              ).frame(maxWidth: .infinity, alignment: .trailing)
              TextField(
                "—",
                text: Binding(
                  get: { draftText.indices.contains(i) ? draftText[i] : "" },
                  set: { v in
                    while draftText.count <= i { draftText.append("") }
                    draftText[i] = v
                  })
              ).multilineTextAlignment(.trailing).textFieldStyle(.roundedBorder).frame(width: 110)
                .disabled(model.working || i >= model.profile.count)
              Text(difference(i)).foregroundStyle(.secondary).frame(
                width: 100, alignment: .trailing)
            }.font(.system(.body, design: .monospaced)).padding(.horizontal, 16).padding(
              .vertical, 7
            )
            .background(model.selectedGear == i ? lime.opacity(0.09) : Color.clear)
            .contentShape(Rectangle()).onTapGesture { model.selectedGear = i }
          }
        }
      }.frame(minHeight: 145)
    }.background(panel, in: RoundedRectangle(cornerRadius: 12))
  }
  private var controls: some View {
    HStack(spacing: 12) {
      Text("Gear \(model.selectedGear+1)").font(.headline)
      Button("Shift here") { model.shift() }.disabled(!model.canWrite)
      Divider().frame(height: 24)
      Picker("Trim", selection: $step) {
        Text("0.05 mm").tag(30)
        Text("0.10 mm").tag(60)
        Text("0.25 mm").tag(150)
        Text("1.00 mm").tag(600)
      }.frame(width: 160)
      Button("− Trim") { model.trim(-step) }.disabled(!model.canWrite)
      Button("+ Trim") { model.trim(step) }.disabled(!model.canWrite)
      Spacer()
      Button("Save device tuning…") {
        confirmation = Confirmation(
          title: "Commit current tuning?",
          message:
            "This writes the current gear configuration to the XShifter. The app will read it back; persistence after power cycling still needs checking.",
          action: { model.commit() })
      }.disabled(!model.canWrite)
    }.buttonStyle(.bordered)
  }
  private var profileEditor: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack {
        TextField("Profile name", text: $model.profile.name).font(.headline).textFieldStyle(
          .roundedBorder)
        Stepper("\(model.profile.count) gears", value: $model.profile.count, in: 2...17).fixedSize()
        Button("Use device table") { model.loadDeviceDraft() }.disabled(
          model.live?.positions.isEmpty != false || model.working)
      }
      HStack {
        Text("Teeth").foregroundStyle(.secondary)
        TextField("Optional: 11, 12, 13 …", text: $teethText).textFieldStyle(.roundedBorder)
        Toggle("Measured / calibrated", isOn: $model.profile.calibrated).fixedSize()
        Button("Apply draft to device…") {
          do {
            try captureDraftThrowing()
            try model.profile.validate(requireCalibrated: true)
            confirmation = Confirmation(
              title: "Replace the device gear table?",
              message:
                "A backup will be saved first. This writes \(model.profile.count) measured positions, commits them, and verifies readback. Keep the bike on a stand and the Mac awake.",
              action: { model.applyProfile() })
          } catch { model.report(error) }
        }.disabled(!model.canWrite)
      }
      Text(
        "Positions are cumulative cable travel. A cassette or derailleur name does not provide calibrated motor positions. Backups can be imported here for restoration."
      )
      .font(.caption).foregroundStyle(.secondary)
    }.padding(14).background(panel, in: RoundedRectangle(cornerRadius: 12)).disabled(model.working)
  }
  private func mm(_ n: Int) -> String { String(format: "%.3f", Double(n) / 600) }
  private func batterySymbol(_ percent: Int?) -> String {
    guard let p = percent else { return "battery.0percent" }
    return p <= 10
      ? "battery.0percent"
      : p <= 35
        ? "battery.25percent"
        : p <= 60 ? "battery.50percent" : p <= 85 ? "battery.75percent" : "battery.100percent"
  }
  private func difference(_ i: Int) -> String {
    guard draftText.indices.contains(i), let d = Double(draftText[i]), let table = model.live,
      table.positions.indices.contains(i)
    else { return "—" }
    return String(format: "%+.3f", d - Double(table.positions[i]) / 600)
  }
  private func syncDraft() {
    draftText = (0..<model.profile.count).map {
      model.profile.positions.indices.contains($0) ? mm(model.profile.positions[$0]) : ""
    }
    teethText = model.profile.teeth.map(String.init).joined(separator: ", ")
  }
  private func captureDraftThrowing() throws {
    var p = model.profile
    let texts = Array(draftText.prefix(p.count))
    if texts.allSatisfy({ $0.trimmingCharacters(in: .whitespaces).isEmpty }) {
      p.positions = []
    } else {
      p.positions = try texts.map {
        guard let value = Double($0), value.isFinite, value >= 0, value <= 42 else {
          throw ShiftError.invalid("Enter a cable position between 0 and 42 mm for every gear")
        }
        return Int((value * 600).rounded())
      }
    }
    p.teeth = try teethText.split(whereSeparator: { ",; \n\t".contains($0) }).map {
      guard let v = Int($0) else { throw ShiftError.invalid("Invalid sprocket tooth count") }
      return v
    }
    try p.validate()
    model.profile = p
    model.persistDraft()
  }
}
