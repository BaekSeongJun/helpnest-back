// @owner PMJ
package com.helpnest.domain.ticket.repository;

/**
 * 회원 1명의 표시 이름 ({@link MemberNameLookupRepository} 전용 읽기 프로젝션).
 * record 가 아니라 인터페이스인 것은 네이티브 쿼리 결과를 Spring Data 가 프로젝션으로
 * 직접 매핑하게 하기 위해서다.
 */
public interface MemberName {

    Long getMemberId();

    String getName();
}
