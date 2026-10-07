// @owner SSJ
package com.helpnest.infra.mail;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.web.util.HtmlUtils;

/**
 * 메일 HTML 템플릿 4종 (docs/05 §5·§5.1). 사용자 값은 모두 이스케이프한다.
 * ponytail: Java text block 렌더링, 템플릿이 늘거나 디자이너가 편집해야 하면 Thymeleaf(CR) 도입
 */
public final class MailTemplates {

    // 메일 클라이언트는 CSS 변수를 못 읽음 → 디자인 토큰 --primary(oklch 0.546 0.215 262) 근사 hex (front #43)
    // ponytail: 로고는 텍스트 헤더 유지, SVG 미지원 클라이언트가 있어 PNG 공개 URL(S3) 확정 후 <img> 추가
    static final String PRIMARY = "#2563eb";

    static final int REPLY_PREVIEW_MAX = 200;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private MailTemplates() {
    }

    public static MailContent resolved(ResolvedMailCommand cmd, String summary) {
        String body = """
                <p>%s님, 문의하신 내용이 해결되었습니다.</p>
                <p><strong>문의 제목</strong><br>%s</p>
                <p><strong>답변 요약</strong><br>%s</p>
                <p>서비스 개선을 위해 간단한 설문에 참여해 주세요.<br>설문 만료: %s</p>
                """.formatted(esc(cmd.customerName()), esc(cmd.ticketTitle()), esc(summary),
                formatTime(cmd.surveyExpiresAt()));
        return new MailContent("[HelpNest] 문의(%s)가 해결되었습니다".formatted(cmd.ticketNo()),
                layout(body, "설문 참여하기", cmd.surveyUrl()));
    }

    public static MailContent agentReply(AgentReplyMailCommand cmd) {
        String body = """
                <p>문의(%s)에 상담원 답변이 등록되었습니다.</p>
                <p style="padding:12px;background:#f4f4f5;border-radius:6px;white-space:pre-line">%s</p>
                """.formatted(esc(cmd.ticketNo()), esc(preview(cmd.replyPreview())));
        return new MailContent("[HelpNest] 문의(%s)에 답변이 등록되었습니다".formatted(cmd.ticketNo()),
                layout(body, "문의 보기", cmd.ticketUrl()));
    }

    /** PASSWORD_RESET·GUEST_PASSWORD_RESET 공용, 문구만 분기 */
    public static MailContent passwordReset(PasswordResetMailCommand cmd) {
        String target = cmd.guest() ? "문의 조회 비밀번호" : "비밀번호";
        String body = """
                <p>%s 재설정을 요청하셨습니다.</p>
                <p>아래 버튼을 눌러 새 %s를 설정해 주세요. 링크는 <strong>30분</strong> 동안 유효합니다.</p>
                <p style="color:#71717a">요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.</p>
                """.formatted(target, target);
        return new MailContent("[HelpNest] %s 재설정 안내".formatted(target),
                layout(body, "%s 재설정".formatted(target), cmd.resetUrl()));
    }

    // 공통 레이아웃: 헤더 HelpNest + 본문 + 버튼 1개
    private static String layout(String bodyHtml, String buttonLabel, String url) {
        return """
                <!doctype html>
                <html lang="ko"><body style="margin:0;padding:24px;background:#f4f4f5;font-family:sans-serif;color:#18181b">
                <div style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:8px;overflow:hidden">
                <div style="padding:16px 24px;background:%1$s;color:#ffffff;font-size:18px;font-weight:bold">HelpNest</div>
                <div style="padding:24px;font-size:14px;line-height:1.6">
                %2$s
                <p style="margin-top:24px"><a href="%3$s" style="display:inline-block;padding:10px 20px;background:%1$s;color:#ffffff;text-decoration:none;border-radius:6px">%4$s</a></p>
                </div></div></body></html>
                """.formatted(PRIMARY, bodyHtml, esc(url), buttonLabel);
    }

    static String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= REPLY_PREVIEW_MAX ? text : text.substring(0, REPLY_PREVIEW_MAX) + "…";
    }

    private static String formatTime(OffsetDateTime time) {
        return time == null ? "-" : time.atZoneSameInstant(SEOUL).format(TIME);
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
