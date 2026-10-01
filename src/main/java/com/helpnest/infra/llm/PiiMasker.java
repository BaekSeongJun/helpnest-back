// @owner SSJ
package com.helpnest.infra.llm;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM 전송 전 개인정보 마스킹 (docs/05 §2) */
public final class PiiMasker {

    // 010-1234-5678, 01012345678, 010 1234 5678 → 010-****-5678
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(01[016789])[-\\s]?\\d{3,4}[-\\s]?(\\d{4})(?!\\d)");
    // hong@example.com → h***@example.com
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    // 카드·계좌: 숫자 10~19자리(하이픈·공백 구분 허용) → 마지막 4자리만 남김. 전화번호 처리 뒤 적용
    private static final Pattern NUMBER_SEQ = Pattern.compile("(?<![\\d*])\\d(?:[-\\s]?\\d){9,18}(?!\\d)");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = PHONE.matcher(text).replaceAll("$1-****-$2");
        masked = EMAIL.matcher(masked).replaceAll("$1***$2");
        return NUMBER_SEQ.matcher(masked).replaceAll(PiiMasker::maskDigits);
    }

    private static String maskDigits(MatchResult m) {
        String digits = m.group().replaceAll("\\D", "");
        return Matcher.quoteReplacement("****-" + digits.substring(digits.length() - 4));
    }
}
