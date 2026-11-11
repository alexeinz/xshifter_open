package app.xshifter.open;

import java.util.Locale;

final class ConnectionPolicy {
  enum Background {
    DISCONNECT,
    SHORT_GRACE,
    MONITOR
  }

  static Background background(
      boolean authenticated, boolean unsafeTransaction, boolean onlyReads, boolean monitoring) {
    if (!authenticated || unsafeTransaction || !onlyReads) return Background.DISCONNECT;
    return monitoring ? Background.MONITOR : Background.SHORT_GRACE;
  }

  static boolean matchesAdvertisement(boolean serviceAdvertised, String name) {
    if (serviceAdvertised) return true;
    if (name == null) return false;
    String n = name.trim().toLowerCase(Locale.ROOT);
    return n.startsWith("xshifter")
        || n.startsWith("x-shifter")
        || n.startsWith("elink")
        || n.startsWith("e-link");
  }
}
