package app.xshifter.open;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConnectionReliabilityTest {
  @Test
  public void monitorSurvivesPendingReadInsteadOfRequiringIdle() {
    BleCommandQueue q = new BleCommandQueue();
    q.add(Protocol.requestCogs());
    q.begin();
    q.add(Protocol.version());
    assertFalse(q.idle());
    assertEquals(
        ConnectionPolicy.Background.MONITOR,
        ConnectionPolicy.background(true, false, q.onlyReads(), true));
  }

  @Test
  public void shortForegroundReturnGraceIsNotUnlimitedMonitoring() {
    assertEquals(
        ConnectionPolicy.Background.SHORT_GRACE,
        ConnectionPolicy.background(true, false, true, false));
  }

  @Test
  public void motorTransactionOrUnfinishedLoginMustDisconnectInBackground() {
    assertEquals(
        ConnectionPolicy.Background.DISCONNECT,
        ConnectionPolicy.background(true, true, true, true));
    assertEquals(
        ConnectionPolicy.Background.DISCONNECT,
        ConnectionPolicy.background(false, false, true, true));
    BleCommandQueue q = new BleCommandQueue();
    q.add(Protocol.shift(0, 11));
    q.begin();
    q.setReadOnly(true);
    assertFalse(q.onlyReads());
    assertEquals(
        ConnectionPolicy.Background.DISCONNECT,
        ConnectionPolicy.background(true, false, q.onlyReads(), true));
  }

  @Test
  public void backgroundPurgesUnsentControlsButKeepsPendingReads() {
    BleCommandQueue q = new BleCommandQueue();
    q.add(Protocol.version());
    q.begin();
    q.add(Protocol.commit());
    q.add(Protocol.peer());
    q.setReadOnly(true);
    assertTrue(q.onlyReads());
    assertNull(q.begin());
    assertTrue(q.complete());
    assertArrayEquals(Protocol.peer(), q.begin());
    assertTrue(q.complete());
    assertTrue(q.idle());
  }

  @Test
  public void queueDoesNotReplayCompletedOrClearedWrites() {
    BleCommandQueue q = new BleCommandQueue();
    q.add(Protocol.move(1, 60, false));
    assertNotNull(q.begin());
    assertNull(q.begin());
    q.complete();
    assertNull(q.begin());
    assertFalse(q.complete());
    q.add(Protocol.commit());
    q.clear();
    assertTrue(q.idle());
    assertNull(q.begin());
  }

  @Test
  public void queueOwnsCommandBytes() {
    BleCommandQueue q = new BleCommandQueue();
    byte[] request = Protocol.version();
    q.add(request);
    request[0] = 0;
    byte[] sent = q.begin();
    sent[0] = 0;
    assertTrue(q.onlyReads());
  }

  @Test(expected = IllegalStateException.class)
  public void backgroundRejectsMotorWrites() {
    BleCommandQueue q = new BleCommandQueue();
    q.setReadOnly(true);
    q.add(Protocol.shift(1, 11));
  }

  @Test
  public void foregroundReattachCanUnlockControlsAgain() {
    BleCommandQueue q = new BleCommandQueue();
    q.setReadOnly(true);
    q.setReadOnly(false);
    q.add(Protocol.shift(1, 11));
    assertFalse(q.onlyReads());
    assertNotNull(q.begin());
  }

  @Test
  public void serviceOrLegacyNameIdentifiesCandidatesButNotRandomBikes() {
    assertTrue(ConnectionPolicy.matchesAdvertisement(true, null));
    assertTrue(ConnectionPolicy.matchesAdvertisement(false, "E-Link 123"));
    assertTrue(ConnectionPolicy.matchesAdvertisement(false, "ELINK"));
    assertTrue(ConnectionPolicy.matchesAdvertisement(false, "XShifter"));
    assertTrue(ConnectionPolicy.matchesAdvertisement(false, " x-shifter Mini "));
    assertFalse(ConnectionPolicy.matchesAdvertisement(false, "My bike"));
    assertFalse(ConnectionPolicy.matchesAdvertisement(false, null));
  }

  @Test
  public void loginRetriesAreBoundedAndSpaced() {
    LoginHandshake h = new LoginHandshake();
    h.start(100);
    assertEquals(LoginHandshake.Action.SEND, h.next(100, true));
    assertEquals(LoginHandshake.Action.WAIT, h.next(3099, true));
    assertEquals(LoginHandshake.Action.SEND, h.next(3100, true));
    assertEquals(LoginHandshake.Action.SEND, h.next(6100, true));
    assertEquals(LoginHandshake.Action.WAIT, h.next(9100, true));
    assertEquals(LoginHandshake.Action.FAIL, h.next(12100, true));
  }

  @Test
  public void busyGattCannotQueueDuplicateLoginRequestsForever() {
    LoginHandshake h = new LoginHandshake();
    h.start(0);
    assertEquals(LoginHandshake.Action.WAIT, h.next(0, false));
    assertEquals(LoginHandshake.Action.SEND, h.next(1000, true));
    assertEquals(LoginHandshake.Action.WAIT, h.next(4000, false));
    assertEquals(LoginHandshake.Action.FAIL, h.next(12000, false));
  }

  @Test
  public void loginReplyIsAcceptedOncePerStartedSession() {
    byte[] reply = new byte[18];
    reply[0] = (byte) 0xdf;
    reply[1] = (byte) 0x89;
    LoginHandshake h = new LoginHandshake();
    assertFalse(h.accept(reply));
    h.start(0);
    assertFalse(h.accept(new byte[18]));
    assertFalse(h.accept(new byte[] {(byte) 0xdf, (byte) 0x89}));
    assertTrue(h.accept(reply));
    assertFalse(h.accept(reply));
    assertEquals(LoginHandshake.Action.WAIT, h.next(99999, true));
    h.reset();
    assertFalse(h.accept(reply));
    h.start(100000);
    assertTrue(h.accept(reply));
  }
}
