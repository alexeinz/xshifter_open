package app.xshifter.open;

/** Retry only the app-identification handshake; never retry calibration or motor writes. */
final class LoginHandshake {
  enum Action {
    WAIT,
    SEND,
    FAIL
  }

  private long started, next;
  private int attempts;
  private boolean active, authenticated;

  void start(long now) {
    started = next = now;
    attempts = 0;
    authenticated = false;
    active = true;
  }

  Action next(long now, boolean idle) {
    if (!active || authenticated) return Action.WAIT;
    if (now < started || now - started >= 12_000) return Action.FAIL;
    if (now < next) return Action.WAIT;
    if (attempts >= 3) return Action.WAIT;
    if (!idle) return Action.WAIT;
    attempts++;
    next = now + 3000;
    return Action.SEND;
  }

  boolean accept(byte[] packet) {
    if (!active || authenticated || !isReply(packet)) return false;
    authenticated = true;
    return true;
  }

  static boolean isReply(byte[] packet) {
    return packet != null
        && packet.length == 18
        && Protocol.u(packet[0]) == 0xdf
        && Protocol.u(packet[1]) == 0x89;
  }

  void reset() {
    active = authenticated = false;
    attempts = 0;
  }

  int attempts() {
    return attempts;
  }
}
