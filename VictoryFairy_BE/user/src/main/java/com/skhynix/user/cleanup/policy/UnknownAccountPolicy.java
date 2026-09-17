package com.skhynix.user.cleanup.policy;

import com.skhynix.domain.user.entity.Gender;

/**
 * 탈퇴자가 남긴 공용 데이터(채팅방·채팅)를 넘겨받는 <b>{@code (알수없음)} 더미 계정</b>의 예약값 —
 * 이 값들의 <b>단일 출처</b>다({@code PasswordPolicy}·{@code NicknamePolicy} 와 같은 구조).
 *
 * <p>이 계정이 필요한 이유는 정책이 아니라 스키마다: {@code chatrooms.owner_account_id} 는
 * NOT NULL + FK NO ACTION 이라 소유자를 비울 수 없고, {@code chats} 는 히스토리 변환이 작성자의
 * 닉네임을 역참조해 NULL 이면 NPE 다. 즉 "떠난 사람의 자리"를 대신 채울 실제 계정 행이 있어야 한다.
 *
 * <p><b>이 계정으로는 로그인이 성립하지 않는다.</b> {@link #LOCKED_PASSWORD} 는 BCrypt 패턴이 아니라
 * {@code BCryptPasswordEncoder.matches()} 가 어떤 원문에도 예외 없이 false 를 낸다
 * ({@code infra/sql/chat-init.sql} 의 SYSTEM 계정 선례와 같은 방식).
 *
 * <p><b>사칭도 성립하지 않는다.</b> {@code NicknamePolicy.REGEX} 의 허용 문자에 괄호가 없어 가입·닉네임
 * 변경 어느 경로로도 {@code (알수없음)} 을 만들 수 없다 — {@code users_account.nickname} 에는 DB UNIQUE
 * 가 없으므로 사칭을 막는 것은 중복 검사가 아니라 <b>그 문자 정책</b>이다. ⚠ 허용 문자를 넓히는 변경은
 * 이 보장을 조용히 깬다. 그때 남는 마지막 방어선은 이 계정을 <b>닉네임이 아니라 {@link #UID} 로만
 * 식별</b>한다는 사실이다(닉네임에는 UNIQUE 가 없어 같은 값의 행이 둘이면 선택이 비결정적이 된다).
 *
 * <p>SYSTEM 시드 계정을 재사용하지 않는 이유: 그 계정은 "구단 공용 채팅방의 소유자"라는 별개 의미를
 * 이미 갖고 있어, 위에 "탈퇴자 콘텐츠의 소유자"를 겹치면 나중에 어느 기준으로도 둘을 갈라낼 수 없다.
 */
public final class UnknownAccountPolicy {

    /** 예약 uid — SYSTEM 시드 계정({@code chat-init.sql})의 uid 와 겹치지 않는 값. 이 계정을 찾는 유일한 키다. */
    public static final String UID = "568ee3c3-029f-4514-b87f-9d90e729f755";

    /** 노출되는 이름. 괄호가 들어 있어 일반 사용자가 만들 수 없다(위 사칭 불가 근거). */
    public static final String NICKNAME = "(알수없음)";

    /** 예약 계정은 {@code .internal} 도메인이라는 규칙(SYSTEM 계정과 동일). */
    public static final String EMAIL = "unknown@victoryfairy.internal";

    /** {@code users.tel} 은 NOT NULL + UNIQUE 라 값이 필요하다. SYSTEM 계정({@code ...0}) 다음 번호. */
    public static final String TEL = "00000000001";

    /** {@code users.name} 은 NOT NULL. 실제 사람 이름이 아니므로 ASCII 식별자로 둔다. */
    public static final String NAME = "UNKNOWN";

    /** {@code users.gender} 는 NOT NULL 이고 이 계정에 의미가 없다 — SYSTEM 시드와 같은 관례값. */
    public static final Gender GENDER = Gender.MALE;

    /** BCrypt 패턴이 아닌 placeholder. ⚠ 여기에 진짜 해시를 넣으면 로그인 가능한 계정이 된다. */
    public static final String LOCKED_PASSWORD = "LOCKED-NO-LOGIN";

    private UnknownAccountPolicy() {
    }
}
