package com.skhynix.user.cleanup.service;

import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.cleanup.policy.UnknownAccountPolicy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동할 때 {@code (알수없음)} 더미 계정이 없으면 만든다(find-or-create).
 *
 * <p>시드 SQL 이 아니라 앱이 만드는 이유: 이 계정이 없으면 만료 데이터 정리가 <b>계정 삭제 단계를
 * 통째로 멈춘다.</b> 적용을 잊기 쉬운 수동 SQL 에 그 전제를 걸어 두면, 아무도 모르는 채로 정리가
 * 몇 달 동안 안 도는 상태가 될 수 있다.
 *
 * <p>스케줄러 실행 스위치와 <b>무관하게</b> 항상 돈다. 스위치가 꺼진 환경에서도 이 계정은 있어야
 * 한다 — 켜는 순간 바로 필요해지고, 없으면 그날 회차가 그대로 스킵된다.
 *
 * <p>⚠ 완전히 빈 DB 에 파드가 <b>동시에</b> 뜨면 UNIQUE(email·tel·uid) 충돌로 한쪽 기동이 실패할 수
 * 있다. 예외를 삼키지 않는 것은 의도다 — 재시작하면 앞선 파드가 넣은 행이 보여 통과하므로 자가
 * 치유되며, 조용히 넘어가면 "계정이 없는데 기동은 성공한" 상태가 만들어진다({@code sql.init} 시드가
 * 이미 같은 성질을 갖고 있고, 초기 구축은 파드 1개로 먼저 띄우는 것이 기존 운영 규칙이다).
 */
@Component
@RequiredArgsConstructor
public class UnknownAccountBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UnknownAccountBootstrapper.class);

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userAccountRepository.findByUid(UnknownAccountPolicy.UID).isPresent()) {
            return;
        }
        // users 행은 따로 확인한다 — 계정 행만 사라진 상태(수동 정리 등)에서 그냥 INSERT 하면
        // email·tel UNIQUE 에 걸려 기동이 실패한다.
        User user = userRepository.findByEmail(UnknownAccountPolicy.EMAIL)
                .orElseGet(() -> userRepository.save(User.builder()
                        .name(UnknownAccountPolicy.NAME)
                        .tel(UnknownAccountPolicy.TEL)
                        .email(UnknownAccountPolicy.EMAIL)
                        .gender(UnknownAccountPolicy.GENDER)
                        .build()));
        userAccountRepository.save(UserAccount.reserved(
                UnknownAccountPolicy.UID, user, UnknownAccountPolicy.NICKNAME,
                UnknownAccountPolicy.LOCKED_PASSWORD));
        // 닉네임·이메일은 로그에 남기지 않는다(정리 경로 전반의 규칙) — uid 만으로 충분히 특정된다.
        log.info("탈퇴자 데이터 이관용 더미 계정 생성: uid={}", UnknownAccountPolicy.UID);
    }
}
