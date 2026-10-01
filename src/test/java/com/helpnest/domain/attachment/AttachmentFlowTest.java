// @owner BSJ
package com.helpnest.domain.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.attachment.port.AttachmentPort;
import com.helpnest.domain.attachment.repository.AttachmentRepository;
import com.helpnest.domain.attachment.service.OrphanAttachmentCleaner;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 업로드 검증 → 티켓 연결 → 역할별 다운로드 권한 (FR-INQ-02, docs/04 §3) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttachmentFlowTest {

    @TempDir
    static Path uploadDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.local.dir", uploadDir::toString);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    AttachmentPort attachmentPort;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    AttachmentRepository attachmentRepository;
    @Autowired
    OrphanAttachmentCleaner orphanAttachmentCleaner;

    Member customer;
    Member otherCustomer;
    Member agent;

    @BeforeEach
    void setUp() {
        customer = saveMember("att-customer@helpnest.local", MemberRole.CUSTOMER);
        otherCustomer = saveMember("att-other@helpnest.local", MemberRole.CUSTOMER);
        agent = saveMember("att-agent@helpnest.local", MemberRole.AGENT);
    }

    @Test
    @DisplayName("비회원 업로드: 형식·개수 검증, 성공 시 201 + 원래 파일명")
    void guestUploadValidation() throws Exception {
        mockMvc.perform(multipart("/api/attachments").file(file("악성.exe")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_INVALID_TYPE"));

        var six = multipart("/api/attachments");
        for (int i = 0; i < 6; i++) {
            six.file(file("a" + i + ".png"));
        }
        mockMvc.perform(six)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_TOO_MANY"));

        mockMvc.perform(multipart("/api/attachments").file(file("사진.PNG")).file(file("문서.pdf")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].originalName").value("사진.PNG"))
                .andExpect(jsonPath("$.data[1].size").value(5));
    }

    @Test
    @DisplayName("연결: 본인 첨부만 1회, 남의 첨부·이미 연결된 첨부는 거부")
    void linkToTicket() throws Exception {
        Long mine = upload(bearer(customer));
        Long others = upload(bearer(otherCustomer));
        Long ticketId = saveTicket(customer.getId()).getId();

        // 실제로는 예외가 호출자 트랜잭션을 롤백하지만, 테스트는 한 트랜잭션이라 섞지 않고 남의 첨부만 시도
        assertThatThrownBy(() -> attachmentPort.linkToTicket(List.of(others), ticketId, null))
                .isInstanceOf(BusinessException.class);
        attachmentPort.linkToTicket(List.of(mine), ticketId, null);
        assertThatThrownBy(() -> attachmentPort.linkToTicket(List.of(mine), ticketId, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다운로드: 티켓 고객·상담원·같은 티켓 Guest 만 허용, 한글 파일명 RFC 5987")
    void downloadPermission() throws Exception {
        Long id = upload(bearer(customer));
        Long ticketId = saveTicket(customer.getId()).getId();
        attachmentPort.linkToTicket(List.of(id), ticketId, null);
        String url = "/api/attachments/" + id + "/download";

        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''%EC%82%AC%EC%A7%84.PNG")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(content().string("hello"));
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(agent)))
                .andExpect(status().isOk());
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, guestBearer(ticketId)))
                .andExpect(status().isOk());

        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(otherCustomer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, guestBearer(ticketId + 1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(url))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ID 는 추측 불가 난수 [2^52, 2^53) — JS 안전 정수 범위")
    void randomId() throws Exception {
        Long id = upload(bearer(customer));

        assertThat(id).isBetween(1L << 52, (1L << 53) - 1);
    }

    @Test
    @DisplayName("고아 정리: 미연결 첨부만 행·파일 삭제, 연결된 첨부는 유지")
    void orphanCleanup() throws Exception {
        Long orphan = upload(bearer(customer));
        Long linked = upload(bearer(customer));
        attachmentPort.linkToTicket(List.of(linked), saveTicket(customer.getId()).getId(), null);
        String orphanKey = attachmentRepository.findById(orphan).orElseThrow().getStoredKey();

        int deleted = orphanAttachmentCleaner.cleanup(OffsetDateTime.now().plusMinutes(1));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(attachmentRepository.existsById(orphan)).isFalse();
        assertThat(attachmentRepository.existsById(linked)).isTrue();
        assertThat(uploadDir.resolve(orphanKey)).doesNotExist();
    }

    private Long upload(String bearer) throws Exception {
        String body = mockMvc.perform(multipart("/api/attachments").file(file("사진.PNG"))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data[0].attachmentId")).longValue();
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("files", name, "application/octet-stream", "hello".getBytes());
    }

    private Member saveMember(String email, MemberRole role) {
        return memberRepository.save(Member.builder()
                .email(email).password("x").name("테스트").role(role).build());
    }

    private Ticket saveTicket(Long customerId) {
        return ticketRepository.save(Ticket.builder()
                .ticketNo("HN-T-" + System.nanoTime() % 1_000_000_000L)
                .customerId(customerId).title("제목").content("본문").channel(TicketChannel.WEB)
                .firstResponseDueAt(OffsetDateTime.now().plusHours(4)).build());
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    }

    // S1-5 GuestTicketToken 과 같은 형식 (role=GUEST, ticketId 클레임)
    private String guestBearer(Long ticketId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("guest:" + ticketId)
                .claim(JwtProvider.ROLE_CLAIM, JwtProvider.GUEST_ROLE)
                .claim(JwtProvider.TICKET_ID_CLAIM, ticketId)
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
