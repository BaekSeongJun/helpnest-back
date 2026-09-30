// @owner BSJ
package com.helpnest.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 jwt.* */
@ConfigurationProperties("jwt")
public record JwtProperties(String secret, Duration accessTtl) {
}
