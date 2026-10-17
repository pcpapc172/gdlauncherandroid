package ir.pcpapc172.gdlauncher1.patch;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Length preserving native/resource edits and structured binary-XML string-pool rebuilding. */
public final class BinaryPatch {
  private BinaryPatch() {}

  public static byte[] replace(byte[] input, String from, String to) {
    if (from.equals(to)) return input;
    if (from.length() != to.length())
      throw new IllegalArgumentException("Replacement must have equal length");
    byte[] output = input.clone();
    replaceInPlace(output, from, to);
    return output;
  }

  public static void replaceInPlace(byte[] output, String from, String to) {
    if (from.equals(to)) return;
    if (from.length() != to.length())
      throw new IllegalArgumentException("Replacement must have equal length");
    for (java.nio.charset.Charset charset :
        new java.nio.charset.Charset[] {StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
      byte[] find = from.getBytes(charset), replacement = to.getBytes(charset);
      for (int i = 0; i <= output.length - find.length; i++) {
        int j = 0;
        while (j < find.length && output[i + j] == find[j]) j++;
        if (j == find.length) {
          System.arraycopy(replacement, 0, output, i, replacement.length);
          i += find.length - 1;
        }
      }
    }
  }

  /** Stream large native libraries/assets, including matches split across input buffers. */
  public static void replaceStream(
      java.io.InputStream input,
      java.io.OutputStream output,
      String from,
      String to,
      boolean nativeLibrary,
      java.util.function.BooleanSupplier cancelled)
      throws java.io.IOException {
    if (from.length() != to.length())
      throw new IllegalArgumentException("Replacement must have equal length");
    List<byte[]> needles = new ArrayList<>(), replacements = new ArrayList<>();
    for (String separator : new String[] {".", "/"})
      for (java.nio.charset.Charset charset :
          new java.nio.charset.Charset[] {StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
        needles.add(from.replace(".", separator).getBytes(charset));
        replacements.add(to.replace(".", separator).getBytes(charset));
      }
    byte[] forbidden = from.replace('.', '_').getBytes(StandardCharsets.UTF_8);
    int longest =
        Math.max(forbidden.length, needles.stream().mapToInt(a -> a.length).max().orElse(0));
    byte[] buffer = new byte[65536 + longest];
    int carry = 0;
    while (true) {
      if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Cancelled");
      int read = input.read(buffer, carry, 65536);
      boolean eof = read < 0;
      int size = carry + (eof ? 0 : read),
          limit = eof ? size : Math.max(0, size - longest + 1),
          pos = 0;
      while (pos < limit) {
        if (nativeLibrary && matches(buffer, size, pos, forbidden))
          throw new java.io.IOException("JNI package symbols require a full native rebuild");
        boolean found = false;
        for (int j = 0; j < needles.size(); j++)
          if (matches(buffer, size, pos, needles.get(j))) {
            output.write(replacements.get(j));
            pos += needles.get(j).length;
            found = true;
            break;
          }
        if (!found) output.write(buffer[pos++]);
      }
      if (eof) break;
      carry = size - pos;
      System.arraycopy(buffer, pos, buffer, 0, carry);
    }
  }

  private static boolean matches(byte[] data, int size, int pos, byte[] value) {
    if (pos + value.length > size) return false;
    for (int j = 0; j < value.length; j++) if (data[pos + j] != value[j]) return false;
    return true;
  }

  public static boolean contains(byte[] input, String value) {
    for (java.nio.charset.Charset c :
        new java.nio.charset.Charset[] {StandardCharsets.UTF_8, StandardCharsets.UTF_16LE}) {
      byte[] v = value.getBytes(c);
      outer:
      for (int i = 0; i <= input.length - v.length; i++) {
        for (int j = 0; j < v.length; j++) if (input[i + j] != v[j]) continue outer;
        return true;
      }
    }
    return false;
  }

  /** Read the package even for configuration split APKs that PackageManager cannot parse alone. */
  public static String packageName(byte[] input) {
    return attributeValue(input, "manifest", "package");
  }

  public static String attributeValue(byte[] input, String tag, String attribute) {
    if (input.length < 8 || u16(input, 0) != 3)
      throw new IllegalArgumentException("Expected binary XML");
    List<String> strings = new ArrayList<>();
    for (int p = 8; p < input.length; ) {
      int kind = u16(input, p), size = i32(input, p + 4);
      if (size < 8 || p + size > input.length) throw new IllegalArgumentException("Malformed XML");
      if (kind == 1) {
        int count = i32(input, p + 8), header = u16(input, p + 2), start = i32(input, p + 20);
        boolean utf8 = (i32(input, p + 16) & 256) != 0;
        for (int n = 0; n < count; n++) {
          int[] pos = {p + start + i32(input, p + header + 4 * n)};
          int len = length(input, pos, utf8);
          if (utf8) len = length(input, pos, true);
          strings.add(
              new String(
                  input,
                  pos[0],
                  len * (utf8 ? 1 : 2),
                  utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE));
        }
      } else if (kind == 0x102 && strings.get(i32(input, p + 20)).equals(tag)) {
        int attrs = p + 16 + u16(input, p + 24),
            stride = u16(input, p + 26),
            count = u16(input, p + 28);
        for (int n = 0; n < count; n++) {
          int a = attrs + n * stride;
          if (strings.get(i32(input, a + 4)).equals(attribute)) {
            int value = i32(input, a + 8);
            if (value < 0) value = i32(input, a + 16);
            return strings.get(value);
          }
        }
      }
      p += size;
    }
    throw new IllegalArgumentException("Manifest attribute is missing: " + tag + "/" + attribute);
  }

  private static int u16(byte[] b, int p) {
    return (b[p] & 255) | ((b[p + 1] & 255) << 8);
  }

  private static int i32(byte[] b, int p) {
    return ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static void put(byte[] b, int p, int v) {
    ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
  }

  private static int length(byte[] b, int[] p, boolean utf8) {
    int n = utf8 ? b[p[0]++] & 255 : u16(b, p[0]);
    if (!utf8) p[0] += 2;
    int flag = utf8 ? 128 : 32768;
    if ((n & flag) == 0) return n;
    int next = utf8 ? b[p[0]++] & 255 : u16(b, p[0]);
    if (!utf8) p[0] += 2;
    return ((n & (flag - 1)) << (utf8 ? 8 : 16)) | next;
  }

  private static void writeLength(ByteArrayOutputStream o, int n, boolean utf8) {
    if (utf8) {
      if (n > 32767) throw new IllegalArgumentException("XML string too long");
      if (n > 127) o.write((n >> 8) | 128);
      o.write(n & 255);
    } else {
      if (n > 32767) {
        int hi = (n >> 16) | 32768;
        o.write(hi & 255);
        o.write(hi >> 8);
      }
      o.write(n & 255);
      o.write((n >> 8) & 255);
    }
  }

  public static byte[] manifest(
      byte[] input, String oldPackage, String newPackage, String label, boolean preserveClasses) {
    if (input.length < 8 || u16(input, 0) != 3 || i32(input, 4) != input.length)
      throw new IllegalArgumentException("Expected binary Android manifest");
    int pool = -1, poolSize = 0;
    for (int p = 8; p < input.length; ) {
      int size = i32(input, p + 4);
      if (size < 8 || p + size > input.length)
        throw new IllegalArgumentException("Malformed XML chunk");
      if (u16(input, p) == 1) {
        pool = p;
        poolSize = size;
        break;
      }
      p += size;
    }
    if (pool < 0) throw new IllegalArgumentException("Missing XML string pool");
    int header = u16(input, pool + 2),
        count = i32(input, pool + 8),
        styles = i32(input, pool + 12),
        flags = i32(input, pool + 16);
    if (styles != 0) throw new IllegalArgumentException("Styled manifest strings are unsupported");
    boolean utf8 = (flags & 256) != 0;
    int start = i32(input, pool + 20);
    List<String> strings = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int[] pos = {pool + start + i32(input, pool + header + 4 * i)};
      int n = length(input, pos, utf8);
      if (utf8) n = length(input, pos, true);
      strings.add(
          new String(
              input,
              pos[0],
              n * (utf8 ? 1 : 2),
              utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE));
    }
    byte[] tail = Arrays.copyOfRange(input, pool + poolSize, input.length);
    Set<String> components =
        new HashSet<>(
            Arrays.asList(
                "application",
                "activity",
                "activity-alias",
                "service",
                "receiver",
                "provider",
                "instrumentation"));
    for (int p = 0; p < tail.length; ) {
      int size = i32(tail, p + 4);
      if (size < 8 || p + size > tail.length)
        throw new IllegalArgumentException("Malformed XML node");
      if (u16(tail, p) == 0x102) {
        String tag = strings.get(i32(tail, p + 20));
        int attrs = p + 16 + u16(tail, p + 24),
            stride = u16(tail, p + 26),
            amount = u16(tail, p + 28);
        for (int j = 0; j < amount; j++) {
          int a = attrs + j * stride;
          String key = strings.get(i32(tail, a + 4));
          int index = i32(tail, a + 8);
          if (index < 0 && (tail[a + 15] & 255) == 3) index = i32(tail, a + 16);
          if (tag.equals("application") && key.equals("label")) {
            int labelIndex = strings.indexOf(label);
            if (labelIndex < 0) {
              labelIndex = strings.size();
              strings.add(label);
            }
            put(tail, a + 8, labelIndex);
            tail[a + 14] = 8;
            tail[a + 15] = 3;
            put(tail, a + 16, labelIndex);
            continue;
          }
          if (index < 0) continue;
          String value = strings.get(index), next = value;
          if (tag.equals("manifest") && key.equals("package")) next = newPackage;
          else if (tag.equals("application") && key.equals("label")) next = label;
          else if (preserveClasses
              && components.contains(tag)
              && (key.equals("name")
                  || key.equals("targetActivity")
                  || key.equals("backupAgent")
                  || key.equals("appComponentFactory")
                  || key.equals("manageSpaceActivity"))) {
            next =
                value.startsWith(".")
                    ? oldPackage + value
                    : !value.contains(".") ? oldPackage + "." + value : value;
          } else if (preserveClasses
              && (key.equals("authorities")
                  || key.equals("permission")
                  || key.equals("readPermission")
                  || key.equals("writePermission")
                  || tag.contains("permission")
                  || key.equals("taskAffinity")
                  || key.equals("targetPackage"))) next = value.replace(oldPackage, newPackage);
          else if (!preserveClasses) next = value.replace(oldPackage, newPackage);
          if (!next.equals(value)) {
            int newIndex = strings.indexOf(next);
            if (newIndex < 0) {
              newIndex = strings.size();
              strings.add(next);
            }
            put(tail, a + 8, newIndex);
            tail[a + 14] = 8;
            tail[a + 15] = 3;
            put(tail, a + 16, newIndex);
          }
        }
      }
      p += size;
    }
    // Strings referenced by resources or non-attribute nodes also require retargeting for game
    // APKs.
    if (!preserveClasses)
      for (int i = 0; i < count; i++)
        strings.set(i, strings.get(i).replace(oldPackage, newPackage));
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    int[] offsets = new int[strings.size()];
    for (int i = 0; i < strings.size(); i++) {
      offsets[i] = data.size();
      String s = strings.get(i);
      byte[] bytes = s.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
      writeLength(data, s.length(), utf8);
      if (utf8) writeLength(data, bytes.length, true);
      data.write(bytes, 0, bytes.length);
      data.write(0);
      if (!utf8) data.write(0);
    }
    while (data.size() % 4 != 0) data.write(0);
    int dataStart = 28 + 4 * strings.size(), newSize = dataStart + data.size();
    byte[] newPool = new byte[newSize];
    newPool[0] = 1;
    newPool[2] = 28;
    put(newPool, 4, newSize);
    put(newPool, 8, strings.size());
    put(newPool, 16, flags & ~1);
    put(newPool, 20, dataStart);
    for (int i = 0; i < offsets.length; i++) put(newPool, 28 + 4 * i, offsets[i]);
    System.arraycopy(data.toByteArray(), 0, newPool, dataStart, data.size());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(input, 0, pool);
    output.write(newPool, 0, newPool.length);
    output.write(tail, 0, tail.length);
    byte[] result = output.toByteArray();
    put(result, 4, result.length);
    return result;
  }
}
