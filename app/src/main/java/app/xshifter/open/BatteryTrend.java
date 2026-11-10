package app.xshifter.open;

import java.util.*;

/** Session-only voltage trend. This is a heuristic, not a capacity or health measurement. */
public final class BatteryTrend {
  private static final long MINUTE = 60_000;
  private final ArrayList<Bucket> buckets = new ArrayList<>();
  private long last = -1;
  private static final class Bucket {
    final long time;
    long sum;
    int count;
    Bucket(long time, int voltage) { this.time = time; sum = voltage; count = 1; }
    double mean() { return sum / (double) count; }
  }
  public record Result(boolean ready, boolean fastDrop, double dropVolts, double minutes) {}
  public void clear() { buckets.clear(); last = -1; }
  public void add(BatteryState.Reading reading) {
    long now = reading.elapsedMs();
    if (last >= 0 && now <= last) return;
    if (last >= 0 && now - last > BatteryState.FRESH_MS) clear();
    last = now;
    buckets.removeIf(b -> now - b.time > 16 * MINUTE);
    long slot = now / MINUTE;
    if (!buckets.isEmpty() && buckets.get(buckets.size()-1).time / MINUTE == slot) {
      Bucket b = buckets.get(buckets.size()-1); b.sum += reading.centivolts(); b.count++;
    } else buckets.add(new Bucket(now, reading.centivolts()));
  }
  public Result result(long now) {
    if (last < 0 || now < last || now-last > BatteryState.FRESH_MS || buckets.size() < 11)
      return new Result(false, false, 0, 0);
    int n = buckets.size();
    double minutes = (buckets.get(n-1).time - buckets.get(0).time) / (double) MINUTE;
    if (minutes < 10) return new Result(false, false, 0, minutes);
    // Medians at each end reject a single load sag or noisy sample.
    double first = median(buckets.get(0).mean(), buckets.get(1).mean(), buckets.get(2).mean());
    double end = median(buckets.get(n-3).mean(), buckets.get(n-2).mean(), buckets.get(n-1).mean());
    double drop = (first-end)/100.0;
    return new Result(true, drop >= .20, drop, minutes);
  }
  private static double median(double a, double b, double c) {
    return a+b+c-Math.min(a,Math.min(b,c))-Math.max(a,Math.max(b,c));
  }
}
