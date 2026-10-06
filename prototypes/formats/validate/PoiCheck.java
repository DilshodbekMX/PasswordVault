import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Apache POI as a third, independent reader: java PoiCheck FILE OUT.json [PASSWORD] */
public class PoiCheck {
    public static void main(String[] a) throws Exception {
        long t = System.nanoTime();
        InputStream in;
        if (a.length > 2) {
            POIFSFileSystem fs = new POIFSFileSystem(new File(a[0]), true);
            EncryptionInfo info = new EncryptionInfo(fs);
            Decryptor d = Decryptor.getInstance(info);
            System.out.println("POI: encryption mode " + info.getEncryptionMode() + ", spin " + info.getVerifier().getSpinCount()
                + ", cipher " + info.getHeader().getCipherAlgorithm() + " " + info.getHeader().getKeySize() + ", hash " + info.getHeader().getHashAlgorithm());
            if (!d.verifyPassword(a[2])) { System.out.println("POI: password refused"); write(a[1], "{\"loaded\": false, \"error\": \"password refused\"}"); return; }
            in = d.getDataStream(fs);
        } else in = new FileInputStream(a[0]);
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet s = wb.getSheetAt(0);
            StringBuilder sb = new StringBuilder("{\"loaded\": true, \"seconds\": 0, \"sheet\": ").append(q(s.getSheetName())).append(", \"rows\": [");
            int lastCol = s.getRow(0).getLastCellNum();
            for (int r = 0; r <= s.getLastRowNum(); r++) {
                Row row = s.getRow(r);
                sb.append(r == 0 ? "[" : ",[");
                for (int c = 0; c < lastCol; c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    String v;
                    if (cell == null) v = "";
                    else if (cell.getCellType() == CellType.FORMULA) v = "formula:" + cell.getCellFormula();
                    else if (cell.getCellType() == CellType.NUMERIC) v = "num:" + cell.getNumericCellValue();
                    else v = cell.getStringCellValue();
                    sb.append(c == 0 ? "" : ",").append(q(v));
                }
                sb.append("]");
            }
            sb.append("]}");
            write(a[1], sb.toString());
            System.out.println("POI: read " + (s.getLastRowNum() + 1) + " rows in " + (System.nanoTime() - t) / 1_000_000 + " ms; frozen pane "
                + s.getPaneInformation().getHorizontalSplitPosition() + ", print titles " + s.getRepeatingRows());
        }
    }

    static void write(String path, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) { w.write(s); }
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20 || Character.isSurrogate(ch) || ch == 0xFFFE) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
