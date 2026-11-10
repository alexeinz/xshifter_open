package app.xshifter.open;

/** Battery telemetry and alert decisions, independent of Android and the motor controller. */
public final class BatteryState {
  private BatteryState() {}

  public static final long FRESH_MS = 120_000;
  // Interpolate the original build 1105 battery ICON thresholds. The unused original
  // percentage helper is linear and disagrees with its icons (e.g. 11.16 V is a 20% icon).
  // This is a UI estimate, not a calibrated capacity or remaining-runtime measurement.
  private static final int[] VOLTAGE = {1020, 1116, 1140, 1152, 1173, 1209, 1230, 1240};
  private static final int[] PERCENT = {0, 20, 30, 50, 60, 80, 90, 100};

  public record Reading(int centivolts, long elapsedMs, long wallMs) {
    public Reading {
      if (centivolts < 600 || centivolts > 1500)
        throw new IllegalArgumentException("Invalid battery voltage");
    }

    public int percent() {
      if (centivolts <= VOLTAGE[0]) return 0;
      for (int i = 1; i < VOLTAGE.length; i++)
        if (centivolts <= VOLTAGE[i])
          return PERCENT[i - 1]
              + Math.round(
                  (centivolts - VOLTAGE[i - 1])
                      * (PERCENT[i] - PERCENT[i - 1])
                      / (float) (VOLTAGE[i] - VOLTAGE[i - 1]));
      return 100;
    }

    public boolean fresh(long now) {
      return now >= elapsedMs && now - elapsedMs <= FRESH_MS;
    }

    public double volts() {
      return centivolts / 100.0;
    }
  }

  /** Build 1105 t$d.b / onReceive: 01 01 01 followed by uint16 little-endian centivolts. */
  public static Reading decode(byte[] packet, long elapsed, long wall) {
    if (packet == null || packet.length < 5 || packet[0] != 1 || packet[1] != 1 || packet[2] != 1)
      return null;
    int voltage = Protocol.le(packet, 3);
    if (voltage < 600 || voltage > 1500) return null;
    return new Reading(voltage, elapsed, wall);
  }

  public static final int NONE = 0, LOW = 1, CRITICAL = 2;

  public record Decision(int mask, int alert) {}

  public static Decision evaluate(
      Reading reading, long now, boolean connected, int lowThreshold, int mask) {
    if (reading == null || !connected || !reading.fresh(now)) return new Decision(mask, NONE);
    return evaluate(reading.percent(), lowThreshold, mask);
  }

  /** Mask is persisted per E-Link. Recovery hysteresis avoids alerts on voltage bounce. */
  public static Decision evaluate(int percent, int lowThreshold, int mask) {
    if (percent < 0 || percent > 100 || lowThreshold < 15 || lowThreshold > 40)
      throw new IllegalArgumentException("Invalid battery threshold");
    mask &= 3;
    if (percent >= lowThreshold + 5) mask &= ~LOW;
    if (percent >= 15) mask &= ~CRITICAL;
    if (percent <= 10 && (mask & CRITICAL) == 0)
      return new Decision(mask | LOW | CRITICAL, CRITICAL);
    if (percent <= lowThreshold && (mask & LOW) == 0) return new Decision(mask | LOW, LOW);
    return new Decision(mask, NONE);
  }
}
