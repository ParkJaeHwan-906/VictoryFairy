package com.skhynix.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * 소셜 계정 연동 — (provider, provider 가 준 사용자 식별자) 하나를 이 서비스의 계정 하나에 묶는다.
 *
 * <p><b>이 행은 신원 그 자체다.</b> 잘못된 행 하나가 곧 "남의 계정으로 로그인됨"이라, 아래 UNIQUE
 * 두 개가 장식이 아니라 이 기능의 안전장치 본체다:
 * <ul>
 *   <li>{@code uk_users_oauth_link_provider_user(provider, provider_user_id)} — 한 소셜 신원이 두
 *       계정에 걸리지 않게 한다.</li>
 *   <li>{@code uk_users_oauth_link_account_provider(user_account_id, provider)} — 한 계정에 같은
 *       provider 연동이 둘 생기지 않게 한다.</li>
 * </ul>
 * 애플리케이션의 "먼저 찾아보고 없으면 만든다" 판정만으로는 <b>동시 요청에서 반드시 뚫린다</b>
 * (조회와 INSERT 사이가 열려 있다). 심판은 DB 다 — 두 제약 중 하나라도 빠뜨리면 그 순간 이 기능은
 * 계정 탈취 경로가 된다.
 *
 * <p>상태 전이 메서드가 하나도 없는 것은 빠뜨린 게 아니다. 연동은 <b>생기거나 사라지거나</b> 둘뿐이며
 * 특히 provider·식별자·소속 계정을 나중에 갈아끼우는 전이를 만들면 안 된다 — 그건 "이 신원이 저
 * 사람이었다"를 사후에 바꾸는 일이고, 위 UNIQUE 를 우회해 다른 계정으로 옮겨 붙이는 수단이 된다.
 * 옮겨야 한다면 지우고 새로 만든다.
 */
@Entity
@Table(name = "users_oauth_link", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_oauth_link_provider_user",
                columnNames = {"provider", "provider_user_id"}),
        @UniqueConstraint(name = "uk_users_oauth_link_account_provider",
                columnNames = {"user_account_id", "provider"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOauthLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * CASCADE — 계정이 물리 삭제되면 연동도 함께 사라져야 한다.
     *
     * <p>여기서만큼은 CASCADE 가 취향이 아니라 <b>배치의 동작 조건</b>이다. 만료 데이터 정리는
     * {@code DELETE FROM users} 한 문장으로 계정을 지우는데, 이 FK 가 non-cascade 면 그 문장이 FK
     * 위반으로 실패하고 <b>그 계정이 배치에서 통째로 스킵된다</b>(개인정보가 안 지워진 채 남는다).
     *
     * <p>⚠ {@code ddl-auto=update} 는 <b>이미 만들어진 FK 의 삭제 규칙을 바꾸지 않는다.</b> 테이블이
     * 한 번 잘못된 규칙으로 생성되고 나면 여기 애노테이션을 고쳐도 DB 는 그대로이며, 증상은 배포가
     * 아니라 30일 뒤 배치에서야 나타난다({@code QuizLike} 가 실제로 밟은 사고다).
     *
     * <p>이 저장소의 다른 FK 와 달리 제약 이름을 못박은 이유: 이 테이블은 운영에 <b>손으로</b>
     * 만들어지는데, 이름을 비워 두면 Hibernate 가 기대하는 이름({@code FK...} 난수)과 실제 이름이
     * 달라 다음 기동의 {@code ddl-auto=update} 가 같은 FK 를 하나 더 붙인다(이름으로 존재 여부를
     * 찾기 때문이다). 손 DDL 과 애노테이션이 같은 이름을 쓰면 그 중복이 원천 차단된다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_users_oauth_link_account"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    /**
     * {@code @JdbcTypeCode(VARCHAR)} 가 붙어 있는 이유는 <b>MySQL 네이티브 {@code ENUM} 컬럼을 피하기
     * 위해서다.</b> 이것을 빼면 Hibernate 의 MySQL 방언이 {@code @Enumerated(STRING)} 을
     * {@code enum('GOOGLE','KAKAO','NAVER')} 로 만드는데(이때 {@code length} 도 무시된다), 나중에
     * provider 를 하나 더 붙이는 날 <b>{@code ddl-auto=update} 는 기존 컬럼의 enum 목록을 넓혀 주지
     * 않는다</b> — 배포는 멀쩡히 끝나고 그 provider 첫 로그인의 INSERT 만 data truncated 로 죽는다.
     * varchar 면 새 상수가 그냥 들어간다.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", length = 20, nullable = false)
    private OauthProvider provider;

    /**
     * provider 가 준 사용자 식별자를 <b>가공 없이 그대로</b> 담는다(카카오 숫자 id, 구글 {@code sub} 등).
     *
     * <p>collation 을 {@code ascii_bin} 으로 고정한 것이 이 컬럼의 핵심이다. MySQL 기본 collation
     * ({@code utf8mb4_0900_ai_ci})은 <b>대소문자를 구분하지 않아</b>, 대소문자만 다른 두 식별자가 위
     * UNIQUE 인덱스에서 같은 값으로 취급된다 — 네이버처럼 영숫자 혼합 식별자를 주는 provider 에서
     * 남의 신원이 내 연동 행과 충돌하거나, 최악의 경우 조회가 <b>다른 사람의 행</b>을 물어 온다.
     * 식별자는 provider 가 정한 불투명한 바이트열이므로 바이트 그대로 비교해야 한다
     * ({@code UserAccount.uid} 가 같은 이유로 {@code ascii_bin} 이다).
     */
    @Column(name = "provider_user_id", length = 255, nullable = false,
            columnDefinition = "VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin")
    private String providerUserId;

    // 연동 시각. updated_at 이 없는 것은 의도다 — 위 javadoc 대로 이 행은 갱신되지 않는다
    // (UserRefreshToken 과 같은 기록성 엔티티 계열).
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private UserOauthLink(UserAccount userAccount, OauthProvider provider, String providerUserId) {
        this.userAccount = userAccount;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
