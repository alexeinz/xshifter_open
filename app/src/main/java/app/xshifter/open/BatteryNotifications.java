package app.xshifter.open;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Locale;

/** Local notifications only. No network, push provider, alarms or exact-alarm permission. */
final class BatteryNotifications {
  static final int MONITOR_ID = 80, ALERT_ID = 81, TEST_ID = 82;
  private static final String MONITOR = "battery_monitor", ALERTS = "battery_alerts";
  private final Context context;
  private final NotificationManager manager;

  BatteryNotifications(Context c) {
    context = c;
    manager = c.getSystemService(NotificationManager.class);
    NotificationChannel monitor =
        new NotificationChannel(MONITOR, "Battery monitoring", NotificationManager.IMPORTANCE_LOW);
    monitor.setDescription("Connection and battery freshness while monitoring is enabled");
    monitor.setSound(null, null);
    NotificationChannel alerts =
        new NotificationChannel(ALERTS, "Low battery alerts", NotificationManager.IMPORTANCE_HIGH);
    alerts.setDescription("Low and critical estimated E-Link battery warnings");
    alerts.enableVibration(true);
    manager.createNotificationChannel(monitor);
    manager.createNotificationChannel(alerts);
  }

  boolean allowed() {
    return (Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
        && manager.areNotificationsEnabled()
        && manager.getNotificationChannel(ALERTS).getImportance()
            != NotificationManager.IMPORTANCE_NONE;
  }

  private PendingIntent open() {
    Intent i =
        new Intent(context, MainActivity.class)
            .putExtra("open_device", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(
        context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification.Builder builder(String channel, String title, String detail) {
    return new Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_battery_notification)
        .setContentTitle(title)
        .setContentText(detail)
        .setStyle(new Notification.BigTextStyle().bigText(detail))
        .setContentIntent(open())
        .setVisibility(Notification.VISIBILITY_PUBLIC);
  }

  Notification monitor(BatteryState.Reading reading, boolean connected, long now) {
    String title =
        reading == null
            ? "XShifter battery · waiting for data"
            : "XShifter battery · "
                + (reading.fresh(now) ? "" : "last ")
                + "~"
                + reading.percent()
                + "%";
    String detail =
        reading == null
            ? "Connected. Waiting for the E-Link to report battery voltage."
            : String.format(
                Locale.ROOT,
                "%.2f V · estimated from voltage · %s",
                reading.volts(),
                reading.fresh(now)
                    ? "receiving device data"
                    : "no recent battery data; alerts cannot update");
    if (!connected) detail = "Disconnected · battery monitoring paused";
    PendingIntent stop =
        PendingIntent.getService(
            context,
            1,
            new Intent(context, ConnectionService.class).setAction(ConnectionService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return builder(MONITOR, title, detail)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "Stop monitoring", stop).build())
        .build();
  }

  boolean low(BatteryState.Reading reading, int level) {
    if (!allowed()) return false;
    String title =
        level == BatteryState.CRITICAL ? "XShifter battery critically low" : "XShifter battery low";
    String text =
        String.format(
            Locale.ROOT,
            "E-Link: ~%d%% (%.2f V, estimated). Charge the battery before continuing your ride.",
            reading.percent(),
            reading.volts());
    try {
      manager.notify(
          ALERT_ID,
          builder(ALERTS, title, text)
              .setAutoCancel(true)
              .setCategory(Notification.CATEGORY_STATUS)
              .build());
      return true;
    } catch (SecurityException e) {
      return false;
    }
  }

  void lost() {
    if (!allowed()) return;
    try {
      manager.notify(
          ALERT_ID,
          builder(
                  ALERTS,
                  "XShifter disconnected",
                  "Battery monitoring has stopped. Reconnect in the app to resume alerts.")
              .setAutoCancel(true)
              .build());
    } catch (SecurityException ignored) {
      // Notification permission can be revoked between the check and delivery.
    }
  }

  void clearAlert() {
    manager.cancel(ALERT_ID);
  }

  boolean fastDrain(BatteryTrend.Result trend) {
    if (!allowed()) return false;
    try {
      manager.notify(83, builder(ALERTS, "Check XShifter battery drain",
          String.format(Locale.ROOT,
              "Voltage fell %.2f V over %.0f minutes. Possible fast drain or load sag; this is not a battery-health diagnosis.",
              trend.dropVolts(), trend.minutes())).setAutoCancel(true).build());
      return true;
    } catch (SecurityException ignored) { return false; }
  }

  void test() {
    if (!allowed())
      throw new IllegalStateException("Allow battery notifications in Android settings first");
    manager.notify(
        TEST_ID,
        builder(
                ALERTS,
                "Test battery notification",
                "Notification test only. This is not a live XShifter battery warning.")
            .setAutoCancel(true)
            .build());
  }
}
