package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * 이메일로 <b>활성</b>(탈퇴하지 않은) 계정을 조회한다. 탈퇴한 계정은 조회되지 않는다.
     *
     * <p>탈퇴 계정을 "찾지 못함"으로 흡수하는 것이 의도다 — login이 탈퇴 계정과 미가입 이메일을
     * 완전히 같은 응답(비밀번호 검사도 하지 않고 곧바로 {@code INVALID_CREDENTIALS})으로 처리해야
     * 해당 이메일의 가입 이력이 노출되지 않는다. "탈퇴했습니다" 류의 전용 메시지를 두지 않는 이유다.
     */
    Optional<UserAccount> findByUser_EmailAndExitAtIsNull(String email);

    /**
     * {@code uid}로 <b>활성</b>(탈퇴하지 않은) 계정의 내부 PK만 조회한다. 탈퇴한 계정은 찾지 못한 것으로
     * 취급되며, 인증 필터가 이 결과로 탈퇴 계정의 access 토큰을 차단한다.
     *
     * <p>{@code id}만 SELECT하는 이유: InnoDB secondary index는 PK를 품으므로 {@code uid} unique
     * 인덱스는 물리적으로 이미 {@code (uid, id)}다. 엔티티 하이드레이션·영속성 컨텍스트 적재도 없다.
     * 파생 쿼리 이름 규칙으로는 id 프로젝션이 보장되지 않아 {@code @Query}로 명시한다.
     *
     * <p><b>커버링 인덱스는 의도적으로 포기했다.</b> {@code exit_at}은 uid 인덱스에 없으므로 이 조건이
     * 붙는 순간 클러스터드 인덱스 조회가 되살아나고, 그 비용은 인증이 필요한 모든 요청에 붙는다.
     * 그럼에도 감수하는 이유: access 토큰이 stateless(3h)라 <b>이 조회가 탈퇴 즉시 차단의 유일한
     * 지점</b>이고, 되살아나는 페이지는 대개 버퍼 풀에 상주해 디스크 I/O가 아닌 메모리 접근에 그친다.
     * {@code (uid, exit_at)} 복합 인덱스는 uid unique 인덱스와 거의 중복이라(쓰기 비용·용량 대비
     * 버퍼 풀 히트 1회 절약) 두지 않는다.
     */
    @Query("select ua.id from UserAccount ua where ua.uid = :uid and ua.exitAt is null")
    Optional<Long> findActiveIdByUid(@Param("uid") String uid);

    boolean existsByNickname(String nickname);
}
