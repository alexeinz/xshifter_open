package app.xshifter.open;

import static org.junit.Assert.*;

import org.junit.Test;

public class BatteryStateTest {
  @Test
  public void estimateMatchesRecoveredBatteryIconThresholds() {
    int[] volts = {1020, 1116, 1140, 1152, 1173, 1209, 1230, 1240},
        levels = {0, 20, 30, 50, 60, 80, 90, 100};
    for (int i = 0; i < volts.length; i++)
      assertEquals(levels[i], new BatteryState.Reading(volts[i], 0, 0).percent());
    assertEquals(19, new BatteryState.Reading(1110, 0, 0).percent());
    assertEquals(
        BatteryState.LOW,
        BatteryState.evaluate(new BatteryState.Reading(1110, 0, 0), 0, true, 20, 0).alert());
    assertEquals(
        BatteryState.CRITICAL,
        BatteryState.evaluate(new BatteryState.Reading(1060, 0, 0), 0, true, 20, 0).alert());
  }

  @Test
  public void recoveredVoltagePacketUsesCentivoltsWithoutIntegerTruncation() {
    BatteryState.Reading r = BatteryState.decode(Protocol.unhex("010101B004"), 123, 456);
    assertNotNull(r);
    assertEquals(1200, r.centivolts());
    assertEquals(12.0, r.volts(), .0001);
    assertEquals(75, r.percent());
    assertEquals(123, r.elapsedMs());
    assertEquals(12.09, BatteryState.decode(Protocol.unhex("010101B904"), 0, 0).volts(), .0001);
  }

  @Test
  public void rejectsMalformedOrUnrelatedPackets() {
    for (byte[] b :
        new byte[][] {
          null,
          new byte[0],
          Protocol.unhex("01010104"),
          Protocol.unhex("010201B004"),
          Protocol.unhex("DEDE0000000000000000"),
          Protocol.unhex("0101010000"),
          Protocol.unhex("010101FFFF")
        }) assertNull(BatteryState.decode(b, 0, 0));
  }

  @Test
  public void estimateClampsAtOriginalVoltageEndpoints() {
    assertEquals(0, new BatteryState.Reading(950, 0, 0).percent());
    assertEquals(100, new BatteryState.Reading(1240, 0, 0).percent());
    assertEquals(0, new BatteryState.Reading(900, 0, 0).percent());
    assertEquals(100, new BatteryState.Reading(1260, 0, 0).percent());
  }

  @Test
  public void freshnessRejectsOldAndFutureSamples() {
    BatteryState.Reading r = new BatteryState.Reading(1200, 1000, 0);
    assertTrue(r.fresh(121000));
    assertFalse(r.fresh(121001));
    assertFalse(r.fresh(999));
  }

  @Test
  public void lowWarningOccursOnceUntilRecovery() {
    BatteryState.Decision first = BatteryState.evaluate(20, 20, 0);
    assertEquals(BatteryState.LOW, first.alert());
    assertEquals(BatteryState.NONE, BatteryState.evaluate(19, 20, first.mask()).alert());
    assertEquals(first.mask(), BatteryState.evaluate(24, 20, first.mask()).mask());
    assertEquals(0, BatteryState.evaluate(25, 20, first.mask()).mask());
  }

  @Test
  public void firstCriticalSampleDoesNotAlsoSendLowWarning() {
    BatteryState.Decision d = BatteryState.evaluate(9, 20, 0);
    assertEquals(BatteryState.CRITICAL, d.alert());
    assertEquals(3, d.mask());
    assertEquals(BatteryState.NONE, BatteryState.evaluate(8, 20, d.mask()).alert());
  }

  @Test
  public void lowThenCriticalProducesOneOfEach() {
    BatteryState.Decision low = BatteryState.evaluate(19, 20, 0),
        critical = BatteryState.evaluate(10, 20, low.mask());
    assertEquals(BatteryState.LOW, low.alert());
    assertEquals(BatteryState.CRITICAL, critical.alert());
  }

  @Test
  public void criticalHysteresisResistsVoltageBounce() {
    int mask = BatteryState.evaluate(9, 20, 0).mask();
    assertEquals(3, BatteryState.evaluate(14, 20, mask).mask());
    mask = BatteryState.evaluate(15, 20, mask).mask();
    assertEquals(1, mask);
    assertEquals(BatteryState.CRITICAL, BatteryState.evaluate(10, 20, mask).alert());
  }

  @Test
  public void persistedMaskSuppressesReconnectSpam() {
    int stored = BatteryState.evaluate(10, 20, 0).mask();
    assertEquals(BatteryState.NONE, BatteryState.evaluate(10, 20, stored).alert());
  }

  @Test
  public void customThresholdIsUsed() {
    assertEquals(BatteryState.LOW, BatteryState.evaluate(30, 30, 0).alert());
    assertEquals(BatteryState.NONE, BatteryState.evaluate(30, 20, 0).alert());
  }

  @Test
  public void unknownStaleAndDisconnectedNeverNotify() {
    BatteryState.Reading low = new BatteryState.Reading(975, 1000, 0);
    assertEquals(BatteryState.NONE, BatteryState.evaluate(null, 2000, true, 20, 0).alert());
    assertEquals(BatteryState.NONE, BatteryState.evaluate(low, 2000, false, 20, 0).alert());
    assertEquals(BatteryState.NONE, BatteryState.evaluate(low, 121001, true, 20, 0).alert());
    assertEquals(BatteryState.CRITICAL, BatteryState.evaluate(low, 2000, true, 20, 0).alert());
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidThresholdRejected() {
    BatteryState.evaluate(20, 0, 0);
  }

  @Test
  public void backgroundTransportAllowsOnlyExactTelemetryRequests() {
    assertTrue(Protocol.isReadOnlyCommand(Protocol.requestCogs()));
    assertTrue(Protocol.isReadOnlyCommand(Protocol.version()));
    assertTrue(Protocol.isReadOnlyCommand(Protocol.peer()));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.login(new byte[8])));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.clear()));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.commit()));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.shift(0, 11)));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.move(1, 60, false)));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.cable(60)));
    assertFalse(Protocol.isReadOnlyCommand(Protocol.unhex("ACC10500")));
    assertFalse(Protocol.isReadOnlyCommand(null));
  }
}
