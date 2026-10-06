// @owner BSJ
package com.helpnest.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.helpnest.domain.member.repository.MemberRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 역할 × 엔드포인트 접근 매트릭스 (docs/04 권한 열 ↔ SecurityConfig·컨트롤러).
 *
 * <p>허용 역할은 401·403 이 아니면 통과(없는 id·빈 본문이라 404·400 이 나와도 권한은 통과한 것),
 * 금지 역할은 비로그인 401 · 로그인했으나 역할 부족 403 이어야 한다. 존재하지 않는 id 로만 쳐서
 * 데이터가 바뀌지 않는다. 채팅·고객 이력은 아직 dev 에 없어 구현되면 행을 추가한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("역할별 접근 매트릭스")
class RoleAccessMatrixTest {

    /** ANONYMOUS = 토큰 없음, GUEST = 비회원 조회 토큰(티켓 1건짜리) */
    enum Who { ANONYMOUS, GUEST, CUSTOMER, AGENT, LEAD, ADMIN }

    private static final long NO_SUCH_ID = 999_999_999L;

    private static final Set<Who> ALL = EnumSet.allOf(Who.class);
    /** 로그인한 회원 (Guest 토큰 제외) */
    private static final Set<Who> MEMBERS = EnumSet.of(Who.CUSTOMER, Who.AGENT, Who.LEAD, Who.ADMIN);
    private static final Set<Who> AGENT_UP = EnumSet.of(Who.AGENT, Who.LEAD, Who.ADMIN);
    private static final Set<Who> LEAD_UP = EnumSet.of(Who.LEAD, Who.ADMIN);
    private static final Set<Who> ADMIN_ONLY = EnumSet.of(Who.ADMIN);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    MemberRepository memberRepository;

    /** 04 권한 열 그대로. 경로 뒤 {@code #}는 설명용이라 요청에 쓰지 않는다 */
    static Stream<Arguments> matrix() {
        String t = "/api/console/tickets/" + NO_SUCH_ID;
        return Stream.of(
                // 공개
                row("GET", "/api/faqs", ALL),
                row("GET", "/api/surveys/no-such-token", ALL),
                row("POST", "/api/tickets", "{}", ALL),
                // 로그인 회원 (Guest 토큰은 티켓 1건 전용이라 회원 API 는 막힌다)
                row("GET", "/api/members/me", MEMBERS),
                row("GET", "/api/notifications", MEMBERS),
                row("GET", "/api/notifications/unread-count", MEMBERS),
                row("PATCH", "/api/notifications/" + NO_SUCH_ID + "/read", MEMBERS),
                row("PATCH", "/api/notifications/read-all", MEMBERS),
                row("GET", "/api/tickets/my", EnumSet.of(Who.CUSTOMER)),   // 04 §7 고객 전용 (CR #75)
                // 상담원 이상
                row("GET", "/api/console/agents", AGENT_UP),
                row("GET", "/api/templates", AGENT_UP),
                row("GET", "/api/console/tickets", AGENT_UP),
                row("GET", t, AGENT_UP),
                row("GET", t + "/histories", AGENT_UP),
                row("GET", t + "/ai/drafts", AGENT_UP),
                row("GET", "/api/console/surveys", AGENT_UP),
                row("GET", "/api/console/surveys/summary", AGENT_UP),
                row("GET", "/api/dashboard/agents/me", AGENT_UP),
                // 팀장 이상
                row("PATCH", t + "/assign", "{\"agentId\":1}", LEAD_UP),
                row("POST", t + "/assign/auto", "{}", LEAD_UP),
                row("GET", "/api/dashboard/summary", LEAD_UP),
                row("GET", "/api/dashboard/agents", LEAD_UP),
                row("GET", "/api/dashboard/agents/export", LEAD_UP),
                row("GET", "/api/reports/monthly", LEAD_UP),
                row("GET", "/api/reports/monthly/export", LEAD_UP),
                row("GET", "/api/admin/faqs", LEAD_UP),
                row("GET", "/api/admin/templates", LEAD_UP),
                row("GET", "/api/admin/sla-policies", LEAD_UP),
                // 관리자 전용
                row("GET", "/api/admin/members", ADMIN_ONLY),
                // 이 행만 실제로 쓰기가 된다(없는 id 를 쓸 수 없는 고정 4행 테이블). 본문을
                // 마이그레이션 기본값(60분·0.80)으로 두어 ADMIN 행이 통과해도 값은 그대로다
                row("PUT", "/api/admin/sla-policies/URGENT", "{\"responseMinutes\":60,\"warningRatio\":0.80}",
                        ADMIN_ONLY))
                .flatMap(Function.identity());
    }

    private static Stream<Arguments> row(String method, String path, Set<Who> allowed) {
        return row(method, path, null, allowed);
    }

    /** 한 엔드포인트를 역할 6종으로 펼친다 */
    private static Stream<Arguments> row(String method, String path, String body, Set<Who> allowed) {
        return EnumSet.allOf(Who.class).stream()
                .map(who -> Arguments.of(who, method, path, body, allowed.contains(who)));
    }

    @ParameterizedTest(name = "{0} {1} {2} → 허용={4}")
    @MethodSource("matrix")
    void access(Who who, String method, String path, String body, boolean allowed) throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), path);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (who != Who.ANONYMOUS) {
            req.header("Authorization", "Bearer " + tokenOf(who));
        }

        int status = mockMvc.perform(req).andReturn().getResponse().getStatus();

        if (allowed) {
            assertThat(status).as("허용 역할인데 막힘").isNotIn(401, 403);
        } else {
            assertThat(status).as("금지 역할인데 통과").isEqualTo(who == Who.ANONYMOUS ? 401 : 403);
        }
    }

    private String tokenOf(Who who) {
        return switch (who) {
            case GUEST -> jwtProvider.createGuestToken(NO_SUCH_ID);
            case CUSTOMER -> memberToken("customer1@helpnest.local", "CUSTOMER");
            case AGENT -> memberToken("agent1@helpnest.local", "AGENT");
            case LEAD -> memberToken("lead@helpnest.local", "LEAD");
            case ADMIN -> memberToken("admin@helpnest.local", "ADMIN");
            case ANONYMOUS -> throw new IllegalArgumentException("토큰 없음");
        };
    }

    private String memberToken(String email, String role) {
        return jwtProvider.createAccessToken(memberRepository.findByEmail(email).orElseThrow().getId(), role);
    }
}
