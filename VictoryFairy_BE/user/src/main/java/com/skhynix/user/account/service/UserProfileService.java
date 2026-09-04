package com.skhynix.user.account.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.character.entity.UserCharacterInventory;
import com.skhynix.domain.character.repository.UserCharacterInventoryRepository;
import com.skhynix.domain.character.repository.UserCharacterItemInventoryRepository;
import com.skhynix.domain.quiz.repository.QuizSubmitAccuracyView;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.character.dto.EquippedCharacterItemResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.ranking.service.BqRankingService;
import com.skhynix.user.support.service.SupportService;
import com.skhynix.user.team.dto.TeamResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileService {

    // 응답 계약이 정한 자릿수다 — 여기서만 반올림하므로 이 상수가 단일 출처다.
    private static final int ACCURACY_SCALE = 3;

    private final UserAccountRepository userAccountRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserBqRepository userBqRepository;
    // 응원 선수 목록은 리포지토리를 직접 잡지 않고 위임한다 — fetch join 조회가 이미 거기 있고,
    // 같은 목록을 두 곳에서 만들면 한쪽만 고쳐질 때 응원 API 응답과 프로필 응답이 갈라진다.
    private final SupportService supportService;
    private final UserCharacterInventoryRepository characterInventoryRepository;
    private final UserCharacterItemInventoryRepository characterItemInventoryRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    // 순위 규칙은 랭킹 서비스 한 곳에만 둔다 — 여기서 count 를 직접 하면 /rankings/bq/me 와 갈라질 수 있다.
    private final BqRankingService bqRankingService;

    // DTO 조립을 이 트랜잭션 안에서 끝낸다 — 컨트롤러로 옮기면 LAZY 프록시가 open-in-view: false 인
    // prod 에서만 LazyInitializationException 으로 터진다(dev 는 이 설정이 없어 로컬에서는 통과한다).
    public UserAccountResponse getMyProfile(Long userAccountId) {
        // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증 근거가
        // 사라진 것이므로 필터가 못 찾았을 때와 같은 401로 맞춘다(UserAccountService.withdraw 와 동일).
        UserAccount account = userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        // 응원 구단이 없으면 예외가 아니라 null 이다(안전망) — 활성 구단 행이 2개 이상인
        // 깨진 데이터는 리포지토리가 예외로 드러낸다.
        Optional<UserSupportTeam> activeSupport = userSupportTeamRepository
                .findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId);
        TeamResponse supportTeam = activeSupport
                .map(support -> TeamResponse.from(support.getTeam()))
                .orElse(null);

        // 응원 구단 유무를 따지지 않는다 — 응원 선수 조회는 구단 행을 전제하지 않아 구단 미선택 계정도
        // 예외 없이 빈 목록으로 떨어진다(supportTeam == null 인 안전망 경로가 그대로 200을 유지한다).
        List<PlayerResponse> supportPlayers = supportService.currentSupportedPlayers(userAccountId);

        // 행이 없으면 0이다(안전망) — 배포~백필 사이 구간에서도 200을 유지하며, 행을 만들지는 않는다.
        long bqScore = userBqRepository.findByUserAccount_Id(userAccountId)
                .map(UserBq::getBqScore)
                .orElse(0L);

        // 캐릭터를 못 받은 계정이면 null 이다(안전망) — 지급이 건너뛰어졌을 수 있다
        // (DefaultCharacterGrantService).
        String characterImgUrl = characterInventoryRepository
                .findWithCharacterByUserAccount_IdAndActiveIsTrue(userAccountId)
                .map(UserCharacterInventory::getCharacter)
                .map(character -> character.getImg())
                .orElse(null);

        // 부위 id 순 정렬이 곧 겹치는 순서다 — 없으면 같은 착용 상태가 요청마다 다른 순서로 나가
        // 클라이언트가 레이어를 뒤집어 그릴 수 있다.
        List<EquippedCharacterItemResponse> characterItems = characterItemInventoryRepository
                .findAllByUserAccount_IdAndActiveIsTrue(userAccountId).stream()
                .sorted(Comparator.comparing(item -> item.getCharacterItem().getItemType().getId()))
                .map(EquippedCharacterItemResponse::from)
                .toList();

        // 이미 읽은 bqScore 를 넘겨 순위 조회가 count 1 회로 끝나게 한다. 구단이 없으면 null 이다(안전망) —
        // 구단 행은 위에서 이미 로딩됐으므로 team id 접근에 SELECT 가 붙지 않는다.
        Integer bqRank = activeSupport
                .map(support -> bqRankingService.rankOf(support.getTeam().getId(), bqScore))
                .orElse(null);

        return UserAccountResponse.of(account, supportTeam, supportPlayers, bqScore,
                characterImgUrl, characterItems, quizAccuracy(userAccountId), bqRank);
    }

    // 집계는 DB 가, 나눗셈과 반올림은 여기가 한다 — 나눗셈까지 SQL 로 밀면 반올림 방식이 방언에 딸려
    // 가고, 행을 끌어와 세면 제출 행 수만큼 비용이 늘어 "조회 1회" 제약이 무의미해진다.
    private BigDecimal quizAccuracy(Long userAccountId) {
        QuizSubmitAccuracyView counts = quizUserSubmitRepository.aggregateAccuracy(userAccountId);
        // 퀴즈를 한 번도 받지 않은 계정이다. 0으로 나누지 않고 0을 돌려준다(null·NaN 이 아니다) —
        // 이 분기가 correctCount 의 NULL(빈 집합 SUM)도 함께 막는다.
        if (counts.getTotalCount() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(counts.getCorrectCount())
                .divide(BigDecimal.valueOf(counts.getTotalCount()), ACCURACY_SCALE,
                        RoundingMode.HALF_UP)
                // 0.500 이 아니라 0.5 로 내보낸다 — 자릿수 패딩은 프론트엔드가 하고, 여기서 스케일을
                // 고정하면 값이 아니라 표기까지 서버가 정하게 된다.
                .stripTrailingZeros();
    }
}
