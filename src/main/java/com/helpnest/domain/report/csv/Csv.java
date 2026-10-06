// @owner SSJ
package com.helpnest.domain.report.csv;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 리포트·상담원 처리현황 CSV (FR-RPT-02). 엑셀이 한글을 UTF-8 로 읽도록 BOM 을 붙이고 줄바꿈은 CRLF.
 * 문자열 셀이 = + - @ 탭 CR 로 시작하면 ' 를 붙여 엑셀 수식 실행을 막는다(CSV injection). 숫자는 그대로.
 */
public final class Csv {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    /** 엑셀 사용자용 유형 라벨 (프론트 config/badge.ts CATEGORY_LABEL 과 같은 값) */
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "DELIVERY", "배송", "REFUND", "환불", "EXCHANGE", "교환", "PAYMENT", "결제",
            "ACCOUNT", "계정", "SERVICE_ERROR", "서비스 오류", "ETC", "기타");

    private Csv() {
    }

    /** 모르는 값은 원문 그대로 */
    public static String categoryLabel(String category) {
        return CATEGORY_LABEL.getOrDefault(category, category);
    }

    public static byte[] write(List<String> header, List<List<?>> rows) {
        StringBuilder sb = new StringBuilder();
        line(sb, header);
        rows.forEach(row -> line(sb, row));
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    public static ResponseEntity<byte[]> download(String filename, byte[] csv) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(TEXT_CSV)
                .body(csv);
    }

    private static void line(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
