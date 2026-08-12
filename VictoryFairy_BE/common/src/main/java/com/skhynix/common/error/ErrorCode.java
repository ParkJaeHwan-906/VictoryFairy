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

    // 400 Bad Request - 응원 선택
    SUPPORT_TEAM_REQUIRED(400, "응원하는 구단을 먼저 선택해 주세요."),
    PLAYER_NOT_IN_SUPPORT_TEAM(400, "응원하는 구단 소속 선수만 선택할 수 있습니다."),
    SUPPORT_PLAYER_LIMIT_EXCEEDED(400, "응원 선수는 최대 4명까지 선택할 수 있습니다."),

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

    // 429 Too Many Requests - 이메일 인증
    EMAIL_SEND_COOLDOWN(429, "인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요.");

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
