import java.nio.file.*; import java.security.*; import javax.crypto.*; import javax.crypto.spec.*; import java.util.*;
public class Chk { public static void main(String[] a) throws Exception {
  byte[] vk = new byte[32]; for (int i=0;i<32;i++) vk[i]=(byte)(0xa0+i);
  for (String f : a) { byte[] b = Files.readAllBytes(Path.of(f));
    int hl = ((b[8]&255)<<24)|((b[9]&255)<<16)|((b[10]&255)<<8)|(b[11]&255);
    MessageDigest md = MessageDigest.getInstance("SHA-256"); md.update(b,0,b.length-32);
    boolean trailer = Arrays.equals(md.digest(), Arrays.copyOfRange(b,b.length-32,b.length));
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(vk,"AES"), new GCMParameterSpec(128, Arrays.copyOfRange(b,12+hl,24+hl)));
    c.updateAAD(b,0,12+hl);
    byte[] pt = c.doFinal(b,24+hl,b.length-32-24-hl);
    int n = pt.length; while (n>0 && pt[n-1]==0x20) n--;
    byte[] body = Arrays.copyOf(pt,n);
    System.out.println(f+" len="+b.length+" file-sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))+" trailerOk="+trailer+" headerLen="+hl+" plain="+pt.length+" body="+n+" body-sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
  } } }
