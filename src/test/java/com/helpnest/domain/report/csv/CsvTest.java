// @owner SSJ
package com.helpnest.domain.report.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    @DisplayName("BOM + CRLF, null 은 빈 칸")
    void bomAndLines() {
        byte[] csv = Csv.write(List.of("이름", "값"), List.of(Arrays.asList("가", null)));

        assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8)).isEqualTo("이름,값\r\n가,\r\n");
    }

    @Test
    @DisplayName("쉼표·따옴표·개행은 따옴표로 감싸고 \" 는 두 번")
    void escapes() {
        assertThat(Csv.cell("홍,길\"동")).isEqualTo("\"홍,길\"\"동\"");
        assertThat(Csv.cell("줄\n바꿈")).isEqualTo("\"줄\n바꿈\"");
        assertThat(Csv.cell("보통")).isEqualTo("보통");
    }

    @Test
    @DisplayName("수식 주입: = + - @ 로 시작하는 문자열에 ' 접두, 숫자는 그대로")
    void formulaInjection() {
        assertThat(Csv.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(Csv.cell("@cmd")).isEqualTo("'@cmd");
        assertThat(Csv.cell("+1")).isEqualTo("'+1");
        assertThat(Csv.cell("-2,3")).isEqualTo("\"'-2,3\"");
        assertThat(Csv.cell(-5.5)).isEqualTo("-5.5");
        assertThat(Csv.cell(12L)).isEqualTo("12");
    }

    @Test
    @DisplayName("유형 라벨: 아는 값은 한글, 모르는 값은 원문")
    void categoryLabel() {
        assertThat(Csv.categoryLabel("SERVICE_ERROR")).isEqualTo("서비스 오류");
        assertThat(Csv.categoryLabel("NEW_TYPE")).isEqualTo("NEW_TYPE");
    }
}
