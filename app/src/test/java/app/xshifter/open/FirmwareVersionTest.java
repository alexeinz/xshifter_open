package app.xshifter.open;
import org.junit.Test;
import static org.junit.Assert.*;
public class FirmwareVersionTest {
 @Test public void requestMatchesOriginal() { assertArrayEquals(Protocol.unhex("ACC105"),Protocol.version()); }
 @Test public void modernAsciiAndNullPadding() {
  assertEquals("v1.105",Protocol.firmwareVersion(Protocol.unhex("ACC10576312E3130350000")));
  assertEquals("1105",Protocol.firmwareVersion(Protocol.unhex("ACC10531313035")));
 }
 @Test public void legacyReplyUsesOriginalMapping() {
  assertEquals("27 (legacy reply)",Protocol.firmwareVersion(Protocol.unhex("3700")));
 }
 @Test public void malformedAndOtherPacketsAreNotVersions() {
  for(String s:new String[]{"","37","ACC105","ACC1063132","ACC105FF","ACC105000031","ACC105616263","DF89"})
   assertNull(s,Protocol.firmwareVersion(Protocol.unhex(s)));
  assertNull(Protocol.firmwareVersion(null));
 }
}
