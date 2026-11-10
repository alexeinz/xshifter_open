package app.xshifter.open;
import org.junit.Test;
import static org.junit.Assert.*;
public class BatteryTrendTest {
  private static BatteryState.Reading r(int cv, long min) { return new BatteryState.Reading(cv,min*60000,min*60000); }
  @Test public void requiresTenMinutesAndIndependentBuckets() {
    BatteryTrend t = new BatteryTrend();
    for(int i=0;i<100;i++) t.add(new BatteryState.Reading(1200-i, i*500, i*500));
    assertFalse(t.result(50000).ready());
  }
  @Test public void steadyBatteryIsNotFastDrain() {
    BatteryTrend t = new BatteryTrend();for(int i=0;i<=10;i++)t.add(r(1200,i));
    assertTrue(t.result(600000).ready());assertFalse(t.result(600000).fastDrop());
  }
  @Test public void sustainedDropIsFlagged() {
    BatteryTrend t = new BatteryTrend();for(int i=0;i<=10;i++)t.add(r(1200-i*4,i));
    assertTrue(t.result(600000).fastDrop());assertEquals(.32,t.result(600000).dropVolts(),.001);
  }
  @Test public void singleLoadSagIsRejected() {
    BatteryTrend t = new BatteryTrend();for(int i=0;i<=10;i++)t.add(r(i==10?1000:1200,i));
    assertFalse(t.result(600000).fastDrop());
  }
  @Test public void staleOrBackwardsClockIsUnknown() {
    BatteryTrend t = new BatteryTrend();for(int i=0;i<=10;i++)t.add(r(1200-i*4,i));
    assertFalse(t.result(600000+120001).ready());assertFalse(t.result(500000).ready());
  }
  @Test public void gapAndReconnectResetHistory() {
    BatteryTrend t = new BatteryTrend();for(int i=0;i<=10;i++)t.add(r(1200-i*4,i));
    t.add(r(1100,14));assertFalse(t.result(840000).ready());t.clear();assertFalse(t.result(840000).ready());
  }
  @Test public void increasingChargeDoesNotAlertAndHistoryIsBounded() {
    BatteryTrend t = new BatteryTrend();for(int i=0;i<=100;i++)t.add(r(1100+i,i));
    assertTrue(t.result(6000000).ready());assertFalse(t.result(6000000).fastDrop());
    assertTrue(t.result(6000000).minutes()<=16);
  }
}
