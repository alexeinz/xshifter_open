package app.xshifter.open;

import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.security.SecureRandom;

/** Owns the BLE lifetime. Background operation is opt-in, passive and read-only. */
@SuppressLint("MissingPermission")
public final class ConnectionService extends Service implements BleLink.Listener {
  static final String START = "app.xshifter.open.START_MONITOR",
      STOP = "app.xshifter.open.STOP_MONITOR";

  public interface UiListener extends BleLink.Listener {
    void batteryChanged();

    void deviceInfoChanged();
  }

  public final class LocalBinder extends Binder {
    ConnectionService service() {
      return ConnectionService.this;
    }
  }

  private final LocalBinder binder = new LocalBinder();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final LoginHandshake login = new LoginHandshake();
  private BleLink transport;
  private UiListener ui;
  private BatteryNotifications notifications;
  private SharedPreferences prefs;
  private BatteryState.Reading battery;
  private final BatteryTrend trend = new BatteryTrend();
  private boolean drainAlertSent;
  private String firmware = "Not read";
  private int firmwareAttempts;
  private boolean firmwarePending;
  private final Runnable firmwareRetry = this::retryFirmware;

  String firmware() {
    return firmware;
  }

  BatteryTrend.Result batteryTrend() {
    return trend.result(SystemClock.elapsedRealtime());
  }

  void requestFirmware() {
    if (!authenticated || !transport.isReady())
      throw new IllegalStateException("Connect to your XShifter first");
    if (firmwarePending) return;
    firmwarePending = true;
    firmwareAttempts = 0;
    firmware = "Reading…";
    retryFirmware();
  }

  private void retryFirmware() {
    if (!firmwarePending || !authenticated) return;
    if (firmwareAttempts >= 3) {
      firmwarePending = false;
      firmware = "No supported reply · retry or inspect device log";
      if (ui != null) ui.deviceInfoChanged();
      return;
    }
    try {
      transport.send(Protocol.version());
      firmwareAttempts++;
      handler.postDelayed(firmwareRetry, 2000);
    } catch (RuntimeException e) {
      firmwarePending = false;
      firmware = "Read failed: " + e.getMessage();
    }
    if (ui != null) ui.deviceInfoChanged();
  }

  private String address = "", status = "Offline · profiles and demo available", lastSummary = "";
  private byte[] identity;
  private boolean authenticated, monitoring;
  private final Runnable loginTimeout = this::tryLogin;
  private final Runnable backgroundTimeout =
      () -> {
        if (ui == null && !monitoring) {
          transport.close();
          status(
              "Disconnected after 30 seconds away · enable Battery notifications for longer"
                  + " monitoring");
        }
      };

  private void tryLogin() {
    if (authenticated || !transport.isReady()) return;
    LoginHandshake.Action action = login.next(SystemClock.elapsedRealtime(), transport.isIdle());
    if (action == LoginHandshake.Action.FAIL) {
      int attempts = login.attempts();
      transport.close();
      status(
          "No login reply after "
              + attempts
              + " attempts. Wake the E-Link, close other XShifter apps and reconnect.");
      return;
    }
    if (action == LoginHandshake.Action.SEND) {
      try {
        transport.send(Protocol.login(identity));
        status("Connected · identification attempt " + login.attempts() + " of 3");
      } catch (RuntimeException e) {
        transport.close();
        status("Identification failed: " + e.getMessage());
        return;
      }
    }
    handler.postDelayed(loginTimeout, 250);
  }

  void leaveForeground(boolean unsafeTransaction) {
    ConnectionPolicy.Background decision =
        ConnectionPolicy.background(
            authenticated, unsafeTransaction, transport.hasOnlyReadOperations(), monitoring);
    handler.removeCallbacks(backgroundTimeout);
    if (decision == ConnectionPolicy.Background.DISCONNECT) disconnect();
    else if (decision == ConnectionPolicy.Background.SHORT_GRACE)
      handler.postDelayed(backgroundTimeout, 30_000);
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (monitoring) {
            updateMonitor();
            if (ui != null) ui.batteryChanged();
            handler.postDelayed(this, 5000);
          }
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    prefs = getSharedPreferences("xshifter", MODE_PRIVATE);
    notifications = new BatteryNotifications(this);
    transport = new BleLink(getApplicationContext(), this);
  }

  @Override
  public IBinder onBind(Intent i) {
    return binder;
  }

  BleLink transport() {
    return transport;
  }

  String address() {
    return address;
  }

  byte[] identity() {
    return identity == null ? null : identity.clone();
  }

  boolean authenticated() {
    return authenticated;
  }

  boolean monitoring() {
    return monitoring;
  }

  BatteryState.Reading battery() {
    return battery;
  }

  void attach(UiListener listener) {
    handler.removeCallbacks(backgroundTimeout);
    ui = listener;
    transport.setReadOnly(false);
    ui.status(status);
    ui.batteryChanged();
    ui.deviceInfoChanged();
  }

  void detach(UiListener listener) {
    if (ui == listener) {
      ui = null;
      transport.setReadOnly(true);
    }
  }

