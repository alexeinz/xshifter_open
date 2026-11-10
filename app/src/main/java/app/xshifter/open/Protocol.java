package app.xshifter.open;

import java.util.*;

/** Wire formats recovered from XShifter Android build 1105. No Android dependencies. */
public final class Protocol {
  public static boolean isReadOnlyCommand(byte[] data) {
    return Arrays.equals(data, requestCogs())
        || Arrays.equals(data, version())
        || Arrays.equals(data, peer());
  }

  private Protocol() {}

  public static final int MAX_POSITION = 25200;
  public static final int UNITS_PER_MM = 600;
  public static final UUID SERVICE = uuid("1523"),
      WRITE = uuid("1524"),
      NOTIFY = uuid("1525"),
      CCCD = uuid("2902");

  private static UUID uuid(String s) {
    return UUID.fromString("0000" + s + "-0000-1000-8000-00805f9b34fb");
  }

  public static byte[] login(byte[] identity) {
    if (identity.length != 8)
      throw new IllegalArgumentException("Phone identity must contain 8 bytes");
    byte[] b = new byte[17];
    b[0] = (byte) 0xdf;
    b[1] = (byte) 0x89;
    System.arraycopy(identity, 0, b, 2, 8);
    return b;
  }

  public static byte[] requestCogs() {
    return new byte[] {10, 0, 0, 0, 0, 0, 0, 0};
  }

  public static byte[] version() {
    return new byte[] {(byte) 0xac, (byte) 0xc1, 5};
  }

  /** Build 1105 t$d.c/k: legacy 0x37 reply maps to version 27; modern reply is ASCII. */
  public static String firmwareVersion(byte[] packet) {
    if (packet == null) return null;
    if (packet.length > 1 && u(packet[0]) == 0x37) return "27 (legacy reply)";
    if (packet.length <= 3 || u(packet[0]) != 0xac || u(packet[1]) != 0xc1
        || packet[2] != 5) return null;
    StringBuilder value = new StringBuilder();
    boolean digit = false, ended = false;
    for (int i = 3; i < packet.length; i++) {
      int c = u(packet[i]);
      if (c == 0) { ended = true; continue; }
      if (ended || c < 32 || c > 126) return null;
      digit |= c >= '0' && c <= '9';
      value.append((char)c);
    }
    return digit ? value.toString().trim() : null;
  }

  public static byte[] peer() {
    return new byte[] {(byte) 0xac, (byte) 0xc1, 6};
  }

  public static byte[] commit() {
    return new byte[] {25, 1, (byte) 0xaa, 0, 0, 0, 0, 0};
  }

  public static byte[] clear() {
    return new byte[] {(byte) 0xac, (byte) 0xc1, 3, 0};
  }

  public static byte[] shift(int index, int count) {
    if (index < 0 || index >= count || count < 2 || count > 18)
      throw new IllegalArgumentException("Invalid gear index");
    return new byte[] {30, (byte) index, 0, 0, 0, 0, 0, 0};
  }

  public static byte[] move(int index, int delta, boolean calibration) {
    if (index < 0 || index >= 18 || delta == 0 || Math.abs((long) delta) > MAX_POSITION)
      throw new IllegalArgumentException("Invalid cable movement");
    int n = Math.abs(delta);
    return new byte[] {
      30,
      (byte) index,
      (byte) (delta < 0 ? 1 : 0),
      (byte) n,
      (byte) (n >> 8),
      0,
      (byte) (calibration ? 7 : 0),
      1
    };
  }

  public static byte[] cable(int delta) {
    if (delta == 0 || Math.abs((long) delta) > MAX_POSITION)
      throw new IllegalArgumentException("Invalid cable adjustment");
    int n = Math.abs(delta);
    return new byte[] {30, 101, (byte) (delta < 0 ? 0 : 1), (byte) n, (byte) (n >> 8), 0, 0, 1};
  }

