package com.skhynix.user.player.service;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.user.player.dto.PlayerResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선수 조회 전용 서비스. 선수 데이터는 py-collector 가 소유하므로 쓰기 경로는 두지 않는다
 * ({@code TeamService} 와 같은 이유로 클래스 레벨 {@code readOnly = true}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlayerService {

    private final PlayerRepository playerRepository;

    /**
     * 선수를 {@code name} 오름차순으로 반환한다. {@code teamId} 가 있으면 그 구단 소속만, {@code name} 이
     * 있으면 이름에 그 문자열이 포함된 선수만 남기며, 둘 다 있으면 AND 로 함께 적용한다. 정렬은
     * 리포지토리(DB)가 수행하며 여기서 다시 정렬하지 않는다.
     *
     * <p>존재하지 않는 {@code teamId} 는 404 가 아니라 빈 리스트다 — 구단 존재 확인에 조회를 한 번 더
     * 쓰는 대신, 이미 공개된 {@code GET /teams} 가 유효한 id 의 출처라는 전제를 따른다. 어떤 선수와도
     * 일치하지 않는 {@code name} 역시 같은 이유로 빈 리스트다.
     *
     * <p>필터링은 전부 DB 가 한 쿼리로 수행한다 — 전체를 읽어와 자바에서 거르면 정렬 기준이 앱과 DB 로
     * 갈라지고 불필요한 행까지 실어 나르게 된다.
     */
    public List<PlayerResponse> getPlayers(Long teamId, String name) {
        List<Player> players = findPlayers(teamId, normalizeKeyword(name));
        return players.stream()
                .map(PlayerResponse::from)
                .toList();
    }

    /**
     * 검색어를 정규화한다. 빈 문자열·공백만 있는 값은 {@code null}(= 검색어 없음)로 취급한다.
     *
     * <p>{@code ?name=} 처럼 값 없이 붙는 쿼리 파라미터는 Spring 이 {@code null} 이 아니라 빈 문자열로
     * 넘겨준다. 이를 그대로 {@code LIKE '%%'} 로 흘리면 "전체 조회"와 결과가 같으면서도 인덱스를 못 타는
     * 쿼리만 하나 더 도는 셈이라, 여기서 미리 없는 것으로 접는다.
     */
    private String normalizeKeyword(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim();
    }

    private List<Player> findPlayers(Long teamId, String keyword) {
        if (teamId == null) {
            return (keyword == null)
                    ? playerRepository.findAllByOrderByNameAsc()
                    : playerRepository.findAllByNameContainingOrderByNameAsc(keyword);
        }
        return (keyword == null)
                ? playerRepository.findAllByTeam_IdOrderByNameAsc(teamId)
                : playerRepository.findAllByTeam_IdAndNameContainingOrderByNameAsc(teamId, keyword);
    }
}
