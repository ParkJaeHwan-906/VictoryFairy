package com.skhynix.quiz.quiz.dto;

/**
 * 한 사용자·한 문제에 대한 좋아요 상태. 토글 응답 본문이자, 상세·이력 조립이 문제마다 들고 다니는
 * 값이다(같은 두 값을 세 경로가 쓰므로 타입을 하나로 둔다).
 *
 * <p><b>토글은 멱등이 아니므로 이 응답이 화면 상태의 정본이다</b> — 클라이언트가 타임아웃 후 재시도하면
 * 서버는 두 번의 토글로 받아 원상 복귀시킨다. 낙관적으로 버튼을 뒤집어 두면 실제 서버 상태와 어긋난다.
 *
 * @param liked 요청자 자신의 현재 좋아요 상태
 * @param likeCount 그 문제에 대해 {@code liked = true}인 행의 수. <b>행 수가 아니다</b> — 취소한 좋아요도
 *     행으로 남으므로 취소분은 세지 않는다
 */
public record QuizLikeResponse(boolean liked, long likeCount) {

    /** 좋아요 행이 없는(=누른 적 없는) 상태. 집계에 안 잡힌 문제를 0으로 채울 때 쓴다. */
    public static QuizLikeResponse none() {
        return new QuizLikeResponse(false, 0L);
    }
}
