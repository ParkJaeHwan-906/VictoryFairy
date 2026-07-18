package com.skhynix.user.auth.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.NicknameValidationRequest;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.dto.PasswordValidationRequest;
import com.skhynix.user.auth.dto.PasswordValidationResponse;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.PasswordPolicy;
import com.skhynix.user.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 비밀번호 정책 사전 검사. 입력창에서 타이핑마다 호출되는 용도라 DB를 보지 않는다.
     *
     * <p>비밀번호가 URL·접근 로그에 남지 않도록 GET이 아닌 POST로 받는다. "정책 위반"은 검사가
     * 정상 수행된 결과이므로 400이 아니라 항상 200 + {@code valid:false}로 응답한다. 같은 이유로
     * 요청 DTO에 {@code @Valid}를 걸지 않는다(임의 문자열을 그대로 판정해야 한다).
     *
     * <p>판정 규칙은 {@link PasswordPolicy}가 단일 출처로 갖고 있다. 상태도 협력 객체도 없는 순수
     * 유틸이라 서비스 계층을 거치지 않고 직접 호출한다.
     */
    @PostMapping("/password/validate")
    public ResponseEntity<ApiResponse<PasswordValidationResponse>> validatePassword(
            @RequestBody PasswordValidationRequest request) {
        PasswordValidationResponse result = PasswordPolicy.findViolation(request.password())
                .map(PasswordValidationResponse::violated)
                .orElseGet(PasswordValidationResponse::passed);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 닉네임 사전 검사(정책 → 중복 2단 파이프라인). 회원가입 전 닉네임 사용 가능 여부를 미리 확인한다.
     *
     * <p>{@code /password/validate}와 달리 <b>중복 검사에 DB를 조회</b>하므로 순수 static 호출이 아니라
     * 서비스 계층({@link AuthService#validateNickname(String)})을 거친다. 정책·중복 두 단계는 서비스
     * 내부에서 별도 메서드로 분리돼 순서대로 호출된다(정책 위반 시 중복 검사 생략).
     *
     * <p>정책 위반도 중복도 "검사가 정상 수행된 결과"이므로 400/409가 아니라 <b>항상 200</b> +
     * {@code valid:false}로 응답한다(signup의 중복은 기존대로 409 유지 — 사전검사와 상태코드가 다른
     * 것은 의도된 설계). 같은 이유로 요청 DTO에 {@code @Valid}를 걸지 않는다(임의 문자열을 그대로
     * 판정해야 한다). 세 실패 종류(길이/문자/중복)는 응답 {@code message} 문구로만 구분한다.
     */
    @PostMapping("/nickname/validate")
    public ResponseEntity<ApiResponse<NicknameValidationResponse>> validateNickname(
            @RequestBody NicknameValidationRequest request) {
        NicknameValidationResponse result = authService.validateNickname(request.nickname());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 닉네임 중복 단독 검사. {@code /nickname/validate}가 정책→중복 2단계를 모두 도는 것과 달리, 이
     * 엔드포인트는 <b>중복(DB)만</b> 확인한다(정책 미검사). 정책 검사를 이미 마친 화면에서 "중복 확인"만
     * 다시 수행하는 용도다.
     *
     * <p>{@code /nickname/validate}와 동일하게 <b>항상 200</b> + {@code valid} 결과로 응답하고 요청에
     * {@code @Valid}를 걸지 않는다(임의 문자열 허용). 다만 정책을 보지 않으므로 {@code valid:true}는
     * "중복이 아니다"일 뿐 가입 가능 보장이 아니다({@link AuthService#checkNicknameDuplicate(String)}
     * 참고). signup의 중복은 기존대로 409를 유지한다.
     */
    @PostMapping("/nickname/duplicate")
    public ResponseEntity<ApiResponse<NicknameValidationResponse>> checkNicknameDuplicate(
            @RequestBody NicknameValidationRequest request) {
        NicknameValidationResponse result = authService.checkNicknameDuplicate(request.nickname());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/signup")
    public ResponseEntity<Boolean> signup(@Valid @RequestBody SignupRequest request) {
        Long userAccountId = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(true);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(authService.reissue(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody TokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
