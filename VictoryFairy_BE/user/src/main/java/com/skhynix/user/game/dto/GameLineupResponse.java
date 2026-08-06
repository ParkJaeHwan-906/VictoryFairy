package com.skhynix.user.game.dto;

import com.skhynix.domain.game.entity.GameLineup;
import java.util.List;

/**
 * 한 경기의 선발 라인업을 팀 단위로 묶은 응답. 응답 배열의 항목 하나가 한 팀이다.
 *
 * <p>선수가 어느 팀인지는 그룹 소속이 결정하므로 항목마다 {@code teamId} 를 반복하지 않는다.
 * 홈/원정 구분은 서버가 하지 않고 {@code GET /games} 의 {@code homeTeamId}/{@code awayTeamId} 와
 * 이 {@code teamId} 를 맞춰 클라이언트가 판정한다(라인업 조회가 경기의 홈/원정까지 알 필요가 없도록).
 *
 * <p>항목 DTO 2종을 별도 파일로 흩지 않고 중첩한 이유는 셋이 하나의 응답 계약이라서다.
 */
public record GameLineupResponse(Long teamId,
                                 List<Pitcher> pitchers,
                                 List<Batter> batters) {

    /**
     * {@code positionName} 은 {@code position_id} 가 NULL 이면 {@code null} 그대로 나간다
     * ({@code GameResponse.stadium} 과 같은 처리) — 서버가 {@code "-"} 같은 표시용 대체 문자열을 정하면
     * 화면마다 표기가 달라질 때 계약을 다시 바꿔야 한다. 값 자체는 {@code positions.name}({@code "투수"}·
     * {@code "지명타자"})을 가공 없이 내보낸다 — 수집 단계에서 이미 화면에 쓸 정식 명칭으로 적재하므로
     * 서버가 변환을 떠안지 않는다(매핑에 없는 표기는 원문 적재되는 열린 집합, {@link com.skhynix.domain.game.entity.Position} 참고).
     */
    public record Pitcher(String name, String positionName) {

        public static Pitcher from(GameLineup lineup) {
            return new Pitcher(lineup.getPlayer().getName(), positionNameOf(lineup));
        }
    }

    public record Batter(String name, String positionName, Integer batOrder) {

        public static Batter from(GameLineup lineup) {
            return new Batter(lineup.getPlayer().getName(), positionNameOf(lineup), lineup.getBatOrder());
        }
    }

    private static String positionNameOf(GameLineup lineup) {
        return lineup.getPosition() == null ? null : lineup.getPosition().getName();
    }
}
