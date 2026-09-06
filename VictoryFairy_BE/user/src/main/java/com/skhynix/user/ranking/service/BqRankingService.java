package com.skhynix.user.ranking.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.repository.BqRankingEntryView;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.user.ranking.dto.BqRankingResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응원 구단 안의 BQ 순위. 쓰기 경로가 없다 — 조회가 행을 만들지 않는다는 계약을 클래스 모양으로 둔다.
 * 순위 규칙(점수 내림차순·동점 공동 순위 1·1·3·동점자 id 오름차순)은 이 클래스와 그 뒤의 두 쿼리에만 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BqRankingService {

    private static final int TOP_LIMIT = 3;
    private static final int RANKING_LIMIT = 10;

    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserBqRepository userBqRepository;

    public List<BqRankingResponse> getTopRanking(Long userAccountId) {
        return ranking(userAccountId, TOP_LIMIT);
    }

    public List<BqRankingResponse> getRanking(Long userAccountId) {
        return ranking(userAccountId, RANKING_LIMIT);
    }

    /**
     * 본인 항목 1 개. 활성 응원 구단이 없으면 {@code null} — 예외가 아니다(빈 배열을 주는 목록 경로와 같은 안전망).
     */
    public BqRankingResponse getMyRanking(Long userAccountId) {
        Optional<Long> teamId = activeTeamId(userAccountId);
        if (teamId.isEmpty()) {
            return null;
        }
        // 필터가 활성 계정임을 확인한 id 라 정상 경로에서는 항상 있다. 그 사이 사라졌다면 인증 근거가
        // 사라진 것이므로 UserProfileService 와 같은 401 로 맞춘다.
        BqRankingEntryView entry = userBqRepository.findRankingEntry(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        return BqRankingResponse.of(rankOf(teamId.get(), entry.getBqScore()), entry);
    }

    /**
     * 그 구단 모집단에서 이 점수의 순위. 점수를 받는 이유는 {@code /me} 가 이미 읽은 값을 다시 읽지 않기
     * 위해서다 — 순위 계산을 여기 하나로 모아 두되 SELECT 는 count 1 회만 더한다.
     */
    public int rankOf(Long teamId, long bqScore) {
        // 모집단 크기가 int 를 넘을 일은 없다 — 한 구단의 응원 계정 수다.
        return (int) userBqRepository.countHigherInTeam(teamId, bqScore) + 1;
    }

    private List<BqRankingResponse> ranking(Long userAccountId, int limit) {
        Optional<Long> teamId = activeTeamId(userAccountId);
        if (teamId.isEmpty()) {
            return List.of();
        }
        return assignRanks(userBqRepository.findTeamRanking(teamId.get(), Limit.of(limit)));
    }

    private Optional<Long> activeTeamId(Long userAccountId) {
        // 구단명이 응답에 없어 @EntityGraph 변형은 안 쓴다 — id 접근은 프록시를 깨우지 않는다.
        return userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(UserSupportTeam::getTeam)
                .map(team -> team.getId());
    }

    // 목록이 1 위부터의 접두라는 전제에 기댄다 — 앞 항목과 점수가 같으면 그 순위를 잇고, 다르면 위치가 곧
    // 순위다(SQL RANK 와 동일). 상한 이후 페이지에는 이 방식이 틀리므로 페이징이 생기면 count 로 바꿔야 한다.
    private static List<BqRankingResponse> assignRanks(List<BqRankingEntryView> entries) {
        List<BqRankingResponse> result = new ArrayList<>(entries.size());
        int rank = 0;
        long previousScore = Long.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            BqRankingEntryView entry = entries.get(i);
            if (i == 0 || entry.getBqScore() != previousScore) {
                rank = i + 1;
            }
            previousScore = entry.getBqScore();
            result.add(BqRankingResponse.of(rank, entry));
        }
        return result;
    }
}
