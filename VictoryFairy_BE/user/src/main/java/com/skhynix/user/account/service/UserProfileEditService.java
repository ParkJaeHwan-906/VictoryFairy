package com.skhynix.user.account.service;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.dto.NicknameChangeCooldownResponse;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.NicknameChangeCooldownPolicy;
import com.skhynix.user.auth.service.AuthService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 프로필(닉네임·비밀번호) 수정 전용 서비스.
 *
 * <p>조회 전용인 {@code UserProfileService}(클래스 레벨 readOnly)에 얹지 않았다 — 거기에 쓰기 경로를
 * 넣는 순간 "조회는 행을 만들지 않는다"는 계약이 클래스 모양으로는 표현되지 않는다. 탈퇴
 * ({@code UserAccountService})와도 협력자 필요 범위가 갈린다: 탈퇴는 {@code UserRefreshTokenRepository}를
 * 직접 쓰고 여기는 안 쓰며(토큰 절차는 {@code AuthService}에 위임), 여기는 {@code PasswordEncoder}·
 * {@code AuthService}·{@code Clock}을 쓰고 탈퇴는 안 쓴다(공유는 {@code UserAccountRepository} 하나).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserProfileEditService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    // 토큰 발급 절차(기존 유효 refresh 만료 → 새 쌍 발급)를 복제하지 않고 그대로 재사용한다.
    private final AuthService authService;
    private final Clock clock;

    /**
     * 판정 순서 ①길이 → ②문자 구성 → ③현재 닉네임과 동일 → ④타 계정 점유 → ⑤쿨다운, 첫 위반 하나만
     * 응답한다. ①②는 {@code @ValidNickname}이 컨트롤러 진입 전에 끝내므로 여기 도달하면 형식은 이미
     * 통과다.
     *
     * <p>⑤가 마지막인 것이 핵심이다 — 앞에 두면 형식이 틀린 요청에도 쿨다운 메시지가 나가 사용자가
     * 진짜 원인을 끝내 못 본다. 파생 계약 하나가 여기서 나온다: <b>쿨다운 중인 계정이 이미 점유된
     * 닉네임을 요청하면 429가 아니라 409</b>다(둘 다 성립해도 ④가 먼저다).
     */
    public void updateNickname(Long userAccountId, String nickname) {
        UserAccount account = loadAccount(userAccountId);

        if (nickname.equals(account.getNickname())) {
            throw new BusinessException(ErrorCode.SAME_AS_CURRENT_NICKNAME);
        }

        // 중복 판정에 "본인 제외"를 넣지 않는다 — 같은 값은 바로 위에서 400으로 잘려 여기 도달하지
        // 못하므로, signup·/nickname/validate 와 문자 그대로 같은 판정(existsByNickname)을 계속 쓴다.
        // 순서를 뒤집으면 이 전제가 깨져 자기 닉네임 요청이 409가 된다.
        if (authService.isNicknameDuplicated(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        // 운영 파드가 UTC 라 LocalDateTime.now() 대신 Clock(Asia/Seoul 고정) 빈에서 읽는다. 저장 값도
        // 판정 기준도 표기도 전부 이 한 시계에서 나와야 존이 어긋나지 않는다(컬럼이 벽시계라 그렇다).
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime lastChanged = account.getNicknameChangedAt();
        if (NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, now)) {
            // 이 저장소에서 실패 응답에 data 를 싣는 첫 지점 — "왜 막혔는지"만으로는 사용자가 언제
            // 다시 시도해야 할지 알 방법이 없어 BusinessException 이 아니라 하위 타입으로 던진다.
            throw new BusinessDataException(ErrorCode.NICKNAME_CHANGE_COOLDOWN,
                    NicknameChangeCooldownResponse.of(
                            NicknameChangeCooldownPolicy.nextChangeableAt(lastChanged), clock.getZone()));
        }

        // 교체와 시각 기록은 한 전이다 — 실패 경로는 전부 이 줄 앞에서 예외로 끝나므로 성공했을 때만
        // 기록된다.
        account.changeNickname(nickname, now);
    }

    /**
     * 판정 순서 ①새 비밀번호 형식 → ②현재 비밀번호 일치 → ③신·구 동일, 첫 위반 하나만 응답한다.
     * ①은 {@code @ValidPassword}가 컨트롤러 진입 전에 끝낸다(형식 위반이면 bcrypt 를 돌리지 않는다).
     *
     * <p><b>닉네임과 달리 변경 간격 제한(쿨다운)이 없다 — 의식적인 비대칭이다.</b> 비밀번호가 새어
     * 나갔을 때 사용자의 자구책은 사실상 즉시 교체 하나뿐이라, 그 수단을 잠그는 제한은 막으려는 남용보다
     * 피해가 크다. 닉네임 쿨다운이 보호하는 대상은 다른 사용자(사칭·세탁)이고 여기서 보호할 대상은
     * 계정 주인이다. "일관성" 명목으로 붙이지 말 것.
     *
     * <p>성공 시 기존 유효 refresh 토큰을 모두 만료시키고 새 토큰 쌍을 돌려주며, <b>변경 이전에 발급된
     * access·refresh 토큰도 즉시 무효화된다</b> — 계정에 기준 시각을 적어 두고 필터·재발급이 토큰의
     * {@code iat}를 그 값과 대조한다.
     */
    public TokenResponse updatePassword(Long userAccountId, String currentPassword, String newPassword) {
        UserAccount account = loadAccount(userAccountId);

        // currentPassword 에는 검증 애노테이션이 없어 null 로 들어올 수 있다 — matches()에 넘기면 NPE 라
        // 여기서 "불일치"로 흡수한다(위반이 항상 1개여야 판정 순서가 성립한다).
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }

        // 위 검사를 통과한 currentPassword 가 곧 현재 비밀번호의 평문이므로 문자열 비교로 충분하다
        // (해시 대조를 한 번 더 돌릴 이유가 없다). 재해싱조차 하지 않고 거절한다.
        if (currentPassword.equals(newPassword)) {
            throw new BusinessException(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        }

        // 토큰 무효화 기준 시각만은 Clock 빈이 아니라 Instant.now() 에서 읽는다. 이 값은 표시용 벽시계가
        // 아니라 토큰의 iat 와 직접 비교되는 절대 시각이라 존을 고를 일 자체가 없어야 하고, 대조 상대인
        // iat 가 System.currentTimeMillis() 에서 나오므로 같은 시스템 시계에서 읽어야 "기록 → 발급"
        // 순서가 값의 순서로도 보장된다(주입된 Clock 이 고정·오프셋 시계면 그 보장이 깨진다).
        // 아래 issueTokens 보다 반드시 먼저 읽는다 — 뒤집으면 방금 발급한 토큰이 스스로 무효화된다.
        account.changePassword(passwordEncoder.encode(newPassword), Instant.now().getEpochSecond());

        // 실패 경로는 전부 이 줄 앞에서 예외로 끝난다 — 그래서 실패 시 기준 시각 기록도 refresh 만료도
        // 토큰 발급도 없다. 운영 파드가 UTC 라 LocalDateTime.now() 를 직접 읽지 않고 Clock 빈을 쓴다.
        return authService.issueTokens(account, LocalDateTime.now(clock));
    }

    // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증 근거가
    // 사라진 것이므로 필터가 못 찾았을 때와 같은 401로 맞춘다(UserAccountService.withdraw 와 동일).
    private UserAccount loadAccount(Long userAccountId) {
        return userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
