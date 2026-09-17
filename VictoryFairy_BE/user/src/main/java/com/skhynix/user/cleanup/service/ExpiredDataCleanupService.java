package com.skhynix.user.cleanup.service;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.ExpiredAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.cleanup.policy.UnknownAccountPolicy;
import com.skhynix.user.cleanup.support.QuizLikeDeleteRuleInspector;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 한 회차의 정리를 지휘한다 — 대상 선정, 계정별 처리, 만료 토큰 삭제, 결과 로깅.
 *
 * <p><b>이 클래스에는 {@code @Transactional} 이 없다.</b> 트랜잭션은 계정 1건마다 하나씩
 * ({@link ExpiredAccountEraser}) 열린다. 회차 전체를 한 트랜잭션으로 묶으면 마지막 계정의 실패가
 * 앞선 999건을 되돌리고, 그 사이 계정 행들이 오래 잠긴다.
 *
 * <p>회차의 <b>기준 시각은 호출자가 넘긴 하나</b>다. 여기서 다시 "지금"을 읽지 않는다 — 30일 경과
 * 판정과 토큰 만료 판정이 서로 다른 시각을 보면, 경계에 걸친 데이터의 처리 결과가 회차 안에서
 * 어긋난다.
 *
 * <p>로그에는 uid 와 건수만 남는다. 이메일·전화번호·닉네임·비밀번호 해시는 이 경로 어디에서도
 * 읽지 않는다(대상 조회 자체가 그 컬럼들을 select 하지 않는다).
 */
@Service
@RequiredArgsConstructor
public class ExpiredDataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExpiredDataCleanupService.class);

    /**
     * 탈퇴 후 계정을 실제로 지우기까지의 보존 기간.
     *
     * <p>⚠ {@code NicknameChangeCooldownPolicy.COOLDOWN_DAYS}(닉네임 재변경 간격)와 <b>숫자만 같고
     * 정책이 다르다.</b> 하나로 합치면 한쪽 정책이 바뀔 때 다른 쪽이 말없이 따라 바뀐다.
     */
    private static final int RETENTION_DAYS = 30;

    private final UserAccountRepository userAccountRepository;
    private final ExpiredAccountEraser eraser;
    private final QuizLikeDeleteRuleInspector quizLikeDeleteRuleInspector;

    public void removeExpiredData(LocalDateTime baseTime) {
        log.info("만료 데이터 정리 시작 — 기준 시각 {}", baseTime);

        int chatrooms = 0;
        int chats = 0;
        int cancelledLikes = 0;
        int deletedAccounts = 0;
        int failedAccounts = 0;

        // 선행 조건이 하나라도 어긋나면 계정 삭제 단계는 통째로 건너뛴다. 만료 토큰 삭제는 이관·
        // 삭제와 아무 관계가 없으므로 그대로 진행한다.
        Optional<UserAccount> unknownAccount = findTransferTarget();
        if (unknownAccount.isPresent()) {
            List<ExpiredAccountView> targets = userAccountRepository.findExpiredAccounts(
                    baseTime.minusDays(RETENTION_DAYS), UnknownAccountPolicy.UID);
            for (ExpiredAccountView target : targets) {
                try {
                    AccountEraseResult result = eraser.erase(target, unknownAccount.get());
                    chatrooms += result.chatroomsTransferred();
                    chats += result.chatsTransferred();
                    cancelledLikes += result.cancelledLikesDeleted();
                    if (result.accountRemoved()) {
                        deletedAccounts++;
                    } else {
                        // 다른 파드가 먼저 끝낸 경우 — 실패가 아니다(락이 있어도 TTL·분단에서 겹칠 수 있다)
                        log.info("이미 삭제된 계정 — 건너뜀: uid={}", target.uid());
                    }
                } catch (RuntimeException e) {
                    // 한 계정의 실패는 그 계정에서 끝난다. 같은 회차에서 재시도하지 않는다 —
                    // 원인이 그대로면 재시도도 같은 결과이고, 대상은 다음 날 회차에 그대로 다시 잡힌다.
                    failedAccounts++;
                    log.error("계정 정리 실패 — 이 계정만 건너뜀: uid={}", target.uid(), e);
                }
            }
        }

        int deletedTokens = 0;
        try {
            deletedTokens = eraser.purgeExpiredTokens(baseTime);
        } catch (RuntimeException e) {
            // 별개 트랜잭션이라 여기서 실패해도 앞선 계정 삭제는 그대로 남는다.
            log.error("만료 refresh 토큰 삭제 실패 — 계정 처리 결과는 유지: 기준 시각 {}", baseTime, e);
        }

        log.info("만료 데이터 정리 완료 — 기준 시각 {}: 이관 chatrooms {}건·chats {}건, "
                        + "취소 추천 삭제 {}건, 계정 삭제 {}건, 실패 {}건, 만료 토큰 삭제 {}건",
                baseTime, chatrooms, chats, cancelledLikes, deletedAccounts, failedAccounts,
                deletedTokens);
    }

    /**
     * 이관 대상(더미 계정)을 찾고, 함께 계정 삭제의 선행 조건을 확인한다. 둘 중 하나라도 어긋나면
     * 비어 있는 값을 돌려 <b>삭제 단계 자체를 열지 않는다.</b>
     *
     * <p>FK 검사가 먼저인 이유는 없다 — 순서가 결과를 바꾸지 않는다. 다만 둘 다 "이 회차에서는 계정을
     * 지우면 안 된다"는 같은 결론으로 이어지고, 어느 쪽이든 <b>ERROR 로 남겨야</b> 사람이 알아챈다.
     * 정리는 무인 작업이라 이 로그가 유일한 신호다.
     */
    private Optional<UserAccount> findTransferTarget() {
        if (!quizLikeDeleteRuleInspector.isSetNull()) {
            log.error("quizzes_like 의 계정 FK 가 아직 ON DELETE SET NULL 이 아니다 — 계정 삭제 단계를 "
                    + "건너뛴다. infra/sql/migrate-quiz-like-account-set-null.sql 을 먼저 적용할 것"
                    + "(적용 전에 지우면 CASCADE 가 추천 행까지 지워 추천 수가 되돌릴 수 없이 줄어든다)");
            return Optional.empty();
        }
        Optional<UserAccount> unknownAccount =
                userAccountRepository.findByUid(UnknownAccountPolicy.UID);
        if (unknownAccount.isEmpty()) {
            log.error("이관용 더미 계정이 없다 — 계정 삭제 단계를 건너뛴다: uid={}", UnknownAccountPolicy.UID);
        }
        return unknownAccount;
    }
}
