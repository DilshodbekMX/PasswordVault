import java.util.*;
public class Dig { public static void main(String[] a) {
  for (String t : new String[]{"tr-TR","fa-IR","ar-SA","ar-EG","th-TH-u-nu-thai","uz-Cyrl-UZ","ru-RU"}) {
    Locale l = Locale.forLanguageTag(t); Locale.setDefault(l);
    System.out.println(t+" "+String.format("%d|%010d", 1234567890L, 42L)+" | "+String.format(Locale.ROOT,"%010d",42L)+" | "+Long.toString(42L)+" | "+"I".toLowerCase());
  }}}
