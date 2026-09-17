package com.skhynix.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 실명. 소셜로 만들어진 계정에서는 {@code null} 이다 — 3사 모두 이 값을 주지 않고, 아무도 읽지
     * 않는 값을 위해 소셜 가입에 폼을 강제하지 않기로 했다.
     *
     * <p>⚠ 컬럼이 느슨해진 것뿐이고 <b>자체 회원가입 요청은 여전히 이 값을 필수로 받는다</b>.
     * 그 강제는 이제 DB 가 아니라 요청 검증에만 남아 있으므로, 가입 DTO 의 {@code @NotBlank} 를 떼면
     * 아무 데서도 안 걸리고 이름 없는 자체 가입 계정이 조용히 생긴다.
     */
    @Column(name = "name", length = 30, nullable = true)
    private String name;

    /**
     * 전화번호. 소셜 계정에서는 {@code null} 이다({@link #name} 과 같은 이유).
     *
     * <p>UNIQUE 는 그대로 둔다 — MySQL 의 UNIQUE 인덱스는 NULL 중복을 허용하므로 "번호 없는 계정
     * 여러 건"과 "같은 번호 두 건 금지"가 동시에 성립한다. 제약을 풀 이유가 없다.
     *
     * <p>⚠ 이제 <b>{@code tel} 이 NULL 인 계정이 정상적으로 존재한다.</b> 앞으로 SMS 발송·본인확인
     * 같은 기능을 붙일 때 "전화번호는 항상 있다"를 전제로 짜면 소셜 계정에서 NPE 나 조용한 누락이
     * 된다({@code name}·{@code gender} 도 같다).
     */
    @Column(name = "tel", length = 11, nullable = true, unique = true)
    private String tel;

    @Column(name = "email", length = 100, nullable = false, unique = true)
    private String email;

    // 소셜 계정에서는 null (name·tel 과 같은 이유)
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "gender", columnDefinition = "TINYINT", nullable = true)
    private Gender gender;

    /**
     * 이 계정의 이메일이 <b>소유자 본인의 것임이 확인됐는가</b>. 소셜 자동 통합이 코드 인증 없이
     * 기존 계정에 붙어도 되는지를 가르는 유일한 근거다.
     *
     * <p>기본값이 {@code true} 인 것이 두 경로의 정답을 동시에 만든다: 자체 가입은 이메일 인증을
     * 이미 선행조건으로 강제하므로 항상 검증됨이고, 이 컬럼이 생기기 전부터 있던 기존 행도
     * {@code DEFAULT}(=1)로 채워져 검증됨이 된다. ⚠ <b>손으로 DDL 을 칠 때 {@code DEFAULT 1} 을
     * 빠뜨리면 기존 계정 전원이 미검증이 되어</b> 모든 소셜 통합이 코드 인증을 요구하게 된다.
     *
     * <p>미검증({@code false})으로 태어나는 유일한 길은 {@link #socialSignup(String, boolean)} 이다.
     *
     * <p><b>승격은 단방향이다</b>({@link #verifyEmail()}). 되돌리는 전이를 만들지 말 것 — 미검증으로
     * 되돌릴 수 있으면 검증됨 계정도 다시 코드 인증 갈래로 끌려가고, 무엇보다 그 계정에 이미 붙어
     * 있던 정상 연동이 선점 방지 규칙에 걸려 함께 끊긴다.
     */
    @Column(name = "email_verified", columnDefinition = "TINYINT", nullable = false)
    @ColumnDefault("1")
    private boolean emailVerified = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private User(String name, String tel, String email, Gender gender) {
        // emailVerified 는 @Builder 파라미터로 받지 않는다 — 이 길로 만들어지는 계정(자체 가입·예약
        // 계정)은 예외 없이 검증됨이므로 필드 초기값이 곧 정답이고, 파라미터를 늘리면 기존 호출부가
        // Lombok 기본값(false)을 받아 조용히 미검증 계정을 만든다.
        this.name = name;
        this.tel = tel;
        this.email = email;
        this.gender = gender;
    }

    /**
     * <b>소셜 가입</b> 전용 생성 — provider 가 주는 것이 이메일뿐이라 {@code name}·{@code tel}·
     * {@code gender} 는 NULL 로 둔다.
     *
     * <p>{@code @Builder} 를 쓰지 않는 이유는 {@code emailVerified} 때문이다. 이 경로만이 미검증
     * 계정을 만들 수 있어야 하는데, 빌더에 파라미터로 열면 <b>모든</b> 호출부가 검증 상태를 정할 수
     * 있게 되고 값을 빠뜨린 쪽은 Lombok 기본값 {@code false} 를 받는다({@code UserAccount.reserved}
     * 가 예약 계정만의 생성 규칙을 별도 팩터리로 가져간 것과 같은 판단이다).
     *
     * <p>{@code emailVerified} 는 provider 의 검증 판정을 <b>그대로</b> 받는다. 판정 규칙(구글은
     * {@code email_verified}, 카카오는 두 필드의 동시 만족, 네이버는 판정 필드가 없어 언제나 미검증)은
     * provider 응답을 아는 쪽의 몫이고 domain 은 그 결과만 보관한다.
     *
     * <p>⚠ 여기서 만든 계정의 이메일은 <b>티켓에 실려 온 provider 이메일이어야 한다.</b> 요청 본문의
     * 이메일을 쓰면 남의 이메일로 계정을 만들 수 있게 된다.
     */
    public static User socialSignup(String email, boolean emailVerified) {
        User user = new User();
        user.email = email;
        user.emailVerified = emailVerified;
        return user;
    }

    /**
     * 이메일 검증 승격 — 이미 검증됨이면 no-op 이다.
     *
     * <p>⚠ <b>대응되는 되돌리기 메서드를 추가하지 말 것.</b> 이 전이가 단방향이라는 사실 자체가
     * 계약이며, 호출자는 이 메서드를 부르기 <b>전에</b> {@link #isEmailVerified()} 로 직전 상태를
     * 읽어야 한다 — "미검증이던 계정이었는가"가 기존 연동을 전량 해제할지 말지를 가르는 조건인데,
     * 부른 뒤에는 그 사실이 어디에도 남지 않는다.
     */
    public void verifyEmail() {
        this.emailVerified = true;
    }
}
