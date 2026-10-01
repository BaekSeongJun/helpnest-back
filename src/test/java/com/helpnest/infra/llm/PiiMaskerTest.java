// @owner SSJ
package com.helpnest.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PiiMaskerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "연락처 010-1234-5678 입니다|연락처 010-****-5678 입니다",
            "01012345678로 연락|010-****-5678로 연락",
            "011 123 4567|011-****-4567",
            "메일 hong.gil@example.com 으로|메일 h***@example.com 으로",
            "카드 1234-5678-9012-3456 결제|카드 ****-3456 결제",
            "계좌 110123456789 입금|계좌 ****-6789 입금",
            "계좌 123-456789-01-234|****-1234",
    })
    @DisplayName("전화·이메일·카드·계좌 마스킹")
    void masks(String input, String expected) {
        assertThat(PiiMasker.mask(input)).contains(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "주문번호 20261001 배송 문의",
            "10/1 주문, 금액 35,000원",
            "{중괄호} 포함 본문",
    })
    @DisplayName("개인정보가 아닌 숫자·문자는 그대로")
    void keeps(String input) {
        assertThat(PiiMasker.mask(input)).isEqualTo(input);
    }
}
