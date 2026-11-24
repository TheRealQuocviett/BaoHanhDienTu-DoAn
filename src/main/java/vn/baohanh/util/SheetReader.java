package vn.baohanh.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Đọc bảng dữ liệu từ file Excel .xlsx (trang tính đầu tiên) hoặc .csv mà không cần thư viện ngoài.
 * Kết quả là danh sách dòng, mỗi dòng là danh sách ô dạng chữ (ô trống = "").
 */
public final class SheetReader {
    private SheetReader() {}

    public static final int MAX_ROWS = 5000;

    public static List<List<String>> read(byte[] data, String fileName) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("File rỗng");
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (zip) return readXlsx(data);
        if (name.endsWith(".xls")) throw new IllegalArgumentException("File .xls (Excel 97-2003) chưa hỗ trợ – mở bằng Excel rồi lưu lại dạng .xlsx");
        return readCsv(new String(data, StandardCharsets.UTF_8));
    }

    // ===================== XLSX =====================

    private static List<List<String>> readXlsx(byte[] data) {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            long total = 0;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (!(n.startsWith("xl/") && n.endsWith(".xml") || n.endsWith(".rels"))) continue;
                byte[] b = readLimited(zin, 50_000_000 - total);
                total += b.length;
                files.put(n, b);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Không đọc được file Excel: " + ex.getMessage());
        }
        if (!files.containsKey("xl/workbook.xml")) throw new IllegalArgumentException("File không phải Excel .xlsx hợp lệ");
        try {
            DocumentBuilder b = builder();
            // Trang tính đầu tiên theo thứ tự trong workbook
            String sheetPath = "xl/worksheets/sheet1.xml";
            Document wb = b.parse(new ByteArrayInputStream(files.get("xl/workbook.xml")));
            NodeList sheets = wb.getElementsByTagNameNS("*", "sheet");
            if (sheets.getLength() > 0 && files.containsKey("xl/_rels/workbook.xml.rels")) {
                String rid = ((Element) sheets.item(0)).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                Document rels = b.parse(new ByteArrayInputStream(files.get("xl/_rels/workbook.xml.rels")));
                NodeList rs = rels.getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < rs.getLength(); i++) {
                    Element r = (Element) rs.item(i);
                    if (rid.equals(r.getAttribute("Id"))) {
                        String t = r.getAttribute("Target");
                        sheetPath = t.startsWith("/") ? t.substring(1) : "xl/" + t;
                    }
                }
            }
            byte[] sheet = files.get(sheetPath);
            if (sheet == null) throw new IllegalArgumentException("Không tìm thấy trang tính trong file");

            List<String> shared = new ArrayList<>();
            if (files.containsKey("xl/sharedStrings.xml")) {
                Document ss = b.parse(new ByteArrayInputStream(files.get("xl/sharedStrings.xml")));
                NodeList sis = ss.getElementsByTagNameNS("*", "si");
                for (int i = 0; i < sis.getLength(); i++) shared.add(text((Element) sis.item(i)));
            }

            Document doc = b.parse(new ByteArrayInputStream(sheet));
            NodeList rows = doc.getElementsByTagNameNS("*", "row");
            List<List<String>> out = new ArrayList<>();
            for (int i = 0; i < rows.getLength() && out.size() < MAX_ROWS + 1; i++) {
                Element row = (Element) rows.item(i);
                int rowNum = parseInt(row.getAttribute("r"), out.size() + 1);
                while (out.size() < rowNum - 1) out.add(new ArrayList<>());   // dòng trống bị bỏ qua trong XML
                List<String> line = new ArrayList<>();
                NodeList cells = row.getElementsByTagNameNS("*", "c");
                for (int j = 0; j < cells.getLength(); j++) {
                    Element c = (Element) cells.item(j);
                    int col = colIndex(c.getAttribute("r"), line.size());
                    while (line.size() < col) line.add("");
                    line.add(cellValue(c, shared));
                }
                out.add(line);
            }
            return out;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("File Excel bị lỗi định dạng: " + ex.getMessage());
        }
    }

    private static String cellValue(Element c, List<String> shared) {
        String t = c.getAttribute("t");
        if ("inlineStr".equals(t)) {
            NodeList is = c.getElementsByTagNameNS("*", "is");
            return is.getLength() == 0 ? "" : text((Element) is.item(0)).trim();
        }
        NodeList vs = c.getElementsByTagNameNS("*", "v");
        String v = vs.getLength() == 0 ? "" : vs.item(0).getTextContent();
        if ("s".equals(t)) {
            int k = parseInt(v, -1);
            return k >= 0 && k < shared.size() ? shared.get(k).trim() : "";
        }
        if ("b".equals(t)) return "1".equals(v) ? "TRUE" : "FALSE";
        if ("str".equals(t) || "e".equals(t)) return v.trim();
        if (v.isEmpty()) return "";
        try {   // số: bỏ phần thập phân thừa, không dùng dạng 1.2E7
            return new BigDecimal(v.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v.trim();
        }
    }

    /** Ghép các đoạn <t> (bỏ phần phiên âm <rPh>). */
    private static String text(Element si) {
        StringBuilder sb = new StringBuilder();
        NodeList ts = si.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < ts.getLength(); i++) {
            Node p = ts.item(i).getParentNode();
            if (p != null && "rPh".equals(p.getLocalName())) continue;
            sb.append(ts.item(i).getTextContent());
        }
        return sb.toString();
    }

    private static int colIndex(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) return fallback;
        int n = 0;
        for (char ch : ref.toCharArray()) {
            if (ch < 'A' || ch > 'Z') break;
            n = n * 26 + (ch - 'A' + 1);
        }
        return n == 0 ? fallback : n - 1;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > limit) throw new IOException("file quá lớn");
        }
        return bos.toByteArray();
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // chống tấn công XXE
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    // ===================== CSV =====================

    static List<List<String>> readCsv(String s) {
        if (s.startsWith("﻿")) s = s.substring(1);
        String first = s.lines().findFirst().orElse("");
        char sep = count(first, ';') > count(first, ',') ? ';' : count(first, '\t') > count(first, ',') ? '\t' : ',';
        List<List<String>> out = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"' && cell.isEmpty()) quoted = true;
            else if (ch == sep) { line.add(cell.toString().trim()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                line.add(cell.toString().trim()); cell.setLength(0);
                out.add(line); line = new ArrayList<>();
                if (out.size() > MAX_ROWS) break;
            } else cell.append(ch);
        }
        if (!cell.isEmpty() || !line.isEmpty()) { line.add(cell.toString().trim()); out.add(line); }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) if (x == c) n++;
        return n;
    }
}
