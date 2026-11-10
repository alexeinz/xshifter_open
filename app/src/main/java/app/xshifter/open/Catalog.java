package app.xshifter.open;

import java.util.*;

/** Manufacturer metadata, never a claim of calibrated XShifter cable travel. */
public final class Catalog {
  private Catalog() {}

  public static int[] parseTeeth(String text, int gearCount) {
    if (text.trim().isEmpty()) return new int[0];
    String[] pieces = text.trim().split("[,;\\s]+");
    int[] teeth = new int[pieces.length];
    for (int i = 0; i < teeth.length; i++) teeth[i] = Integer.parseInt(pieces[i]);
    validateTeeth(teeth, gearCount);
    return teeth;
  }

  public static void validateTeeth(int[] teeth, int gearCount) {
    if (teeth.length == 0) return;
    if (teeth.length != gearCount)
      throw new IllegalArgumentException("Provide one tooth count per gear");
    for (int i = 0; i < teeth.length; i++) {
      if (teeth[i] < 1 || teeth[i] > 200)
        throw new IllegalArgumentException("Invalid sprocket tooth count");
      if (i > 0 && teeth[i] <= teeth[i - 1])
        throw new IllegalArgumentException(
            "Tooth counts must increase from smallest to largest sprocket");
    }
  }

  public record Derailleur(String name, int speed, int maxCog, String source) {
    @Override
    public String toString() {
      return name;
    }
  }

  public record Cassette(String name, int speed, int low, int high, String source) {
    @Override
    public String toString() {
      return name;
    }
  }

  public static final List<Derailleur> DERAILLEURS =
      List.of(
          new Derailleur(
              "SRAM Rival 22 · short cage",
              11,
              28,
              "https://www.sram.com/en/sram/models/rd-riv-b1"),
          new Derailleur(
              "SRAM Rival 22 · medium / WiFLi",
              11,
              32,
              "https://www.sram.com/en/sram/models/rd-riv-b1"),
          new Derailleur(
              "SRAM Rival 1 · medium cage",
              11,
              36,
              "https://www.sram.com/en/sram/models/rd-riv-1-a2"),
          new Derailleur(
              "SRAM Rival 1 · long cage",
              11,
              42,
              "https://www.sram.com/en/sram/models/rd-riv-1-a2"),
          new Derailleur("Other mechanical derailleur · custom calibration", 0, 0, ""));
  public static final List<Cassette> CASSETTES =
      List.of(
          sram("PG-1170", 11, 28),
          sram("PG-1170", 11, 32),
          sram("PG-1170", 11, 36),
          new Cassette(
              "SRAM XG-1150 · 11-speed · 10–42",
              11,
              10,
              42,
              "https://www.sram.com/globalassets/document-hierarchy/compatibility-map/road/road-compatibility-map-2024.pdf"),
          shimano(11, 28),
          shimano(11, 30),
          shimano(11, 32),
          shimano(12, 25),
          new Cassette("Custom cassette · enter gear count", 0, 0, 0, ""));

  private static Cassette sram(String model, int lo, int hi) {
    return new Cassette(
        "SRAM " + model + " · 11-speed · " + lo + "–" + hi,
        11,
        lo,
        hi,
        "https://www.sram.com/en/sram/models/cs-pg-1170-a1");
  }

  private static Cassette shimano(int lo, int hi) {
    return new Cassette(
        "Shimano CS-R7000 · 11-speed · " + lo + "–" + hi,
        11,
        lo,
        hi,
        "https://si.shimano.com/ja/pdfs/ev/CS-R7000-4330/EV-CS-R7000-4330.pdf");
  }

  public static String constraint(Derailleur d, Cassette c) {
    if (c.speed() == 0)
      return "Custom cassette. Enter its gear count and calibrate every position; mechanical"
          + " compatibility is not established by this selection.";
    if (d.speed() != 0 && d.speed() != c.speed())
      return "Gear count differs from the derailleur's catalog specification.";
    if (d.maxCog() != 0 && c.high() > d.maxCog())
      return "Largest sprocket exceeds this derailleur's listed maximum.";
    return "Within the listed gear-count / maximum-sprocket limits. Chain capacity, minimum"
        + " sprocket, chain, freehub and installation still need checking. Cable positions"
        + " require calibration.";
  }
}
