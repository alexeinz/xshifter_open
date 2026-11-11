package app.xshifter.open;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.util.*;

/** One GATT operation at a time. A timeout aborts rather than replaying motor writes. */
@SuppressLint("MissingPermission")
public final class BleLink {
  public interface Listener {
    void status(String text);

    void found(BluetoothDevice device, int rssi);

    void ready();

    void packet(byte[] data);

    void disconnected();
  }

  private final Context context;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic write;
  private BluetoothLeScanner scanner;
  private boolean ready;
  private final BleCommandQueue queue = new BleCommandQueue();
  private long nextWriteAt;
  private String phase = "connection";
  private ScanCallback activeScan;
  private final Runnable pumpLater = this::pump;
  private final Runnable timeout = () -> fail("Bluetooth " + phase + " timed out; disconnected");
  private final Runnable scanEnd = this::stopScan;

  public BleLink(Context c, Listener l) {
    context = c;
    listener = l;
  }

  private BluetoothAdapter adapter() {
    BluetoothManager m = context.getSystemService(BluetoothManager.class);
    return m == null ? null : m.getAdapter();
  }

  private void post(BluetoothGatt session, Runnable action) {
    handler.post(
        () -> {
          if (session != gatt) return;
          try {
            action.run();
          } catch (RuntimeException e) {
            fail("Bluetooth " + phase + " failed: " + e.getMessage());
          }
        });
  }

  public void scan() {
    stopScan();
    BluetoothAdapter a = adapter();
    if (a == null || !a.isEnabled()) {
      listener.status("Turn Bluetooth on to find your XShifter");
      return;
    }
    scanner = a.getBluetoothLeScanner();
    if (scanner == null) {
      listener.status("Bluetooth scanner unavailable");
      return;
    }
    activeScan =
        new ScanCallback() {
          @Override
          public void onScanResult(int type, ScanResult result) {
            handler.post(
                () -> {
                  if (activeScan != this) return;
                  ScanRecord record = result.getScanRecord();
                  boolean service =
                      record != null
                          && record.getServiceUuids() != null
                          && record.getServiceUuids().contains(new ParcelUuid(Protocol.SERVICE));
                  try {
                    String name = record == null ? null : record.getDeviceName();
                    if (name == null) name = result.getDevice().getName();
                    if (ConnectionPolicy.matchesAdvertisement(service, name))
                      listener.found(result.getDevice(), result.getRssi());
                  } catch (SecurityException e) {
                    stopScan();
                    listener.status(
                        "Bluetooth permission was revoked · allow Nearby devices and retry");
                  }
                });
          }

          @Override
          public void onScanFailed(int code) {
            handler.post(
                () -> {
                  if (activeScan != this) return;
                  stopScan();
                  listener.status("Bluetooth scan failed: " + code);
                });
          }
        };
    try {
      scanner.startScan(
          null,
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          activeScan);
    } catch (RuntimeException e) {
      stopScan();
      listener.status("Could not start Bluetooth scan: " + e.getMessage());
      return;
    }
    listener.status("Searching for XShifter · 12 seconds");
    handler.postDelayed(scanEnd, 12000);
  }

  public void stopScan() {
    handler.removeCallbacks(scanEnd);
    ScanCallback oldScan = activeScan;
    activeScan = null;
    if (scanner != null && oldScan != null) {
      try {
        scanner.stopScan(oldScan);
      } catch (RuntimeException ignored) {
      }
      scanner = null;
    }
  }

  public void connect(BluetoothDevice device) {
    close();
    phase = "connection";
    listener.status("Connecting to " + device.getAddress());
    try {
      gatt =
          device.connectGatt(
              context,
              false,
              callback,
              BluetoothDevice.TRANSPORT_LE,
              BluetoothDevice.PHY_LE_1M_MASK,
              handler);
      if (gatt == null) {
        fail("Bluetooth could not create a connection");
        return;
      }
      handler.postDelayed(timeout, 15000);
    } catch (RuntimeException e) {
      fail("Could not connect: " + e.getMessage());
    }
  }

  public boolean isReady() {
    return ready;
  }

  public boolean isIdle() {
    return ready && queue.idle();
  }

  public boolean hasOnlyReadOperations() {
    return ready && queue.onlyReads();
  }

  public void send(byte[] data) {
    if (!ready) throw new IllegalStateException("Connect to your XShifter first");
    queue.add(data);
    pump();
    if (!ready)
      throw new IllegalStateException("Bluetooth disconnected while sending; see session log");
  }

