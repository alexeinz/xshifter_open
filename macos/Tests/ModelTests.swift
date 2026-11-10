import Foundation

@main
struct ModelTests {
  @MainActor static func main() {
    let m = DeviceModel()
    precondition(!m.canWrite, "Offline writes must remain locked")
    m.shift()
    precondition(!m.authenticated && m.live == nil)
    m.startDemo()
    precondition(m.demo && m.canWrite && !m.authenticated)
    precondition(m.live?.positions.count == 11 && m.live?.gear == 3)
    m.selectedGear = 5
    m.shift()
    precondition(m.live?.gear == 5)
    let before = m.live!.positions[5]
    m.trim(30)
    precondition(m.live!.positions[5] == before + 30)
    precondition(m.live!.positions[4] == 6000, "Trim must not change another gear")
    m.selectedGear = 0
    let unchanged = m.live!
    m.trim(30)
    precondition(m.live == unchanged, "Trimming a different gear must be blocked")
    m.shift()
    m.trim(-30)
    precondition(m.live!.positions[0] == 0, "Negative travel must be blocked")
    m.profile.positions = [0, 1200, 2400]
    m.profile.count = 3
    m.profile.calibrated = false
    m.applyProfile()
    precondition(m.live!.positions.count == 11, "Uncalibrated profiles must not apply")
    m.profile.calibrated = true
    m.applyProfile()
    precondition(m.live!.positions == [0, 1200, 2400])
    m.commit()
    precondition(m.status.contains("Demo"))
    m.disconnect()
    precondition(!m.canWrite && !m.bench && m.live == nil && !m.demo)
    print("PASS: 12 demo/control workflow checks · no Bluetooth access")
  }
}