  public static void validatePositions(int[] positions) {
    if (positions.length < 2 || positions.length > 18)
      throw new IllegalArgumentException("Use 2–18 gear positions");
    for (int i = 0; i < positions.length; i++) {
      if (positions[i] < 0 || positions[i] > MAX_POSITION)
        throw new IllegalArgumentException("Position outside 0–42 mm travel");
      if (i > 0 && positions[i] <= positions[i - 1])
        throw new IllegalArgumentException("Gear positions must increase strictly");
    }
  }

  public static int[] linear(int count, int start, int end) {
    if (count < 2 || count > 18 || end - start < count - 1)
      throw new IllegalArgumentException("Invalid calibration endpoints");
    int[] p = new int[count];
    for (int i = 0; i < count; i++)
      p[i] = start + (int) Math.round((end - (long) start) * i / (double) (count - 1));
    validatePositions(p);
    return p;
  }

  /** Device tables are increments; positions shown in the UI are cumulative. */
  public static List<byte[]> upload(int[] p, int adjustment) {
    validatePositions(p);
    if (adjustment < 0 || adjustment > MAX_POSITION)
      throw new IllegalArgumentException("Invalid adjustment");
    if (p.length < 3 || p.length > 17)
      throw new IllegalArgumentException("Live table transfer supports 3–17 gears");
    List<byte[]> result = new ArrayList<>();
    for (int i = 0; i < p.length; i++) {
      int increment = i == 0 ? p[i] : p[i] - p[i - 1];
      byte[] b = {
        25,
        (byte) (i == 1 ? 0 : i == p.length - 1 ? 0xcc : 10),
        (byte) (i == 1 ? 0 : i),
        (byte) increment,
        (byte) (increment >> 8),
        0,
        0,
        0
      };
      if (i == 1) {
        b[5] = (byte) adjustment;
        b[6] = (byte) (adjustment >> 8);
      }
      result.add(b);
    }
    return result;
  }

  public static int u(byte b) {
    return b & 255;
  }

  public static int le(byte[] b, int offset) {
    if (offset < 0 || offset + 1 >= b.length)
      throw new IllegalArgumentException("Truncated packet");
    return u(b[offset]) + (u(b[offset + 1]) << 8);
  }

  public static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format(Locale.ROOT, "%02X", u(v)));
    return s.toString();
  }

  public static byte[] unhex(String s) {
    s = s.replaceAll("\\s+", "");
    if (s.length() % 2 != 0 || !s.matches("[0-9a-fA-F]*"))
      throw new IllegalArgumentException("Invalid hexadecimal data");
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++)
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return out;
  }

  /** Reset for each read request; no state is shared between different reads/devices. */
  public static final class TableReader {
    private final byte[][] parts = new byte[3][];
    public int adjustment, gear;

    public int[] accept(byte[] b) {
      if (b.length < 2 || u(b[0]) != 10) return null;
      int kind = u(b[1]), slot = kind == 0xc0 ? 0 : kind == 0xc1 ? 1 : kind == 0xcc ? 2 : -1;
      if (slot < 0) return null;
      if ((slot < 2 && (b.length < 2 || b.length > 20 || b.length % 2 != 0))
          || (slot == 2 && b.length < 5))
        throw new IllegalArgumentException("Truncated gear table");
      parts[slot] = b.clone();
      if (parts[0] == null || parts[1] == null || parts[2] == null) return null;
      List<Integer> increments = new ArrayList<>();
      for (int n = 0; n < 2; n++)
        for (int i = 2; i + 1 < parts[n].length; i += 2) increments.add(le(parts[n], i));
      while (!increments.isEmpty() && increments.get(increments.size() - 1) == 0)
        increments.remove(increments.size() - 1);
      adjustment = le(parts[2], 2);
      gear = u(parts[2][4]);
      if (increments.isEmpty()) return new int[0];
      int[] p = new int[increments.size()];
      int total = 0;
      for (int i = 0; i < p.length; i++) {
        total += increments.get(i);
        p[i] = total;
      }
      validatePositions(p);
      return p;
    }
  }
}
