// @owner BSJ
package com.helpnest.domain.auth.oauth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberSocialRepository extends JpaRepository<MemberSocial, Long> {

    Optional<MemberSocial> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
