// @owner BSJ
package com.helpnest.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @Scheduled} 활성화 (고아 첨부 정리, SLA 감시 등). 스케줄러 풀은 Boot 자동 구성 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
