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
     * 마지막 닉네임 변경 시각. 한 번도 바꾼 적 없으면 {@code null}이다 — 가입 시점에도 채우지
     * 않는다(채우면 가입 직후 30일간 닉네임을 못 바꾸게 된다).
     *
     * <p>바로 아래 {@code passwordChangedEpochSecond}와 타입이 다른 것은 실수가 아니다. 이 값은 토큰의
     * {@code iat} 같은 외부의 절대 시각과 대조되지 않고 오직 "마지막 변경으로부터 30일"이라는 자기
     * 자신과의 간격 계산에만 쓰이므로, 존 무관 저장이 필요 없고 저장소의 다른 시각 컬럼
     * ({@code exit_at}·{@code created_at})과 형태를 맞추는 편이 낫다.
     *
     * <p>다만 벽시계 값이라 <b>어느 존으로 쓰고 읽느냐가 곧 값의 의미</b>다 — 기록하는 쪽과 판정하는
     * 쪽이 같은 존({@code Clock} 빈, Asia/Seoul)을 써야 하며, {@code LocalDateTime.now()}(시스템 기본
     * 존)를 섞어 쓰면 UTC 파드에서 9시간 어긋난다.
     */
    @Column(name = "nickname_changed_at")
    private LocalDateTime nicknameChangedAt;

    /**
     * 마지막 비밀번호 변경 시각(epoch 초) = <b>이 시각보다 앞선 초에 발급된 토큰은 무효</b>라는 기준선.
     * 한 번도 바꾼 적 없으면 {@code null}이고, 그 계정은 무효화 대조 자체를 건너뛴다(무효화할 사건이
     * 없었다는 뜻이라 배포 시 백필하지 않는다 — 백필하면 그 순간 전 사용자가 로그아웃된다).
     *
     * <p>{@code DATETIME}이 아니라 epoch 초인 이유는 {@code nicknameChangedAt}(그쪽은 DATETIME)와 다르다.
     * 이 값은 토큰의 {@code iat}(절대 시각)와 <b>직접 비교</b>되는 유일한 컬럼이라, 존 개념이 있는
     * 값으로 두면 쓰는 앱(user)과 읽는 앱(quiz)의 TZ가 어긋나는 순간 무효화가 9시간 단위로 오작동한다
     * (멀쩡한 계정 전원 401 또는 무효화 무력화). 초 단위인 것도 우연이 아니다 — {@code iat}가 초로
     * 내림되므로 기준선도 같은 눈금이어야 자기 무효화가 원리적으로 불가능해진다
     * ({@link #acceptsTokenIssuedAt(long)} 참고).
     *
     * <p>⚠ "다른 시각 컬럼과의 일관성"을 이유로 {@code LocalDateTime}으로 되돌리지 말 것 — 증상은
     * TZ 설정이 바뀌는 날에야 나타난다.
     */
    @Column(name = "password_changed_epoch_second")
    private Long passwordChangedEpochSecond;

    /**
     * 보유 포인트. 신규 계정은 항상 0에서 시작한다.
     *
     * <p>이미 행이 있는 테이블에 붙는 NOT NULL 컬럼이라 {@code @ColumnDefault}(기존 행)와 자바 필드
     * 초기값(신규 행)이 각각 다른 경로의 0 보장을 맡는다 — 하나만 있으면 한쪽이 깨진다.
     */
    @Column(name = "point", nullable = false)
    @ColumnDefault("0")
    private long point = 0L;

    /**
     * 프로필 이미지의 <b>EP(오브젝트 키)</b>. 이미지가 없으면 {@code null}이고, 그것이 기본 상태다
     * (가입 시 채우지 않는다 — 아래 {@code @Builder} 주석 참고).
     *
     * <p><b>전체 URL을 담지 않는다.</b> {@code user-profile-img/{uuid}.jpg}처럼 BaseURL(버킷 공개
     * 도메인·CDN)을 뺀 나머지만 저장한다 — 도메인이 바뀌는 날 저장된 값이 전부 죽은 링크가 되어
     * DB를 통째로 UPDATE해야 하는 상황을 만들지 않기 위해서다. BaseURL 조립은 읽는 쪽 몫이다.
     *
     * <p>⚠ 그래서 <b>이 값을 그대로 이미지 주소로 쓰면 깨진다</b>. 반대로 어딘가에서 전체 URL을
     * 넘겨 저장해 버리면 조립 시 도메인이 두 번 붙어, 값이 섞인 뒤에는 어느 행이 어느 형태인지
     * 구분할 방법이 없다({@code length}만으로는 안 걸린다).
     */
    @Column(name = "profile_img_url", length = 255, nullable = true)
    private String profileImgUrl;

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
        // profileImgUrl도 받지 않는다(빠뜨린 게 아니다) — 파라미터를 늘리면 기존 가입 호출부의 계약이
        // 흔들리므로, 가입 시 이미지 설정도 저장 후 changeProfileImgUrl()로 전이한다.
        this.uid = UUID.randomUUID().toString();
        this.user = user;
        this.nickname = nickname;
        this.password = password;
        this.point = 0L;
    }

    /**
     * <b>예약 계정</b> 전용 생성 — uid 를 호출자가 고정한다. 일반 가입 경로({@code @Builder})는 uid 를
     * 스스로 만들지만, 시드/시스템 계정은 코드가 다시 찾아낼 수 있어야 해서 값이 고정이어야 한다
     * (닉네임으로 찾지 않는다 — {@code nickname} 에는 DB UNIQUE 가 없어 같은 값의 행이 둘이 되면 어느
     * 쪽을 고를지 비결정적이다).
     *
     * <p>⚠ <b>회원가입 경로에서 부르지 말 것.</b> 이 길로 만든 계정은 사람이 쓰는 계정이 아니라
     * "데이터를 넘겨받는 자리"이며, {@code password} 에는 BCrypt 패턴이 아닌 placeholder 를 넣어
     * 어떤 원문으로도 로그인이 성립하지 않게 한다({@code BCryptPasswordEncoder.matches()} 는 인코딩된
     * 값이 BCrypt 패턴이 아니면 예외 없이 항상 false — {@code chat-init.sql} 의 SYSTEM 계정 선례).
     *
     * <p>{@code exitAt} 은 채우지 않는다(활성). 예약 계정은 탈퇴 상태로 태어나서는 안 된다 —
     * 만료 데이터 정리가 탈퇴 계정을 대상으로 삼기 때문이다.
     */
    public static UserAccount reserved(String uid, User user, String nickname, String lockedPassword) {
        UserAccount account = new UserAccount();
        account.uid = uid;
        account.user = user;
        account.nickname = nickname;
        account.password = lockedPassword;
        account.point = 0L;
        return account;
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

    /**
     * 포인트 차감 — 캐릭터 아이템 구매가 이 뮤테이터를 쓰는 유일한 경로다.
     * ⚠ 락 요구는 {@link #addPoint(long)} 와 같다({@code findWithLockById} 로 잠근 뒤 호출).
     *
     * <p>잔액이 모자라면 <b>깎지 않고 던진다.</b> 서비스가 먼저 검사해 4xx 를 돌려주므로 여기까지 오는
     * 것은 그 검사를 빠뜨린 버그뿐이라 {@code BusinessException} 이 아니다. 음수 잔액을 한 번 만들면 그
     * 계정은 이후 모든 적립이 빚 갚기로 흡수돼 조용히 망가진다.
     */
    public void deductPoint(long delta) {
        if (delta < 0) {
            throw new IllegalStateException("차감액은 음수일 수 없습니다.");
        }
        if (this.point < delta) {
            throw new IllegalStateException("보유 포인트보다 큰 금액을 차감할 수 없습니다.");
        }
        this.point -= delta;
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
    public void changeNickname(String nickname, LocalDateTime changedAt) {
        this.nickname = nickname;
        this.nicknameChangedAt = changedAt;
    }

    /**
     * 프로필 이미지 EP 교체. 가입 시 최초 설정과 이후 변경을 <b>한 메서드로</b> 받는다 — 두 경우의
     * 상태 전이가 "컬럼 값을 새 EP로 바꾼다"로 완전히 같고, 다른 것은 직전 값이 {@code null}이었느냐
     * 뿐이라서다. 나누면 호출자가 "이번이 최초인가"를 판정해야 하는데 <b>잘못 골라도 결과가 같아
     * 오류가 드러나지 않는</b> 종류의 분기가 생긴다({@link #changeNickname(String, LocalDateTime)}이
     * 시각 기록을 묶어야 했던 것과 달리, 여기엔 함께 갱신할 짝 컬럼이 없어 나눌 이유 자체가 없다).
     *
     * <p>가입 경로가 {@code @Builder}가 아니라 이 메서드를 쓰는 이유는 빌더 파라미터를 늘리면 기존
     * 가입 호출부의 계약이 흔들리기 때문이다 — 저장 후 호출한다.
     *
     * <p>인자는 <b>BaseURL을 뺀 EP</b>여야 한다({@link #getProfileImgUrl()} 참고). 형식·저장소에 객체가
     * 실제로 있는지는 호출자가 판정한다 — domain은 오브젝트 스토리지를 알지 못한다.
     *
     * <p>⚠ 이 전이는 <b>직전 EP를 잃어버린다.</b> 옛 객체를 지워야 하는 호출자는 부르기 전에
     * {@link #getProfileImgUrl()}로 값을 먼저 챙겨야 한다(부른 뒤에는 어디에도 남아 있지 않다).
     */
    public void changeProfileImgUrl(String profileImgUrl) {
        this.profileImgUrl = profileImgUrl;
    }

    /**
     * 비밀번호 교체 + 토큰 무효화 기준 시각 기록. 인자는 <b>이미 인코딩된</b> 값이어야 한다 — domain 은
     * 인코더를 알지 못하므로 평문을 넘기면 그대로 저장돼 로그인이 깨진다.
     *
     * <p><b>기록을 별도 메서드로 분리하지 말 것</b> — {@link #changeNickname(String, LocalDateTime)}과 같은
     * 이유다. 교체와 기록을 한 전이에 묶어야 "비밀번호 변경이 성공했을 때만, 그리고 그때는 반드시
     * 기록된다"가 구조로 보장된다. 나누면 비밀번호만 바뀌고 기준 시각은 그대로인(=옛 토큰이 계속
     * 살아 있는) 호출 경로가 언젠가 생긴다.
     *
     * <p>{@code changedEpochSecond}는 호출자가 넘긴다({@link #withdraw(LocalDateTime)}와 같은 계열).
     * ⚠ 호출자는 이 값을 <b>토큰 발급 직전</b>에 읽어야 한다 — 발급 후에 읽으면 새 토큰의 {@code iat}가
     * 기준선보다 앞서 응답으로 준 토큰이 곧바로 거절된다.
     */
    public void changePassword(String encodedPassword, long changedEpochSecond) {
        this.password = encodedPassword;
        this.passwordChangedEpochSecond = changedEpochSecond;
    }

    /**
     * 그 시각에 발급된 토큰을 이 계정이 여전히 받아들이는가. 판정에 "지금"이 들어가지 않는 것이 핵심이다
     * — 두 값 모두 과거의 고정값이라 읽는 파드(user·quiz)의 시계가 어긋나 있어도 결과가 같다.
     *
     * <p>⚠ <b>{@code >=}를 {@code >}로 바꾸지 말 것(= 같은 초를 거절하지 말 것).</b> {@code iat}는
     * RFC 7519 NumericDate라 초 단위로 내림되고 기준 시각도 같은 초로 적히므로, 비밀번호 변경 응답으로
     * 방금 발급한 토큰은 {@code iat == 기준시각}인 경우가 대부분이다. 같은 초를 거절하면 그 토큰이
     * 자기 자신에게 거절돼 변경 직후 로그인이 통째로 깨진다. 대가는 변경과 같은 초에 이미 발급돼 있던
     * 이전 토큰이 살아남는 ≤1초 창이고, 그것은 의도된 한계다.
     *
     * <p>기준 시각이 미래로 잘못 적힌 계정은 새로 로그인해도 계속 거절된다(fail-closed). 미래 값을
     * 무시하거나 깎는 보정을 넣지 말 것 — 그 보정은 시계 사고를 조용히 덮어 무효화가 꺼진 상태를
     * 정상처럼 보이게 한다.
     */
    public static boolean acceptsTokenIssuedAt(Long passwordChangedEpochSecond,
            long issuedAtEpochSecond) {
        return passwordChangedEpochSecond == null || issuedAtEpochSecond >= passwordChangedEpochSecond;
    }

    /**
     * {@link #acceptsTokenIssuedAt(Long, long)}의 인스턴스 형태. 엔티티를 이미 손에 쥔 호출자
     * (예: refresh 재발급)가 쓰고, 엔티티를 통째로 읽지 않는 필터는 프로젝션 쪽 같은 이름의 메서드를
     * 쓴다 — 규칙 자체는 위 static 하나뿐이다.
     */
    public boolean acceptsTokenIssuedAt(long issuedAtEpochSecond) {
        return acceptsTokenIssuedAt(this.passwordChangedEpochSecond, issuedAtEpochSecond);
    }
}
