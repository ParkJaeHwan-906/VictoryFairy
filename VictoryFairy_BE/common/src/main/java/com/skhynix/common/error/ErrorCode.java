package com.skhynix.common.error;

/**
 * 공통 에러 코드. status는 HTTP 상태값(int)으로 보관해 common이 spring 의존성을 갖지 않도록 한다.
 */
public enum ErrorCode {

    // 409 Conflict
    DUPLICATE_EMAIL(409, "이미 사용 중인 이메일입니다."),
    DUPLICATE_TEL(409, "이미 사용 중인 전화번호입니다."),
    DUPLICATE_NICKNAME(409, "이미 사용 중인 닉네임입니다."),

    // 401 Unauthorized
    UNAUTHENTICATED(401, "인증이 필요합니다."),
    INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(401, "유효하지 않은 리프레시 토큰입니다."),
    EXPIRED_REFRESH_TOKEN(401, "만료되었거나 이미 무효화된 리프레시 토큰입니다."),

    // 400 Bad Request - 이메일 인증
    INVALID_VERIFICATION_CODE(400, "인증번호가 일치하지 않습니다."),
    EXPIRED_VERIFICATION_CODE(400, "만료되었거나 유효하지 않은 인증번호입니다."),
    VERIFICATION_ATTEMPTS_EXCEEDED(400, "인증 시도 횟수를 초과했습니다. 인증번호를 다시 발송해 주세요."),
    EMAIL_NOT_VERIFIED(400, "이메일 인증이 완료되지 않았습니다."),

    // 403 Forbidden
    SELF_REPORT_NOT_ALLOWED(403, "자신의 메시지는 신고할 수 없습니다."),
    CHATROOM_TEAM_MISMATCH(403, "응원하는 구단의 채팅방만 이용할 수 있습니다."),
    // 미존재·미편성·미제출 세 거절 사유를 하나로 합친 코드 — 문구가 문제의 존재·편성 여부를
    // 드러내면 id 순회로 내일 출제분을 알아낼 수 있게 되므로 사유를 세분화하지 말 것
    QUIZ_LIKE_NOT_ALLOWED(403, "좋아요는 직접 푼 문제에만 할 수 있습니다."),
    // '오늘의 퀴즈'로 받지 않은 문제와 제한 시간(8분)이 지난 문제를 한 문구로 합친 코드 — 둘을
    // 구분해 주면 클라이언트가 시한 만료 시각을 역산할 수 있고, 어느 쪽이든 다음 '오늘의 퀴즈'로
    // 다시 받아 풀면 되므로 구분해서 얻는 것도 없다
    QUIZ_SUBMIT_NOT_ALLOWED(403, "오늘의 퀴즈로 받은 문제만 제한 시간 안에 제출할 수 있습니다."),
    // '오늘의 퀴즈'를 받을 수 없는 상태를 한 문구로 합친 코드 — 지목한 경기가 없음·오늘 경기가
    // 아님·내 응원 구단 경기가 아님·진행 중이 아님(경기 전/종료/취소)·이닝 미상이 전부 여기다.
    // 사유를 세분화하지 않는 이유는 좋아요(QUIZ_LIKE_NOT_ALLOWED)와 같다: 응답이 갈리면 어떤 조건에
    // 걸렸는지를 탐색으로 알아내 우회 경로를 찾을 수 있다(이 검증들이 곧 '이닝당 1회'의 전제다).
    QUIZ_NOT_SERVABLE(403, "경기가 진행 중일 때만 문제를 받을 수 있습니다."),
    // 아직 시작하지 않은 경기의 이닝별 결산 조회를 거절하는 코드.
    // ⚠ 위 QUIZ_NOT_SERVABLE 을 재사용하지 말 것 — 그 문구는 '문제를 받을 수 없다'는 출제 거절이라
    //   조회 거절에 쓰면 FE·사용자가 원인을 오해한다(같은 403 이어도 다른 사건이다).
    // 경기의 존재를 감추지 않는 것도 의도다 — 경기 일정은 공개 정보이고, 이 경로는 미존재를
    // GAME_NOT_FOUND(404)로 그대로 알린다(은닉 정책이 없다).
    GAME_NOT_STARTED(403, "아직 시작하지 않은 경기입니다."),

