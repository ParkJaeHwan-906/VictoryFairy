package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserBlock}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * 요구사항: {@code docs/requirements/user/user-block.md}(USER-BLK-19 ~ 21).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code domain} 모듈은 {@code testImplementation}으로
 * {@code spring-boot-starter-data-jpa-test}·H2·Testcontainers 중 어느 것도 갖고 있지 않고(이 모듈의
 * {@code build.gradle} 주석 참고), 로컬 {@code docker-compose.yml}의 mysql 컨테이너도 떠 있지 않다
 * (확인: {@code docker compose ps} 실행 시 "failed to connect to the docker API" — Docker 데몬 자체가
 * 기동돼 있지 않음). 따라서 {@code @DataJpaTest}로 다음 항목을 검증해 달라는 요청은 이 세션에서 실행할
 * 수 없어 보류한다({@link com.skhynix.domain.stadium.entity.StadiumTest}·
 * {@link com.skhynix.domain.quiz.entity.QuizLikeTest}·
 * {@link com.skhynix.domain.support.entity.UserSupportTeamTest}와 동일한 판단):
 * <ul>
 *   <li>USER-BLK-20 — {@code uk_user_blocks_blocker_blocked} UNIQUE(blocker_id, blocked_id) 위반 시
 *       {@code DataIntegrityViolationException} 발생</li>
 *   <li>USER-BLK-21 — blocker/blocked 계정이 하드 삭제되면 관여한 {@code UserBlock} 행도 CASCADE로
 *       함께 삭제됨</li>
 *   <li>{@code UserBlockRepository}의 세 메서드({@code existsByBlocker_IdAndBlocked_Id},
 *       {@code findRelatedAccountIds}, {@code findAllByBlocker_IdOrderByCreatedAtAsc}) — 전부 Spring
 *       Data 파생 쿼리/{@code @Query}라 자체 로직이 없고 실행하려면 영속성 컨텍스트(DB)가 필요하다.
 *       {@code findRelatedAccountIds}의 양방향 대칭성(USER-BLK-13의 전제), fetch join으로
 *       {@code blocked}가 LAZY 초기화 예외 없이 접근되는지, {@code createdAt} 오름차순 정렬도 이
 *       클래스에서 검증되지 않는다.</li>
 * </ul>
 * H2 또는 Testcontainers 도입 여부 결정이 필요하다(자세한 내용은 최종 보고 참고). 이 클래스는 DB 없이도
 * 확인 가능한 {@link UserBlock#builder()} 배선만 다룬다.
 */
class UserBlockTest {

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("password1!").build();
    }

    @Test
    @DisplayName("[USER-BLK-19 관련] Builder(blocker, blocked)로 생성하면 두 필드가 각각 동일 인스턴스로 "
            + "배선되고, 영속화 전이므로 id/createdAt은 null이다")
    void builder_wiresBlockerAndBlockedToSameInstance_andLeavesPersistenceManagedFieldsNull() {
        // given
        UserAccount blocker = newUserAccount("차단자");
        UserAccount blocked = newUserAccount("피차단자");

        // when
        UserBlock userBlock = UserBlock.builder().blocker(blocker).blocked(blocked).build();

        // then
        assertThat(userBlock.getBlocker()).isSameAs(blocker);
        assertThat(userBlock.getBlocked()).isSameAs(blocked);
        assertThat(userBlock.getId()).isNull();
        assertThat(userBlock.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("빌더는 createdAt 파라미터를 받지 않는다 — Hibernate가 채우는 값이라 애플리케이션 코드가 "
            + "직접 지정할 수단이 없다")
    void builder_hasNoCreatedAtParameter() {
        // 이 테스트는 컴파일 시점 자체가 단언이다: UserBlock.builder()에 .createdAt(...)을 호출하는
        // 코드는 그 자체로 컴파일되지 않는다. 런타임 단언으로는 "영속화 전 createdAt은 null"이라는
        // 사실만 재확인한다.
        UserBlock userBlock = UserBlock.builder()
                .blocker(newUserAccount("차단자2"))
                .blocked(newUserAccount("피차단자2"))
                .build();

        assertThat(userBlock.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("[USER-BLK-13 전제] 서로 다른 (blocker, blocked) 조합으로 만든 두 UserBlock은 "
            + "blocker/blocked가 서로 뒤바뀌어 배선되지 않는다(단방향 저장의 방향이 보존된다)")
    void builder_doesNotSwapBlockerAndBlockedAcrossInstances() {
        // given
        UserAccount a = newUserAccount("A");
        UserAccount b = newUserAccount("B");

        // when
        UserBlock aBlocksB = UserBlock.builder().blocker(a).blocked(b).build();
        UserBlock bBlocksA = UserBlock.builder().blocker(b).blocked(a).build();

        // then
        assertThat(aBlocksB.getBlocker()).isSameAs(a);
        assertThat(aBlocksB.getBlocked()).isSameAs(b);
        assertThat(bBlocksA.getBlocker()).isSameAs(b);
        assertThat(bBlocksA.getBlocked()).isSameAs(a);
    }
}
