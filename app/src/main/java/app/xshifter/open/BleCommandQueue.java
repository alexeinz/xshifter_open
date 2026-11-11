package app.xshifter.open;

import java.util.ArrayDeque;

/** Single in-flight ATT write, including the type of that write during UI transitions. */
final class BleCommandQueue {
  private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
  private byte[] inFlight;
  private boolean readOnly;

  void add(byte[] bytes) {
    if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty command");
    if (readOnly && !Protocol.isReadOnlyCommand(bytes))
      throw new IllegalStateException(
          "Device changes are disabled while the app is in the background");
    if (pending.size() >= 32) throw new IllegalStateException("Wait for pending operations");
    pending.add(bytes.clone());
  }

  byte[] begin() {
    if (inFlight != null || pending.isEmpty()) return null;
    inFlight = pending.remove();
    return inFlight.clone();
  }

  boolean complete() {
    if (inFlight == null) return false;
    inFlight = null;
    return true;
  }

  boolean idle() {
    return inFlight == null && pending.isEmpty();
  }

  boolean onlyReads() {
    if (inFlight != null && !Protocol.isReadOnlyCommand(inFlight)) return false;
    for (byte[] bytes : pending) if (!Protocol.isReadOnlyCommand(bytes)) return false;
    return true;
  }

  void setReadOnly(boolean value) {
    readOnly = value;
    if (value) pending.removeIf(bytes -> !Protocol.isReadOnlyCommand(bytes));
  }

  void clear() {
    pending.clear();
    inFlight = null;
  }
}
