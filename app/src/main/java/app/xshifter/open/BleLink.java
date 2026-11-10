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
  private boolean busy, ready;
  private boolean readOnly;
  private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
  private final Runnable timeout = () -> fail("Bluetooth operation timed out; disconnected");
  private final Runnable scanEnd = this::stopScan;

  public BleLink(Context c, Listener l) {
    context = c;
    listener = l;
  }

  private BluetoothAdapter adapter() {
    BluetoothManager m = context.getSystemService(BluetoothManager.class);
    return m == null ? null : m.getAdapter();
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
    scanner.startScan(
        List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build()),
        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
        scanCallback);
    listener.status("Searching for XShifter · 12 seconds");
    handler.postDelayed(scanEnd, 12000);
  }

  private final ScanCallback scanCallback =
      new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
          listener.found(r.getDevice(), r.getRssi());
        }

        @Override
        public void onScanFailed(int code) {
          listener.status("Bluetooth scan failed: " + code);
          stopScan();
        }
      };

  public void stopScan() {
    handler.removeCallbacks(scanEnd);
    if (scanner != null) {
      try {
        scanner.stopScan(scanCallback);
      } catch (SecurityException ignored) {
      }
      scanner = null;
    }
  }

  public void connect(BluetoothDevice device) {
    close();
    listener.status("Connecting to " + device.getAddress());
    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    handler.postDelayed(timeout, 15000);
  }

  public boolean isReady() {
    return ready;
  }

  public boolean isIdle() {
    return ready && !busy && queue.isEmpty();
  }

  public void send(byte[] data) {
    if (readOnly && !Protocol.isReadOnlyCommand(data))
      throw new IllegalStateException("Device changes are disabled during background monitoring");
    if (!ready) throw new IllegalStateException("Connect to your XShifter first");
    if (queue.size() >= 32) throw new IllegalStateException("Wait for pending operations");
    queue.add(data.clone());
    pump();
  }

  public void setReadOnly(boolean value) {
    readOnly = value;
    if (value) queue.removeIf(packet -> !Protocol.isReadOnlyCommand(packet));
  }

  private void pump() {
    if (busy || queue.isEmpty() || !ready) return;
    byte[] bytes = queue.remove();
    busy = true;
    boolean ok;
    if (Build.VERSION.SDK_INT >= 33)
      ok =
          gatt.writeCharacteristic(write, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
              == BluetoothStatusCodes.SUCCESS;
    else {
      write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
      write.setValue(bytes);
      ok = gatt.writeCharacteristic(write);
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
    queue.clear();
    busy = false;
    ready = false;
    write = null;
    BluetoothGatt old = gatt;
    gatt = null;
    if (old != null) {
      try {
        old.disconnect();
        old.close();
      } catch (SecurityException ignored) {
      }
    }
    listener.disconnected();
  }

  private final BluetoothGattCallback callback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
          handler.post(
              () -> {
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS
                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                  fail("Disconnected · " + status);
                  return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                  listener.status("Discovering XShifter services");
                  if (!g.discoverServices()) fail("Service discovery could not start");
                }
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
          handler.post(
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
          handler.post(
              () -> {
                if (g != gatt) return;
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
          handler.post(
              () -> {
                if (g != gatt) return;
                handler.removeCallbacks(timeout);
                busy = false;
                if (status != 0) {
                  fail("Command failed · " + status);
                  return;
                }
                pump();
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
          handler.post(
              () -> {
                if (g == gatt) listener.packet(bytes);
              });
        }
      };
}