    // 400 Bad Request - 프로필 수정
    // 현재 비밀번호 오답에 INVALID_CREDENTIALS(401)를 재사용하지 않는다 — FE 인터셉터가 401을
    // "토큰 만료 → 재발급/로그아웃"으로 처리하면 비밀번호 오타 한 번에 로그아웃되고, 이 경로의
    // 인증 수단(access 토큰)은 이미 통과한 상태라 현재 비밀번호는 인증이 아니라 본문 검증 대상이다.
    INVALID_CURRENT_PASSWORD(400, "현재 비밀번호가 올바르지 않습니다."),
    SAME_AS_CURRENT_PASSWORD(400, "현재 비밀번호와 다른 비밀번호를 사용해 주세요."),
    // 자기 닉네임을 DUPLICATE_NICKNAME(409)으로 흡수하지 않는다 — "이미 사용 중"이라는 문구가
    // 자기 자신에 대해서는 거짓이다.
    SAME_AS_CURRENT_NICKNAME(400, "현재 닉네임과 다른 닉네임을 사용해 주세요."),

    // 400 Bad Request - 응원 선택
    SUPPORT_TEAM_REQUIRED(400, "응원하는 구단을 먼저 선택해 주세요."),
    PLAYER_NOT_IN_SUPPORT_TEAM(400, "응원하는 구단 소속 선수만 선택할 수 있습니다."),
    SUPPORT_PLAYER_LIMIT_EXCEEDED(400, "응원 선수는 최대 4명까지 선택할 수 있습니다."),

    // 400 Bad Request - 프로필 이미지 업로드
    PROFILE_IMAGE_REQUIRED(400, "프로필 이미지를 첨부해 주세요."),
    // 판정 근거는 확장자·요청 Content-Type이 아니라 파일 선두 바이트다(둘 다 클라이언트가 자유로이
    // 정하는 값이라 실행 파일을 a.png로 위장하는 것을 못 막는다).
    INVALID_PROFILE_IMAGE_FORMAT(400, "JPG, PNG, WEBP 이미지만 업로드할 수 있습니다."),
    // 가입 요청의 profileImgUrl 거절 사유 넷(temp 접두가 아님·형태가 어긋남·255자 초과·그 객체가
    // 버킷에 없음)을 한 문구로 합친 코드 — 사유를 나눠 주면 "그 EP가 실재하는가"를 응답으로 물어볼
    // 수 있게 되어 남의 EP 존재 여부를 탐색할 수 있다(QUIZ_LIKE_NOT_ALLOWED와 같은 계열의 은닉).
    INVALID_PROFILE_IMAGE_ENDPOINT(400, "유효하지 않은 프로필 이미지입니다."),
    // 값의 형식(UUID 등)은 검증하지 않는다 — 비어 있지만 않으면 통과다(서버가 발급하는 값이 아니다).
    INVALID_APP_ID(400, "앱 식별자가 필요합니다."),

