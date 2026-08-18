package com.skhynix.user.game.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameLineup;
import com.skhynix.domain.game.repository.GameLineupRepository;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.user.game.dto.GameLineupResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// GameService 와 같은 이유로 클래스 레벨 트랜잭션이 필수다 — prod 는 open-in-view: false 라
// 트랜잭션 밖에서 LAZY 연관을 건드리면 LazyInitializationException(500)이 난다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameLineupService {

    private final GameRepository gameRepository;
    private final GameLineupRepository gameLineupRepository;

    // gameId 는 내부 PK 가 아니라 Game.naverGameId 다 — 내부 PK 를 넘기면 매칭이 안 돼 404 가 난다.
    public List<GameLineupResponse> getLineup(String gameId) {
        Game game = gameRepository.findByNaverGameId(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));

        Map<Long, List<GameLineup>> pitchersByTeam =
                groupByTeamId(gameLineupRepository.findStarterPitchers(game.getId()));
        Map<Long, List<GameLineup>> battersByTeam =
                groupByTeamId(gameLineupRepository.findStarterBatters(game.getId()));

        // 그룹 키는 두 조회에 실제로 등장한 구단만 모은다 — 한 팀 라인업만 적재된 경기에서 반대 팀의
        // 빈 그룹을 만들어 내보내지 않기 위해서다(경기의 홈/원정을 여기서 읽지 않는 이유이기도 하다).
        Set<Long> teamIds = new TreeSet<>(pitchersByTeam.keySet());
        teamIds.addAll(battersByTeam.keySet());

        return teamIds.stream()
                .map(teamId -> new GameLineupResponse(teamId,
                        pitchersByTeam.getOrDefault(teamId, List.of()).stream()
                                .map(GameLineupResponse.Pitcher::from)
                                .toList(),
                        battersByTeam.getOrDefault(teamId, List.of()).stream()
                                .map(GameLineupResponse.Batter::from)
                                .toList()))
                .toList();
    }

    // groupingBy 기본 맵(HashMap)은 키 순서를 보장하지 않아 그룹 순서가 실행마다 흔들린다 — TreeMap 으로
    // teamId 오름차순을 고정한다. 그룹 안의 순서는 groupingBy 가 등장 순서를 유지하므로 리포지토리 정렬
    // (타자 batOrder 오름차순 / 투수 이름 오름차순)이 그대로 보존된다.
    // getTeam().getId() 는 FK 값이라 team 프록시를 초기화하지 않는다(그래서 team 은 fetch 대상이 아니다).
    private Map<Long, List<GameLineup>> groupByTeamId(List<GameLineup> lineups) {
        return lineups.stream().collect(Collectors.groupingBy(
                lineup -> lineup.getTeam().getId(), TreeMap::new, Collectors.toList()));
    }
}