  void connect(BluetoothDevice device) {
    stopMonitoring();
    // Close the old device before replacing its identity or sample.
    transport.close();
    address = device.getAddress();
    battery = null;
    trend.clear();
    drainAlertSent = false;
    firmware = "Not read";
    String key = "identity:" + address, saved = prefs.getString(key, null);
    if (saved == null) {
      identity = new byte[8];
      new SecureRandom().nextBytes(identity);
      prefs.edit().putString(key, Protocol.hex(identity)).apply();
    } else identity = Protocol.unhex(saved);
    transport.setReadOnly(false);
    transport.connect(device);
  }

  void disconnect() {
    stopMonitoring();
    transport.close();
  }

  void enableMonitoring() {
    if (!authenticated || !transport.isReady())
      throw new IllegalStateException("Connect to your XShifter first");
    if (!notifications.allowed())
      throw new IllegalStateException("Allow battery notifications first");
    monitoring = true;
    try {
      startForegroundService(new Intent(this, ConnectionService.class).setAction(START));
    } catch (RuntimeException e) {
      monitoring = false;
      throw e;
    }
  }

  void stopMonitoring() {
    monitoring = false;
    handler.removeCallbacks(tick);
    lastSummary = "";
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    if (ui != null) ui.batteryChanged();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    if (i == null || STOP.equals(i.getAction())) {
      disconnect();
      return START_NOT_STICKY;
    }
    if (!START.equals(i.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      Notification n = notifications.monitor(battery, authenticated, SystemClock.elapsedRealtime());
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(
            BatteryNotifications.MONITOR_ID,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      else startForeground(BatteryNotifications.MONITOR_ID, n);
      if (!authenticated || !monitoring) {
        stopMonitoring();
        return START_NOT_STICKY;
      }
      handler.removeCallbacks(tick);
      handler.post(tick);
      if (ui != null) ui.batteryChanged();
    } catch (RuntimeException e) {
      stopMonitoring();
      status("Battery monitoring could not start: " + e.getMessage());
    }
    return START_NOT_STICKY;
  }

  private void updateMonitor() {
    if (!monitoring) return;
    long now = SystemClock.elapsedRealtime();
    String summary =
        battery == null
            ? "waiting"
            : battery.percent() + ":" + battery.centivolts() + ":" + battery.fresh(now);
    if (!summary.equals(lastSummary)) {
      try {
        getSystemService(NotificationManager.class)
            .notify(
                BatteryNotifications.MONITOR_ID,
                notifications.monitor(battery, authenticated, now));
        lastSummary = summary;
      } catch (SecurityException ignored) {
      }
    }
  }

  @Override
  public void status(String text) {
    status = text;
    if (ui != null) ui.status(text);
  }

  @Override
  public void found(BluetoothDevice device, int rssi) {
    if (ui != null) ui.found(device, rssi);
  }

  @Override
  public void ready() {
    if (identity == null) {
      transport.close();
      return;
    }
    handler.removeCallbacks(loginTimeout);
    login.start(SystemClock.elapsedRealtime());
    tryLogin();
    if (ui != null) ui.ready();
  }

  @Override
  public void packet(byte[] data) {
    if (LoginHandshake.isReply(data)) {
      if (!login.accept(data)) return;
      authenticated = true;
      handler.removeCallbacks(loginTimeout);
      status("XShifter connected");
      requestFirmware();
    }
    String version = Protocol.firmwareVersion(data);
    if (authenticated && version != null) {
      firmware = version;
      firmwarePending = false;
      handler.removeCallbacks(firmwareRetry);
      if (ui != null) ui.deviceInfoChanged();
    }
    BatteryState.Reading sample =
        BatteryState.decode(data, SystemClock.elapsedRealtime(), System.currentTimeMillis());
    if (sample != null) {
      battery = sample;
      if (authenticated) trend.add(sample);
      if (authenticated && monitoring && !drainAlertSent && batteryTrend().fastDrop())
        drainAlertSent = notifications.fastDrain(batteryTrend());
      if (monitoring && authenticated) {
        String key = "battery_alert_mask:" + address;
        int previousMask = prefs.getInt(key, 0);
        BatteryState.Decision decision =
            BatteryState.evaluate(
                sample,
                SystemClock.elapsedRealtime(),
                authenticated,
                Math.max(15, Math.min(40, prefs.getInt("battery_low_threshold", 20))),
                previousMask);
        if ((decision.alert() == BatteryState.NONE || notifications.low(sample, decision.alert()))
            && decision.mask() != previousMask) prefs.edit().putInt(key, decision.mask()).apply();
        if (decision.mask() == 0 && previousMask != 0) notifications.clearAlert();
      }
      updateMonitor();
      if (ui != null) ui.batteryChanged();
    }
    if (ui != null) ui.packet(data);
  }

  @Override
  public void disconnected() {
    boolean lost = monitoring && authenticated;
    authenticated = false;
    login.reset();
    handler.removeCallbacks(backgroundTimeout);
    firmwarePending = false;
    handler.removeCallbacks(firmwareRetry);
    trend.clear();
    handler.removeCallbacks(loginTimeout);
    if (lost) {
      notifications.lost();
      stopMonitoring();
    }
    status = "Disconnected";
    if (ui != null) {
      ui.disconnected();
      ui.status(status);
      ui.batteryChanged();
    }
  }

  @Override
  public void onDestroy() {
    ui = null;
    handler.removeCallbacksAndMessages(null);
    transport.close();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