    // 400 Bad Request - 소셜 로그인(OAuth)
    // "지원하지 않는다"는 판정의 근거가 둘이다: 우리가 아예 모르는 provider(apple 등)와, 아는데
    // 클라이언트 자격증명이 주입되지 않아 호출 자체가 불가능한 provider. 둘을 한 문구로 합치는 이유는
    // 사용자가 할 수 있는 일이 "다른 수단으로 로그인"으로 같기 때문이다(설정 누락은 서버 로그가 알린다).
    UNSUPPORTED_OAUTH_PROVIDER(400, "지원하지 않는 소셜 로그인입니다."),
    // ⚠ 이 검사는 provider 호출보다 반드시 앞이다 — 검증 없이 넘기면 인가코드를 임의 주소로 빼돌리는
    //   경로가 열린다. 대조는 접두 일치가 아니라 문자 그대로 완전 일치여야 한다
    //   (https://victoryfairy.com 을 접두로 검사하면 https://victoryfairy.com.evil.com 이 통과한다).
    INVALID_OAUTH_REDIRECT_URI(400, "허용되지 않은 리다이렉트 주소입니다."),
    // 가입 티켓·링크 티켓·입력 티켓 세 종류가 공용으로 쓴다. 티켓 종류를 문구로 가르지 않는 것은
    // 의도다 — 어느 티켓이 죽었든 사용자가 할 조치는 "소셜 로그인부터 다시"로 같고, 종류를 알려 주면
    // 티켓 문자열을 넣어 보는 것만으로 어느 단계의 티켓이 살아 있는지 탐색할 수 있다.
    INVALID_OAUTH_TICKET(400, "소셜 인증 정보가 만료되었습니다. 다시 로그인해 주세요."),
    // provider 가 이메일을 주지 않아 발급된 입력 티켓인데 본문에 이메일이 없는 경우.
    // EMAIL_NOT_VERIFIED("이메일 인증이 완료되지 않았습니다.")를 재사용하지 않는 이유는 그 문구가
    // 사용자가 주소를 이미 입력했다는 전제를 깔아 실제로 해야 할 일(주소 입력)을 가리기 때문이다.
    // 형식이 어긋난 값은 여기까지 오지 않는다 — DTO 의 @Email 이 잡는 일반 검증 400 이다
    // (링크 티켓 요청은 이 필드가 없어야 정상이라 @NotBlank 를 걸 수 없어 서비스가 판정한다).
    OAUTH_EMAIL_REQUIRED(400, "이메일 주소를 입력해 주세요."),

    // 401 Unauthorized - 소셜 로그인(OAuth)
    // 만료된 코드·이미 사용된 코드·provider 가 판정한 redirect URI 불일치 세 사유를 합친 코드.
    // 어느 쪽이든 클라이언트의 올바른 행동은 인가코드를 새로 받아 다시 시도하는 것 하나뿐이다.
    INVALID_OAUTH_CODE(401, "소셜 인증에 실패했습니다. 다시 시도해 주세요."),

    // 409 Conflict - 소셜 로그인(OAuth)
    // 이메일로 해석된 계정에 '같은 provider 의 다른 식별자' 연동이 이미 있는 경우.
    // 그대로 INSERT 하면 uk_users_oauth_link_account_provider 위반이라 어차피 실패한다 — 그 실패를
    // 500 이 아니라 안내 가능한 409 로 바꾸는 자리다.
    OAUTH_PROVIDER_ALREADY_LINKED(409, "이미 다른 소셜 계정이 연결되어 있습니다."),

