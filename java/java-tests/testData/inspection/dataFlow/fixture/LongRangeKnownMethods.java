import java.time.LocalDateTime;

public class LongRangeKnownMethods {
  void testIndexOf(String s) {
    int idx = s.indexOf("xyz");
    if(idx >= 0) {
      System.out.println("Found");
    } else if(<warning descr="Condition 'idx == -1' is always 'true'">idx == -1</warning>) {
      System.out.println("Not found");
    }
  }

  void testLocalDateTime(LocalDateTime ldt) {
    if(<warning descr="Condition 'ldt.getHour() == 24' is always 'false'">ldt.getHour() == 24</warning>) System.out.println(1);
    if(<warning descr="Condition 'ldt.getMinute() >= 0' is always 'true'">ldt.getMinute() >= 0</warning>) System.out.println(2);
    if(<warning descr="Condition 'ldt.getSecond() >= 60' is always 'false'">ldt.getSecond() >= 60</warning>) System.out.println(3);
  }

  private static int twiceIndexOf(String text, int start, int end) {
    int paragraphStart = text.lastIndexOf("\n\n", start);
    int paragraphEnd = text.indexOf("\n\n", end);
    if (paragraphStart >= paragraphEnd) {
      return text.length();
    }
    return (paragraphStart >= 0 ? paragraphStart + 2 : 0) +
           (<warning descr="Condition 'paragraphEnd < 0' is always 'false' when reached">paragraphEnd < 0</warning> ? text.length() : paragraphEnd);
  }

  void test(String s) {
    if (<warning descr="Condition 's.isEmpty() && s.length() > 2' is always 'false'">s.isEmpty() && <warning descr="Condition 's.length() > 2' is always 'false' when reached">s.length() > 2</warning></warning>) {
      System.out.println("Never");
    }
  }

  void test2(String s) {
    if (s.isEmpty() && <warning descr="Condition 's.length() == 0' is always 'true' when reached">s.length() == 0</warning>) {
      System.out.println("Ok");
    }
  }

  void test3(String s) {
    if (<warning descr="Condition 's.startsWith(\"xyz\") && s.length() < 3' is always 'false'">s.startsWith("xyz") && <warning descr="Condition 's.length() < 3' is always 'false' when reached">s.length() < 3</warning></warning>) {
      System.out.println("Impossible");
    }
  }

  void testEmpty(String s) {
    if (<warning descr="Condition 's.isEmpty() && s.startsWith(\"xyz\")' is always 'false'">s.isEmpty() && <warning descr="Condition 's.startsWith(\"xyz\")' is always 'false' when reached">s.startsWith("xyz")</warning></warning>) {
      System.out.println("Impossible");
    }
  }

  void testOk(String s) {
    if (s.length() <= 3 && s.endsWith("xyz")) {
      System.out.println("Possible");
    }
  }

  void testInterfere(String s, String s2) {
    if (!s.isEmpty() && s2.startsWith(s)) {
      if(<warning descr="Condition 's2.isEmpty()' is always 'false'">s2.isEmpty()</warning>) {
        System.out.println();
      }
    }
  }

  void test4() {
    String s = "abcd";
    if (<warning descr="Condition 's.startsWith(\"efg\")' is always 'false'">s.startsWith("efg")</warning>) {
      System.out.println("Impossible");
    }
  }

  void testEquals(boolean b, boolean c) {
    String s1 = b ? "x" : "y";
    String s2 = c ? "x" : "b";
    if(s1.equals(s2) && <warning descr="Condition 'b' is always 'true' when reached">b</warning>) {
      System.out.println("B is always true");
    }
  }

  void testEqualsIgnoreCase(String s) {
    if(!s.equalsIgnoreCase("xyz") || !s.isEmpty()) {
      System.out.println("Always");
    }
  }

  void testIndexOfUpperBound(String s) {
    int idx = "abcdefgh".indexOf(s);
    if(<warning descr="Condition 'idx > 8' is always 'false'">idx > 8</warning>) {
      System.out.println("Impossible");
    }
  }
}
