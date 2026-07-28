package com.skhynix.user.team.service;

import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구단(팀) 조회 전용 서비스. 구단 데이터는 py-collector·시드 SQL 이 소유하므로 쓰기 경로는 두지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamService {

    private final TeamRepository teamRepository;

    /**
     * 전체 구단을 {@code name} 오름차순으로 반환한다. 정렬은 리포지토리(DB)가 수행하며 여기서 다시
     * 정렬하지 않는다. 행이 없으면 빈 리스트다(예외 아님).
     */
    public List<TeamResponse> getTeams() {
        return teamRepository.findAllByOrderByNameAsc()
                .stream()
                .map(TeamResponse::from)
                .toList();
    }
}
