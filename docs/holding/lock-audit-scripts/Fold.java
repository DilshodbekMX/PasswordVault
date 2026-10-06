import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
public class Fold {
  static final Set<Integer> APOS = new HashSet<>(Arrays.asList(0x27,0x60,0xB4,0x2018,0x2019,0x201B,0x2032,0x2035,0x02B9,0x02BB,0x02BC,0x02BD,0x02BE,0x02BF,0xA78B,0xA78C,0xFF07));
  static boolean ws(int c){ return (c>=0x09&&c<=0x0D)||c==0x20||c==0x85||c==0xA0||c==0x1680||(c>=0x2000&&c<=0x200A)||c==0x2028||c==0x2029||c==0x202F||c==0x205F||c==0x3000; }
  static String bytes(String s){
    String n = Normalizer.normalize(s, Normalizer.Form.NFC);                 // 1 NFC
    StringBuilder w = new StringBuilder(); boolean lastSpace = true;         // 2 white space: fold, collapse, trim
    for (int i=0;i<n.length();){ int c=n.codePointAt(i); i+=Character.charCount(c);
      if (ws(c)) { if(!lastSpace) w.append(' '); lastSpace=true; } else { w.appendCodePoint(c); lastSpace=false; } }
    int end=w.length(); while(end>0 && w.charAt(end-1)==' ') end--; w.setLength(end);
    StringBuilder a = new StringBuilder();                                   // 3 apostrophes
    for (int i=0;i<w.length();){ int c=w.codePointAt(i); i+=Character.charCount(c); a.appendCodePoint(APOS.contains(c)?0x27:c); }
    StringBuilder h=new StringBuilder(); for(byte b: a.toString().getBytes(StandardCharsets.UTF_8)) h.append(String.format("%02x",b)); return h.toString();
  }
  public static void main(String[] x){
    System.out.println(System.getProperty("java.version"));
    String[] sp = {"oʻgʻlim-Toʻra","o‘g‘lim-To‘ra","o'g'lim-To'ra","o’gʼlim-To`ra","o´g＇lim-To′ra","oʹg`lim-To´ra"};
    for (String s: sp) System.out.println(bytes(s));
    System.out.println(bytes("Köz-Ўzbek"));
    System.out.println(bytes(" correct  horse battery staple "));
    // idempotence / NFC-stability on the three Greek signs
    for (int c: new int[]{0x374,0x1FEF,0x1FFD}) { String s=new String(Character.toChars(c)); System.out.println(Integer.toHexString(c)+" -> "+bytes(s)); }
  }
}
