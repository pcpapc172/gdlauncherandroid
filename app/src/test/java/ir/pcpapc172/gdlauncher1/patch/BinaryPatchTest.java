package ir.pcpapc172.gdlauncher1.patch;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class BinaryPatchTest {
  static final String GAME = "com.robtopx.geometryjump", TARGET = "ir.gd.i0123456789abcdef0";

  @Test
  public void replacesEveryOccurrenceAndPreservesSize() {
    byte[] original = (GAME + "/data/" + GAME + "\0").getBytes(StandardCharsets.UTF_8);
    byte[] result = BinaryPatch.replace(original, GAME, TARGET);
    assertEquals(original.length, result.length);
    assertFalse(BinaryPatch.contains(result, GAME));
    assertEquals(TARGET + "/data/" + TARGET + "\0", new String(result, StandardCharsets.UTF_8));
    assertTrue(BinaryPatch.contains(original, GAME)); // input remains untouched
  }

  @Test
  public void streamingFindsBufferBoundaryMatches() throws Exception {
    for (int offset = 65500; offset < 65570; offset++) {
      byte[] original = ("x".repeat(offset) + GAME + "/" + GAME).getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      BinaryPatch.replaceStream(
          new ByteArrayInputStream(original), output, GAME, TARGET, false, () -> false);
      assertFalse(BinaryPatch.contains(output.toByteArray(), GAME));
      assertEquals(original.length, output.size());
    }
  }

  @Test
  public void streamingStopsOnCancellationAndUnsafeJniSymbols() {
    assertThrows(
        InterruptedIOException.class,
        () ->
            BinaryPatch.replaceStream(
                new ByteArrayInputStream(new byte[100]),
                new ByteArrayOutputStream(),
                GAME,
                TARGET,
                false,
                () -> true));
    assertThrows(
        IOException.class,
        () ->
            BinaryPatch.replaceStream(
                new ByteArrayInputStream(GAME.replace('.', '_').getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                GAME,
                TARGET,
                true,
                () -> false));
  }

  @Test
  public void handlesUtf16AndRejectsWrongLength() {
    byte[] original = GAME.getBytes(StandardCharsets.UTF_16LE);
    assertEquals(
        TARGET, new String(BinaryPatch.replace(original, GAME, TARGET), StandardCharsets.UTF_16LE));
    assertThrows(
        IllegalArgumentException.class, () -> BinaryPatch.replace(original, GAME, "short"));
  }

  @Test
  public void rebuildsBothManifestEncodingsAndResourceLabels() throws Exception {
    for (boolean utf8 : new boolean[] {true, false}) {
      byte[] manifest = fixture(utf8, "com.geode.launcher");
      byte[] output =
          BinaryPatch.manifest(
              manifest, "com.geode.launcher", "gd.g0123456789abcd", "My game · Geode", true);
      assertEquals(output.length, le(output, 4));
      assertTrue(BinaryPatch.contains(output, "com.geode.launcher.MainActivity"));
      assertTrue(BinaryPatch.contains(output, "gd.g0123456789abcd.user"));
      assertTrue(BinaryPatch.contains(output, "My game · Geode"));
      assertEquals("gd.g0123456789abcd", BinaryPatch.packageName(output));
      assertEquals(
          "gd.g0123456789abcd.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
          BinaryPatch.attributeValue(output, "permission", "name"));
      assertEquals(
          "gd.g0123456789abcd.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
          BinaryPatch.attributeValue(output, "uses-permission", "name"));
      assertArrayEquals(manifest, fixture(utf8, "com.geode.launcher"));
    }
  }

  @Test
  public void gameManifestRetargetsClassesAndAuthorities() throws Exception {
    byte[] output = BinaryPatch.manifest(fixture(true, GAME), GAME, TARGET, "Clone", false);
    assertFalse(BinaryPatch.contains(output, GAME));
    assertTrue(BinaryPatch.contains(output, TARGET + ".user"));
  }

  @Test
  public void malformedXmlFailsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BinaryPatch.manifest(new byte[8], GAME, TARGET, "x", false));
  }

  private static int le(byte[] b, int p) {
    return ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static void put(byte[] b, int p, int v) {
    ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
  }

  private static void word(byte[] b, int p, int v) {
    b[p] = (byte) v;
    b[p + 1] = (byte) (v >> 8);
  }

  static byte[] fixture(boolean utf8, String pkg) throws Exception {
    List<String> s =
        Arrays.asList(
            "manifest",
            "package",
            pkg,
            "application",
            "name",
            ".MainActivity",
            "provider",
            "authorities",
            pkg + ".user",
            "label",
            "a".repeat(140),
            "permission",
            "uses-permission",
            pkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();
    for (String v : s) {
      offsets.add(text.size());
      byte[] bytes = v.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
      for (int x = 0; x < (utf8 ? 2 : 1); x++) {
        int len = utf8 ? (x == 0 ? v.length() : bytes.length) : v.length();
        if (utf8) {
          if (len > 127) text.write((len >> 8) | 128);
          text.write(len & 255);
        } else {
          text.write(len & 255);
          text.write(len >> 8);
        }
      }
      text.write(bytes);
      text.write(0);
      if (!utf8) text.write(0);
    }
    while (text.size() % 4 != 0) text.write(0);
    int start = 28 + s.size() * 4;
    byte[] pool = new byte[start + text.size()];
    word(pool, 0, 1);
    word(pool, 2, 28);
    put(pool, 4, pool.length);
    put(pool, 8, s.size());
    put(pool, 16, utf8 ? 256 : 0);
    put(pool, 20, start);
    for (int n = 0; n < s.size(); n++) put(pool, 28 + n * 4, offsets.get(n));
    System.arraycopy(text.toByteArray(), 0, pool, start, text.size());
    byte[] root = node(0, new int[][] {{1, 2, 3}}),
        application = node(3, new int[][] {{4, 5, 3}, {9, -1, 1}}),
        provider = node(6, new int[][] {{7, 8, 3}});
    byte[] permission = node(11, new int[][] {{4, 13, 3}}),
        usesPermission = node(12, new int[][] {{4, 13, 3}});
    byte[] output =
        new byte
            [8
                + pool.length
                + root.length
                + application.length
                + provider.length
                + permission.length
                + usesPermission.length];
    word(output, 0, 3);
    word(output, 2, 8);
    put(output, 4, output.length);
    int at = 8;
    for (byte[] chunk :
        new byte[][] {pool, root, application, provider, permission, usesPermission}) {
      System.arraycopy(chunk, 0, output, at, chunk.length);
      at += chunk.length;
    }
    return output;
  }

  private static byte[] node(int name, int[][] attrs) {
    byte[] b = new byte[36 + 20 * attrs.length];
    word(b, 0, 0x102);
    word(b, 2, 16);
    put(b, 4, b.length);
    put(b, 12, -1);
    put(b, 16, -1);
    put(b, 20, name);
    word(b, 24, 20);
    word(b, 26, 20);
    word(b, 28, attrs.length);
    for (int i = 0; i < attrs.length; i++) {
      int at = 36 + 20 * i;
      put(b, at, -1);
      put(b, at + 4, attrs[i][0]);
      put(b, at + 8, attrs[i][1]);
      word(b, at + 12, 8);
      b[at + 15] = (byte) attrs[i][2];
      put(b, at + 16, attrs[i][1]);
    }
    return b;
  }
}
