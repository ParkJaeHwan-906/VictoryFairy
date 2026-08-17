package com.skhynix.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 외부 노출용 식별자 — id(순차 PK)는 내부 전용이라 API·URL에는 이 값을 노출해 PK 열거를 막는다.
    @Column(name = "uid", length = 36, nullable = false, unique = true, updatable = false,
            columnDefinition = "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin")
    private String uid;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "nickname", length = 100, nullable = false)
    private String nickname;

    @Column(name = "password", length = 255, nullable = false)
    private String password;

    @Column(name = "exit_at")
    private LocalDateTime exitAt;

    /**
     * 마지막 닉네임 변경 시각(epoch 초). 한 번도 바꾼 적 없으면 {@code null}이다 — 가입 시점에도 채우지
     * 않는다(채우면 가입 직후 30일간 닉네임을 못 바꾸게 된다).
     *
     * <p>{@code DATETIME}이 아니라 epoch 초인 이유: 파드 TZ(운영은 UTC)나 파드 간 TZ 불일치의 영향을
     * 받지 않는 존 무관 저장이라, 변경 간격 판정이 실행 환경에 좌우되지 않는다.
     */
    @Column(name = "nickname_changed_epoch_second")
    private Long nicknameChangedEpochSecond;

    /**
     * 보유 포인트. 신규 계정은 항상 0에서 시작한다.
     *
     * <p>이미 행이 있는 테이블에 붙는 NOT NULL 컬럼이라 {@code @ColumnDefault}(기존 행)와 자바 필드
     * 초기값(신규 행)이 각각 다른 경로의 0 보장을 맡는다 — 하나만 있으면 한쪽이 깨진다.
     */
    @Column(name = "point", nullable = false)
    @ColumnDefault("0")
    private long point = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private UserAccount(User user, String nickname, String password) {
        // uid·exitAt·point는 @Builder 파라미터로 받지 않는다 — 탈퇴 상태로 태어나는 계정을 막기 위해
        // exitAt은 null(활성)로 시작해 withdraw()로만 전이한다.
        this.uid = UUID.randomUUID().toString();
        this.user = user;
        this.nickname = nickname;
        this.password = password;
        this.point = 0L;
    }

    /**
     * 포인트 적립 — 퀴즈 정답 보상이 이 뮤테이터를 쓰는 유일한 경로다.
     *
     * <p>⚠ 호출자는 반드시 {@code UserAccountRepository.findWithLockById}로 이 행을 잠근 뒤 불러야
     * 한다 — 일반 {@code findById}로 읽은 두 트랜잭션이 같은 잔액에서 각자 더하면 한쪽 적립이
     * 유실된다(lost update). 락 없는 조회 경로에 습관적으로 붙이지 말 것.
     *
     * <p>{@code users_bq.bq_score}는 여기서 건드리지 않는다 — 레이팅 설계가 확정되기 전이라
     * 보유 포인트({@code point})와 레이팅 점수를 미리 묶으면 나중에 풀 수 없다.
     */
    public void addPoint(long delta) {
        this.point += delta;
    }

    // exitAt은 "탈퇴 예정 시각"이 아니라 탈퇴 완료 시각이다(유예 기간·취소 없음). 이미 탈퇴한 계정이면
    // no-op으로 최초 탈퇴 시각을 보존한다. 호출자가 시각을 넘기는 이유는 같은 트랜잭션의 다른 작업
    // (refresh 토큰 만료)과 시각을 정확히 맞추기 위해서다.
    public void withdraw(LocalDateTime exitAt) {
        if (isWithdrawn()) {
            return;
        }
        this.exitAt = exitAt;
    }

    public boolean isWithdrawn() {
        return exitAt != null;
    }

    /**
     * 닉네임 교체 + 변경 시각 기록. 형식·중복·현재 값과의 동일 여부·변경 간격은 호출자(정책·리포지토리를
     * 아는 쪽)가 판정한 뒤 부른다 — 엔티티에 @Setter 를 두지 않는 것과 같은 이유로, 상태 전이는 이름
     * 있는 메서드로만 연다.
     *
     * <p><b>시각 기록을 별도 메서드로 분리하지 말 것.</b> 교체와 기록을 한 전이에 묶어야 "성공했을 때만
     * 기록된다"가 구조로 보장된다 — 나누면 닉네임만 바뀌고 시각은 그대로인(=변경 간격 제한이 뚫리는)
     * 호출 경로가 언젠가 생긴다. 호출자가 시각을 넘기는 이유는 {@link #withdraw(LocalDateTime)}와 같다:
     * "지금"의 출처는 엔티티가 아니라 호출자의 {@code Clock}이다.
     */
    public void changeNickname(String nickname, long changedEpochSecond) {
        this.nickname = nickname;
        this.nicknameChangedEpochSecond = changedEpochSecond;
    }

    /**
     * 비밀번호 교체. 인자는 <b>이미 인코딩된</b> 값이어야 한다 — domain 은 인코더를 알지 못하므로
     * 평문을 넘기면 그대로 저장돼 로그인이 깨진다.
     */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
