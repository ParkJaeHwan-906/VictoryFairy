package com.skhynix.quiz.quizroom.weekly;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomWeek;
import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomCountView;
import com.skhynix.domain.quizroom.repository.QuizRoomLikeRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeekRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeeklyBestRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 주의 주간 베스트 확정과 보상 지급. 둘은 한 트랜잭션이다 — 지급 도중 실패하면 확정 결과도 남지 않아 다음 실행이 그 주를
 * 처음부터 다시 확정한다(확정은 됐는데 보상이 빠진 방이 생기지 않는다).
 *
 * <p>bq({@code users_bq})는 건드리지 않는다. 그래서 {@code users_bq} 락도 잡지 않으며 락 순서(계정 → {@code users_bq})와
 * 충돌할 경로가 없다.
 */
@Service
public class QuizRoomWeeklyBestService {

    static final int BEST_LIMIT = 5;
    static final int REWARD_POINT = 500;

    private final QuizRoomWeekRepository weekRepository;
    private final QuizRoomWeeklyBestRepository bestRepository;
    private final QuizRoomLikeRepository likeRepository;
    private final QuizRoomAnswerRepository answerRepository;
    private final QuizRoomRepository roomRepository;
    private final UserAccountRepository userAccountRepository;
    private final Clock kstClock;
    private final String unknownAccountUid;

    public QuizRoomWeeklyBestService(QuizRoomWeekRepository weekRepository,
            QuizRoomWeeklyBestRepository bestRepository, QuizRoomLikeRepository likeRepository,
            QuizRoomAnswerRepository answerRepository, QuizRoomRepository roomRepository,
            UserAccountRepository userAccountRepository, Clock kstClock,
            @Value("${quiz.room.weekly-best.unknown-account-uid}") String unknownAccountUid) {
        this.weekRepository = weekRepository;
        this.bestRepository = bestRepository;
        this.likeRepository = likeRepository;
        this.answerRepository = answerRepository;
        this.roomRepository = roomRepository;
        this.userAccountRepository = userAccountRepository;
        this.kstClock = kstClock;
        this.unknownAccountUid = unknownAccountUid;
    }

    /**
     * {@code weekStart} 주를 확정한다. 이미 확정된 주면 아무것도 하지 않는다.
     *
     * <p>다른 인스턴스가 같은 주를 동시에 확정하면 주 행 UNIQUE 위반({@code DataIntegrityViolationException})이 그대로
     * 던져지고 이 트랜잭션은 지급까지 전부 롤백된다 — 호출자가 "이미 확정됨"으로 다룬다.
     */
    @Transactional
    public ConfirmResult confirm(LocalDate weekStart) {
        if (weekRepository.existsByWeekStart(weekStart)) {
            return ConfirmResult.alreadyConfirmed();
        }
        LocalDateTime start = weekStart.atStartOfDay();
        LocalDateTime end = weekStart.plusWeeks(1).atStartOfDay();
        List<RankedRoom> ranked = rank(
                toMap(likeRepository.countWeeklyLikesByRoom(start, end)),
                toMap(answerRepository.countWeeklyNewSolversByRoom(start, end)));

        // 심판 행을 계정 락보다 먼저 넣고 flush 한다 — 늦은 인스턴스가 계정 락을 쥔 채 기다리지 않게(교착 여지 제거)
        QuizRoomWeek week = weekRepository.saveAndFlush(QuizRoomWeek.builder()
                .weekStart(weekStart)
                .confirmedAt(LocalDateTime.now(kstClock))
                .build());
        if (ranked.isEmpty()) {
            return ConfirmResult.confirmed(0, 0);
        }

        // author 그래프로 싣지 않는다: 계정 엔티티가 영속성 컨텍스트에 먼저 올라오면 뒤의 findWithLockById 가 행은 잠가도
        // 이미 올라온 엔티티의 point 를 새로 읽지 않아, 그 사이 커밋된 적립·구매가 덮여 사라진다. 프록시의 id 만 쓴다.
        Map<Long, QuizRoom> rooms = roomRepository.findAllById(ranked.stream().map(RankedRoom::roomId).toList())
                .stream().collect(Collectors.toMap(QuizRoom::getId, Function.identity()));
        Map<Long, UserAccount> owners = lockOwners(rooms.values());

        List<QuizRoomWeeklyBest> bests = new ArrayList<>(ranked.size());
        int rewarded = 0;
        for (int i = 0; i < ranked.size(); i++) {
            RankedRoom entry = ranked.get(i);
            QuizRoom room = rooms.get(entry.roomId());
            UserAccount owner = owners.get(room.getAuthor().getId());
            boolean eligible = owner != null && !owner.isWithdrawn() && !unknownAccountUid.equals(owner.getUid());
            if (eligible) {
                owner.addPoint(REWARD_POINT);
                rewarded++;
            }
            bests.add(QuizRoomWeeklyBest.builder()
                    .week(week)
                    .rankNo(i + 1)
                    .room(room)
                    .score(entry.score())
                    .rewardedAccount(eligible ? owner : null)
                    .rewardPoint(eligible ? REWARD_POINT : 0)
                    .build());
        }
        bestRepository.saveAll(bests);
        return ConfirmResult.confirmed(bests.size(), rewarded);
    }

    // 오름차순으로 잠근다. 지금은 확정 트랜잭션이 주 행 UNIQUE 로 한 번에 하나뿐이라 서로 엇갈릴 상대가 없지만, 순서를 고정해 두면
    // 여러 계정을 잠그는 경로가 늘어도 교착이 생기지 않는다
    private Map<Long, UserAccount> lockOwners(Iterable<QuizRoom> rooms) {
        TreeSet<Long> ownerIds = new TreeSet<>();
        rooms.forEach(room -> ownerIds.add(room.getAuthor().getId()));
        Map<Long, UserAccount> owners = new HashMap<>();
        for (Long ownerId : ownerIds) {
            userAccountRepository.findWithLockById(ownerId).ifPresent(account -> owners.put(ownerId, account));
        }
        return owners;
    }

    /** 점수 = 추천 + 새 풀이자. 0점 제외, 점수 내림차순·동점은 roomId 내림차순, 최대 5. */
    static List<RankedRoom> rank(Map<Long, Long> likes, Map<Long, Long> solvers) {
        Map<Long, Long> scores = new HashMap<>(likes);
        solvers.forEach((roomId, count) -> scores.merge(roomId, count, Long::sum));
        return scores.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new RankedRoom(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(RankedRoom::score).reversed()
                        .thenComparing(RankedRoom::roomId, Comparator.reverseOrder()))
                .limit(BEST_LIMIT)
                .toList();
    }

    private static Map<Long, Long> toMap(List<QuizRoomCountView> views) {
        return views.stream().collect(Collectors.toMap(QuizRoomCountView::roomId, QuizRoomCountView::count, Long::sum));
    }

    record RankedRoom(long roomId, long score) {
    }

    /** {@code confirmed=false} 면 이미 확정된 주라 아무것도 바꾸지 않았다는 뜻이다. */
    public record ConfirmResult(boolean confirmed, int rankedCount, int rewardedCount) {

        static ConfirmResult alreadyConfirmed() {
            return new ConfirmResult(false, 0, 0);
        }

        static ConfirmResult confirmed(int rankedCount, int rewardedCount) {
            return new ConfirmResult(true, rankedCount, rewardedCount);
        }
    }
}
