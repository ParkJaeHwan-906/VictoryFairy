package com.skhynix.chat.shared.redis;

import com.skhynix.chat.shared.ChatClock;
import java.time.Duration;
import java.time.LocalDate;

/**
 * 이 모듈이 쓰는 Redis 키 전부. 모든 키는 TTL 을 갖는다(CHAT-GC-25).
 *
 * <p>⚠ 자정 정리는 {@link #rooms} 집합을 읽어 방별 키를 이름으로 지운다 — {@code KEYS}/{@code SCAN} 으로 찾지 않는다
 * (CHAT-GC-16). 그래서 방 단위 키를 새로 만들면 {@code RoomLifecycleJob} 의 정리 목록에도 추가해야 한다.
 * dedup·rate 키는 TTL 로만 사라지므로 정리 대상이 아니다.
 */
public final class ChatRedisKeys {

    /** 오늘 방 집합의 수명 — 그날 00:00 기준 48시간 뒤 절대 시각으로 건다. */
    public static final Duration ROOMS_SET_TTL = Duration.ofHours(48);
    /** 자정 생성 방 메타의 수명(CHAT-GC-17). 지연 생성은 다음 자정까지 남은 초를 쓴다. */
    public static final Duration ROOM_META_TTL = Duration.ofSeconds(86_400);

    /** dedup 선점 값. 확정되면 {@code {"msgId":..,"content":..}} JSON 으로 바뀐다(CHAT-GC-106). */
    public static final String DEDUP_PENDING = "PENDING";

    private static final String PREFIX = "chat:";

    private ChatRedisKeys() {
    }

    /** Set(gameId) — 그날 만들어진 방 목록. 자정 정리의 유일한 근거다. */
    public static String rooms(LocalDate date) {
        return PREFIX + "rooms:" + ChatClock.roomDate(date);
    }

    /** String — 방 메타. 내용은 계약이 아니고 존재 여부만 계약이다. */
    public static String room(String gameId) {
        return PREFIX + "room:" + gameId;
    }

    /** Stream — 히스토리 사본. 엔트리 id 는 {@code {offset}-1}({@link #entryId}). */
    public static String stream(String gameId) {
        return PREFIX + "game:" + gameId;
    }

    /** Set(msgId) — blind 된 메시지. */
    public static String blind(String gameId) {
        return PREFIX + "blind:" + gameId;
    }

    /** String — 전송 멱등 키. */
    public static String dedup(String gameId, String clientMsgId) {
        return PREFIX + "dedup:" + gameId + ":" + clientMsgId;
    }

    /** String(정수) — 사용자 단위 1초 고정 창 속도 제한. 방과 무관하게 한 창을 공유한다. */
    public static String rate(Long userAccountId) {
        return PREFIX + "rate:" + userAccountId;
    }

    /**
     * msgId(Kafka 오프셋) → Stream 엔트리 id {@code {offset}-1}.
     *
     * <p>⚠ 시퀀스를 0 으로 두면 안 된다. 각 파티션의 첫 레코드(offset 0)가 {@code 0-0} 이 되는데 Redis 는
     * {@code 0-0} 을 XADD 로 받지 않는다("must be greater than 0-0"). 그 레코드에서 history-writer 가 영원히 재시도한다.
     * msgId 는 그대로 offset 이고 외부 계약(SSE id·커서·Last-Event-ID·신고 경로)에는 시퀀스가 드러나지 않는다.
     */
    public static String entryId(long msgId) {
        return msgId + "-1";
    }

    /**
     * 범위 경계용 <b>불완전 id</b>(시퀀스 생략). Redis 는 범위 시작에 쓰면 {@code {msgId}-0}, 끝에 쓰면
     * {@code {msgId}-<최대 시퀀스>} 로 해석한다 — 그래서 시퀀스 값과 무관하게 "그 offset 의 엔트리 전부"를 경계로 잡는다.
     */
    public static String boundary(long msgId) {
        return String.valueOf(msgId);
    }

    /** Stream 엔트리 id → msgId. 시퀀스 부분은 offset 과 무관해 버린다. */
    public static long msgIdOf(String entryId) {
        int dash = entryId.indexOf('-');
        return Long.parseLong(dash < 0 ? entryId : entryId.substring(0, dash));
    }
}