    // 502 Bad Gateway - 소셜 로그인(OAuth)
    // 이 저장소 최초의 502. 500 으로 묶지 않는 이유는 원인이 이 서버가 아니라 외부 의존이고,
    // 클라이언트의 올바른 행동(같은 요청을 잠시 후 재시도)이 500 과 다르기 때문이다.
    OAUTH_PROVIDER_UNAVAILABLE(502, "소셜 로그인 제공자와 통신할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // 404 Not Found
    CHATROOM_NOT_FOUND(404, "존재하지 않는 채팅방입니다."),
    CHAT_MESSAGE_NOT_FOUND(404, "존재하지 않는 메시지입니다."),
    TEAM_NOT_FOUND(404, "존재하지 않는 구단입니다."),
    PLAYER_NOT_FOUND(404, "존재하지 않는 선수입니다."),
    GAME_NOT_FOUND(404, "존재하지 않는 경기입니다."),
    // 미편성(quiz_date NULL) 풀 문제도 404 — 편성 전 문제의 존재는 외부에 노출하지 않는다
    QUIZ_NOT_FOUND(404, "존재하지 않는 퀴즈입니다."),

    // 400 Bad Request - 퀴즈 제출
    QUIZ_OPTION_NOT_FOUND(400, "존재하지 않는 보기 번호입니다."),

    // 409 Conflict - 퀴즈 제출
    QUIZ_ALREADY_SUBMITTED(409, "이미 제출한 퀴즈입니다."),
    // 409 Conflict - 퀴즈 서빙(한 이닝에 한 세트)
    // 403(QUIZ_NOT_SERVABLE)과 합치지 않는다 — "다음 이닝에 다시 오세요"와 "경기가 진행 중일 때만"은
    // 전혀 다른 안내다. 상태코드가 갈리는 근거도 다르다: 이쪽은 잠시 뒤 같은 요청이 성공하는
    // 일시적 상태 충돌(409)이고, 저쪽은 지금 그 자원을 받을 자격이 없는 것(403)이다.
    QUIZ_ALREADY_SERVED_IN_INNING(409, "이번 이닝에는 이미 문제를 받았습니다."),

    // 413 Content Too Large - 프로필 이미지 업로드
    // 이 저장소 최초의 413. 400으로 묶지 않는 이유: 고칠 대상이 입력값의 "형식"이 아니라 요청 본문의
    // "크기"이고, 이 응답은 컨트롤러에 닿기도 전에 멀티파트 해석 단계에서 나간다(web-support의
    // GlobalExceptionHandler.handleMaxUploadSizeExceeded).
    PROFILE_IMAGE_TOO_LARGE(413, "이미지 크기는 5MB를 넘을 수 없습니다."),

    // 429 Too Many Requests - 이메일 인증
    EMAIL_SEND_COOLDOWN(429, "인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요."),

    // 429 Too Many Requests - 닉네임 변경 쿨다운
    // 400으로 두지 않는 이유: 이 경로의 400은 data.nickname에 필드 메시지가 실리는 형식 오류라
    // FE·사용자가 "입력을 고치면 된다"로 오해한다(고칠 입력이 없다). 409도 아니다 — 충돌하는
    // 상대 자원이 없고 막는 주체는 내 계정의 시간 제한이다(EMAIL_SEND_COOLDOWN과 같은 성격).
    // ⚠ 메시지의 "30"은 기간의 단일 출처인 NicknameChangeCooldownPolicy.COOLDOWN_DAYS와 같은 값이어야
    //   한다 — :common은 앱 모듈을 참조할 수 없어 상수로 조립할 수 없으니 한쪽만 고치지 말 것.
    NICKNAME_CHANGE_COOLDOWN(429, "닉네임은 30일에 한 번만 변경할 수 있습니다."),

    // 429 Too Many Requests - 비인증 프로필 이미지 업로드(appId 기준 10회)
    // 창이 30분 고정이라 "잠시 후"가 문자 그대로 사실이다 — 갱신형(sliding)으로 바꾸면 계속 올리는
    // 사용자가 영영 풀리지 않아 이 문구가 거짓이 된다.
    PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED(429, "이미지 등록 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요."),

    // 500 Internal Server Error - 처리되지 않은 예외의 최종 방어선
    // (web-support GlobalExceptionHandler.handleUnexpected). 이 코드를 BusinessException 으로 던지지 말 것 —
    // "예상하고 정의한 실패"에 500 을 쓰는 순간 이 문구가 거짓이 되고 로그도 남지 않는다.
    // ⚠ 원인을 문구로 나누지 않는 것이 의도다: 예외 클래스명·내부 경로·SQL 은 응답이 아니라 서버 로그에만
    //   남긴다(응답에 실으면 스택트레이스를 감춘 의미가 없어진다).
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
