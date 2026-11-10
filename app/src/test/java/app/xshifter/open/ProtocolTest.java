package app.xshifter.open;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ProtocolTest {
  @Test
  public void acceptsCustomCassetteMetadataWithoutGuessingCableTravel() {
    assertArrayEquals(
        new int[] {11, 12, 14, 16, 18, 20, 22, 25, 28, 32, 36},
        Catalog.parseTeeth("11,12,14,16,18,20,22,25,28,32,36", 11));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsWrongCassetteToothCount() {
    Catalog.parseTeeth("11,13,15", 11);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsUnorderedCassetteTeeth() {
    Catalog.parseTeeth("11,15,13", 3);
  }

  @Test
  public void toothMetadataIsOptional() {
    assertEquals(0, Catalog.parseTeeth("", 11).length);
  }

  @Test
  public void recoveredReadCommandVectors() {
    assertEquals("0A00000000000000", Protocol.hex(Protocol.requestCogs()));
    assertEquals("ACC105", Protocol.hex(Protocol.version()));
    assertEquals("ACC106", Protocol.hex(Protocol.peer()));
    assertEquals("1901AA0000000000", Protocol.hex(Protocol.commit()));
    assertEquals("ACC10300", Protocol.hex(Protocol.clear()));
  }

  @Test
  public void loginPreservesEightByteIdentityAndPadding() {
    assertEquals(
        "DF89010203040506070800000000000000",
        Protocol.hex(Protocol.login(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})));
  }

  @Test(expected = IllegalArgumentException.class)
  public void loginRejectsWrongIdentityLength() {
    Protocol.login(new byte[15]);
  }

  @Test
  public void recoveredShiftVector() {
    assertEquals("1E05000000000000", Protocol.hex(Protocol.shift(5, 11)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void shiftRejectsOutOfRangeIndex() {
    Protocol.shift(11, 11);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shiftRejectsNegativeIndex() {
    Protocol.shift(-1, 11);
  }

  @Test
  public void trimUsesLittleEndianAndCorrectDirection() {
    assertEquals("1E04012C01000001", Protocol.hex(Protocol.move(4, -300, false)));
    assertEquals("1E04003C00000001", Protocol.hex(Protocol.move(4, 60, false)));
  }

  @Test
  public void cableDirectionIsOppositeTrimEncoding() {
    assertEquals("1E65013C00000001", Protocol.hex(Protocol.cable(60)));
    assertEquals("1E65003C00000001", Protocol.hex(Protocol.cable(-60)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void movementRejectsIntegerOverflow() {
    Protocol.move(0, Integer.MIN_VALUE, false);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsOverlappingGears() {
    Protocol.validatePositions(new int[] {0, 100, 100});
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsTravelBeyondOriginalLimit() {
    Protocol.validatePositions(new int[] {0, 25201});
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsNegativeTravel() {
    Protocol.validatePositions(new int[] {-1, 50});
  }

  @Test
  public void linearKeepsMeasuredEndpointsWithoutCumulativeDrift() {
    int[] p = Protocol.linear(11, 123, 20111);
    assertEquals(123, p[0]);
    assertEquals(20111, p[10]);
    Protocol.validatePositions(p);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsInsufficientCalibrationSpan() {
    Protocol.linear(11, 0, 9);
  }

  @Test
  public void readsFragmentedTableInAnyOrder() {
    Protocol.TableReader r = new Protocol.TableReader();
    assertNull(r.accept(Protocol.unhex("0ACC780002")));
    assertNull(r.accept(Protocol.unhex("0AC1")));
    assertArrayEquals(new int[] {0, 600, 1800}, r.accept(Protocol.unhex("0AC000005802B004")));
    assertEquals(120, r.adjustment);
    assertEquals(2, r.gear);
  }

  @Test
  public void readerDoesNotMixRequests() {
    Protocol.TableReader a = new Protocol.TableReader();
    a.accept(Protocol.unhex("0AC000005802B004"));
    Protocol.TableReader b = new Protocol.TableReader();
    assertNull(b.accept(Protocol.unhex("0AC1")));
    assertNull(b.accept(Protocol.unhex("0ACC000000")));
  }

  @Test
  public void decodesUnsignedSixteenBitValues() {
    assertEquals(65535, Protocol.le(new byte[] {-1, -1}, 0));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsTruncatedTableStatus() {
    new Protocol.TableReader().accept(Protocol.unhex("0ACC0000"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsOddLengthTablePart() {
    new Protocol.TableReader().accept(Protocol.unhex("0AC000"));
  }

  @Test
  public void tableUploadMatchesRecoveredIncrementFormat() {
    List<byte[]> p = Protocol.upload(new int[] {0, 600, 1800}, 120);
    assertEquals("190A000000000000", Protocol.hex(p.get(0)));
    assertEquals("1900005802780000", Protocol.hex(p.get(1)));
    assertEquals("19CC02B004000000", Protocol.hex(p.get(2)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void twoGearUploadIsNotAssumedSupported() {
    Protocol.upload(new int[] {0, 600}, 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsBadHex() {
    Protocol.unhex("GG");
  }

  @Test
  public void rivalLimitsDistinguishCages() {
    Catalog.Cassette wide = Catalog.CASSETTES.get(3);
    assertTrue(Catalog.constraint(Catalog.DERAILLEURS.get(0), wide).contains("exceeds"));
    assertTrue(Catalog.constraint(Catalog.DERAILLEURS.get(3), wide).contains("Within"));
  }
}
