package com.skhynix.user.auth.policy;

import java.time.Duration;
import java.time.Instant;

/**
 * 닉네임 재변경 간격(쿨다운) 정책의 단일 출처 — 기간을 다른 곳에 다시 적지 말 것.
 *
 * <p>형식 정책인 {@link NicknamePolicy}와 분리한 이유: 저쪽은 문자열만 보면 판정이 끝나 회원가입·사전검사가
 * 함께 쓰지만, 이쪽은 계정의 마지막 변경 시각이 있어야 판정할 수 있어 수정 경로만의 규칙이다.
 *
 * <p>판정을 epoch 초 비교로만 하는 것이 핵심이다 — 실행 환경의 시간대(운영 파드는 UTC)가 결과에 끼어들
 * 여지가 없다. 시간대가 쓰이는 곳은 응답에 실을 다음 변경 가능 시각을 <b>표기</b>할 때뿐이다.
 */
public final class NicknameChangeCooldownPolicy {

    public static final int COOLDOWN_DAYS = 30;

    /** 2,592,000초. 리터럴을 따로 적지 않고 {@link #COOLDOWN_DAYS}에서 파생시킨다. */
    public static final long COOLDOWN_SECONDS = Duration.ofDays(COOLDOWN_DAYS).toSeconds();

    private NicknameChangeCooldownPolicy() {
    }

    /**
     * 지금 재변경이 막혀 있는지. {@code lastChangedEpochSecond}가 {@code null}(한 번도 안 바꿈)이면 제한이
     * 없다 — 정책은 소급되지 않으며, NULL은 결핍이 아니라 "아직 바꾼 적 없음"이라는 상태다.
     *
     * <p>경계는 <b>허용</b> 쪽이다: 마지막 변경으로부터 정확히 {@link #COOLDOWN_SECONDS}가 지난 시점부터
     * 통과한다(1초 모자라면 막힌다).
     */
    public static boolean isCoolingDown(Long lastChangedEpochSecond, long nowEpochSecond) {
        if (lastChangedEpochSecond == null) {
            return false;
        }
        return nowEpochSecond < lastChangedEpochSecond + COOLDOWN_SECONDS;
    }

    public static Instant nextChangeableAt(long lastChangedEpochSecond) {
        return Instant.ofEpochSecond(lastChangedEpochSecond + COOLDOWN_SECONDS);
    }
}
