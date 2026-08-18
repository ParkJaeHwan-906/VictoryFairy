package com.skhynix.user.auth.policy;

import java.time.LocalDateTime;

/**
 * 닉네임 재변경 간격(쿨다운) 정책의 단일 출처 — 기간을 다른 곳에 다시 적지 말 것.
 *
 * <p>형식 정책인 {@link NicknamePolicy}와 분리한 이유: 저쪽은 문자열만 보면 판정이 끝나 회원가입·사전검사가
 * 함께 쓰지만, 이쪽은 계정의 마지막 변경 시각이 있어야 판정할 수 있어 수정 경로만의 규칙이다.
 *
 * <p>판정은 존이 없는 {@link LocalDateTime}끼리의 비교다 — 두 값이 <b>같은 존에서 읽힌 벽시계</b>라는 것이
 * 전제이며, 그 전제를 지키는 책임은 호출자에게 있다(서비스가 {@code Clock} 빈 하나에서 "지금"을 읽고,
 * 저장된 값도 같은 시계로 기록됐다). {@code LocalDateTime.now()}(시스템 기본 존)를 섞으면 UTC 파드에서
 * 9시간 어긋난 판정이 나온다.
 */
public final class NicknameChangeCooldownPolicy {

    public static final int COOLDOWN_DAYS = 30;

    private NicknameChangeCooldownPolicy() {
    }

    /**
     * 지금 재변경이 막혀 있는지. {@code lastChangedAt}이 {@code null}(한 번도 안 바꿈)이면 제한이
     * 없다 — 정책은 소급되지 않으며, NULL은 결핍이 아니라 "아직 바꾼 적 없음"이라는 상태다.
     *
     * <p>경계는 <b>허용</b> 쪽이다: 마지막 변경으로부터 정확히 {@link #COOLDOWN_DAYS}일이 지난 시점부터
     * 통과한다(1초 모자라면 막힌다).
     */
    public static boolean isCoolingDown(LocalDateTime lastChangedAt, LocalDateTime now) {
        if (lastChangedAt == null) {
            return false;
        }
        return now.isBefore(nextChangeableAt(lastChangedAt));
    }

    public static LocalDateTime nextChangeableAt(LocalDateTime lastChangedAt) {
        return lastChangedAt.plusDays(COOLDOWN_DAYS);
    }
}