  public void setReadOnly(boolean value) {
    queue.setReadOnly(value);
  }

  private void pump() {
    if (!ready) return;
    long wait = nextWriteAt - SystemClock.elapsedRealtime();
    if (wait > 0) {
      handler.removeCallbacks(pumpLater);
      handler.postDelayed(pumpLater, wait);
      return;
    }
    byte[] bytes = queue.begin();
    if (bytes == null) return;
    phase = "command " + Protocol.hex(bytes).substring(0, Math.min(6, bytes.length * 2));
    boolean ok;
    try {
      if (Build.VERSION.SDK_INT >= 33)
        ok =
            gatt.writeCharacteristic(write, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                == BluetoothStatusCodes.SUCCESS;
      else {
        write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        write.setValue(bytes);
        ok = gatt.writeCharacteristic(write);
      }
    } catch (RuntimeException e) {
      fail("Bluetooth command could not start: " + e.getMessage());
      return;
    }
    if (!ok) {
      fail("Bluetooth rejected the command");
      return;
    }
    handler.postDelayed(timeout, 8000);
  }

  private void fail(String text) {
    close();
    listener.status(text);
  }

  public void close() {
    stopScan();
    handler.removeCallbacks(timeout);
    handler.removeCallbacks(pumpLater);
    queue.clear();
    nextWriteAt = 0;
    ready = false;
    write = null;
    BluetoothGatt old = gatt;
    gatt = null;
    if (old != null) {
      try {
        old.disconnect();
      } catch (RuntimeException ignored) {
      }
      try {
        old.close();
      } catch (RuntimeException ignored) {
      }
    }
    listener.disconnected();
  }

  private final BluetoothGattCallback callback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
          post(
              g,
              () -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS
                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                  fail("Disconnected · " + status);
                  return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                  phase = "service discovery";
                  handler.removeCallbacks(timeout);
                  handler.postDelayed(timeout, 15000);
                  listener.status("Discovering XShifter services");
                  if (!g.discoverServices()) fail("Service discovery could not start");
                }
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
          post(
              g,
              () -> {
                if (g != gatt) return;
                BluetoothGattService service = g.getService(Protocol.SERVICE);
                if (status != 0 || service == null) {
                  fail("XShifter service not found");
                  return;
                }
                write = service.getCharacteristic(Protocol.WRITE);
                BluetoothGattCharacteristic notify = service.getCharacteristic(Protocol.NOTIFY);
                if (write == null
                    || notify == null
                    || (write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                  fail("Unsupported XShifter characteristics");
                  return;
                }
                BluetoothGattDescriptor desc = notify.getDescriptor(Protocol.CCCD);
                if (desc == null || !g.setCharacteristicNotification(notify, true)) {
                  fail("Notifications unavailable");
                  return;
                }
                boolean ok;
                phase = "notification setup";
                handler.removeCallbacks(timeout);
                handler.postDelayed(timeout, 10000);
                if (Build.VERSION.SDK_INT >= 33)
                  ok =
                      g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                          == BluetoothStatusCodes.SUCCESS;
                else {
                  desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                  ok = g.writeDescriptor(desc);
                }
                if (!ok) fail("Could not enable notifications");
              });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
          post(
              g,
              () -> {
                if (g != gatt
                    || !Protocol.CCCD.equals(d.getUuid())
                    || !Protocol.NOTIFY.equals(d.getCharacteristic().getUuid())
                    || ready) return;
                if (status != 0) {
                  fail("Notification setup failed");
                  return;
                }
                handler.removeCallbacks(timeout);
                ready = true;
                listener.ready();
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
          post(
              g,
              () -> {
                if (g != gatt || !Protocol.WRITE.equals(c.getUuid()) || !queue.complete()) return;
                handler.removeCallbacks(timeout);
                if (status != 0) {
                  fail("Command failed · " + status);
                  return;
                }
                nextWriteAt = SystemClock.elapsedRealtime() + 150;
                handler.postDelayed(pumpLater, 150);
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
          deliver(g, c, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
          deliver(g, c, c.getValue());
        }

        private void deliver(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
          if (value == null || !Protocol.NOTIFY.equals(c.getUuid())) return;
          byte[] bytes = value.clone();
          post(
              g,
              () -> {
                if (g == gatt) listener.packet(bytes);
              });
        }
      };
}
