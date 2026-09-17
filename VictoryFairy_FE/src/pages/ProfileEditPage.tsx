import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ApiError,
  changeNickname,
  changeProfileImage,
  checkNicknameDuplicate,
  getNicknameChangeableAt,
  isNicknameChangeCooldown,
  PROFILE_IMAGE_ACCEPT,
  toAssetUrl,
  toProfileImageMessage,
  validateProfileImageFile,
} from '../api';
import profilePlaceholder from '../assets/profile_img.svg';
import { ROUTES } from '../routes';
import { useAccountStore, useMyProfile } from '../stores/useAccountStore';
import type { NicknameValidationResponse } from '../types/auth';
import '../styles/ProfileEditPage.css';

/**
 * ProfileEditPage — 프로필 수정.
 * Figma: SWM / [My] 프로필 수정 (node 1138:8632)
 *
 * 마이페이지 프로필 사진의 연필 버튼으로 들어온다. 디자인에 NavBar 가 없어
 * 레이아웃 밖 전체 화면이고, 뒤로가기로 마이페이지로 돌아간다.
 *
 * 지금 이 화면이 실제로 바꾸는 것은 **닉네임 하나**다.
 * 사진 쪽은 아직 계약이 없다 — 아래 "프로필 사진" 주석 참고.
 *
 * ── 저장 후 다시 조회하는 이유 ────────────────────────────────────────
 * `PATCH /users/me/nickname` 은 204 무본문이라 **바뀐 닉네임이 응답에 없다**(docs/account.md).
 * 보낸 값을 그대로 스토어에 써 넣을 수도 있지만, 서버가 최종적으로 무엇을 저장했는지는
 * 조회가 말해 준다 — 성공 뒤 `fetchProfile()` 로 받아 스토어를 갱신한다.
 * ──────────────────────────────────────────────────────────────────────
 */

/**
 * 쿨다운(429) 안내에 쓰는 날짜 표기.
 *
 * 서버가 주는 `nextChangeableAt` 은 `+09:00` 오프셋이 붙은 ISO-8601 이고, 판정도 표기도
 * 고정 시간대(Asia/Seoul) 시계 하나에서 나온다(docs/account.md). 기기 시간대로 옮겨
 * 그리면 날짜가 하루 밀릴 수 있어 서울 기준으로 못 박는다.
 */
const COOLDOWN_DATE_FORMAT = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: 'long',
  day: 'numeric',
});

/** 저장 실패를 화면 문구로 옮긴다. 서버가 준 문구가 있으면 그대로 쓴다. */
function describeSaveError(error: unknown): string {
  /*
   * 쿨다운만 예외적으로 문구를 덧붙인다 — "30일에 한 번"이라는 규칙만으로는
   * 언제 다시 되는지 알 수 없는데, 그 답이 이 응답의 `data` 에 실려 온다.
   * (이 저장소에서 실패 응답의 `data` 가 null 이 아닌 유일한 사례다)
   */
  if (isNicknameChangeCooldown(error)) {
    const changeableAt = getNicknameChangeableAt(error);
    const at = changeableAt === null ? null : new Date(changeableAt);

    return at !== null && !Number.isNaN(at.getTime())
      ? `${COOLDOWN_DATE_FORMAT.format(at)}부터 다시 바꿀 수 있어요.`
      : (error as ApiError).message;
  }

  if (error instanceof ApiError) {
    /*
     * 길이·허용 문자 위반(400 Bean Validation)은 사유가 `message` 가 아니라
     * 필드 맵에 담긴다 — "입력값이 올바르지 않습니다" 대신 그 사유를 보여 준다.
     * 중복(409) · 현재 닉네임과 동일(400) 은 `message` 자체가 사용자용 문구다.
     */
    return error.fieldErrors?.nickname ?? error.message;
  }

  return '닉네임을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.';
}

export default function ProfileEditPage() {
  const navigate = useNavigate();
  const profile = useMyProfile();
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  const [nickname, setNickname] = useState('');
  /**
   * 중복확인 결과와 **그때 확인한 닉네임**.
   *
   * 확인 뒤에 글자를 고치면 그 결과는 더 이상 지금 값에 대한 답이 아니다 —
   * 어느 값을 확인했는지까지 들고 있어야 그걸 가려낼 수 있다(SignupPage 와 같은 방식).
   */
  const [duplicateCheck, setDuplicateCheck] = useState<
    (NicknameValidationResponse & { nickname: string }) | null
  >(null);
  const [isChecking, setIsChecking] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  /*
   * 프로필 사진.
   *
   * **올리는 순간 바뀐다** — 확정·취소 단계가 없는 계약이라, 아래 "저장" 버튼(닉네임)과
   * 묶지 않고 사진을 고르는 즉시 보낸다. 성공하면 전역 프로필을 다시 받아, 이 화면뿐
   * 아니라 마이페이지 · 라운지의 내 사진까지 함께 바뀌게 한다.
   */
  const [isUploadingImage, setIsUploadingImage] = useState(false);
  const [imageError, setImageError] = useState<string | null>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  /** 지금 사진. 서버는 EP 만 주므로 도메인을 붙이고, 없으면 자리표시 이미지를 쓴다. */
  const avatarUrl = toAssetUrl(profile?.profileImgUrl) ?? profilePlaceholder;

  const handleImageChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;

    // 값을 비워야 같은 파일을 다시 골랐을 때도 change 가 온다(실패 후 재시도가 그렇다).
    event.target.value = '';
    if (file === null) return;

    const invalid = validateProfileImageFile(file);
    if (invalid !== null) {
      setImageError(invalid);
      return;
    }

    setIsUploadingImage(true);
    setImageError(null);

    try {
      await changeProfileImage(file);
      /*
       * 응답이 새 EP 를 주지만 그대로 쓰지 않는다 — 정본은 `GET /users/me` 이고,
       * 전역 프로필을 갱신해야 다른 화면의 내 사진도 함께 바뀐다.
       */
      await fetchProfile();
    } catch (cause: unknown) {
      setImageError(toProfileImageMessage(cause));
    } finally {
      setIsUploadingImage(false);
    }
  };

  const currentNickname = profile?.nickname ?? '';

  /*
   * 입력칸은 지금 쓰는 닉네임에서 시작한다 — 바꾸러 온 화면이라 빈칸보다 현재 값이 맞다.
   * 새로고침으로 이 주소에 바로 들어오면 프로필이 아직 없을 수 있어, 도착한 뒤에 채운다.
   * 사용자가 이미 고쳐 놓은 글자를 늦게 온 응답이 덮지 않도록 한 번만 넣는다.
   */
  const [isSeeded, setIsSeeded] = useState(false);
  useEffect(() => {
    if (isSeeded || currentNickname === '') return;

    setNickname(currentNickname);
    setIsSeeded(true);
  }, [currentNickname, isSeeded]);

  const trimmedNickname = nickname.trim();
  /** 지금 값에 대한 확인 결과만 유효하다. 글자를 고치면 자동으로 없던 일이 된다. */
  const checkResult =
    duplicateCheck && duplicateCheck.nickname === trimmedNickname ? duplicateCheck : null;
  /** 바뀐 게 없으면 보낼 것도 없다 — 그대로 보내면 400(현재 닉네임과 동일)이다. */
  const isChanged = trimmedNickname.length > 0 && trimmedNickname !== currentNickname;
  /**
   * 중복확인을 강제하지는 않는다 — 저장 자체가 서버에서 다시 판정하므로(길이·문자·중복·쿨다운)
   * 미리 눌러야만 저장되는 화면은 왕복만 늘린다. 다만 **이미 중복이라고 답을 받은 값**은 막는다.
   */
  const canSave = isChanged && !isSaving && checkResult?.valid !== false;

  /**
   * 닉네임 중복확인. `POST /auth/nickname/duplicate` 는 중복이어도 200 이라
   * 실패가 아니라 `valid: false` 로 온다 — catch 로 가는 건 네트워크·서버 오류뿐이다.
   *
   * 이 엔드포인트는 **중복만** 본다(정책 미검사). 길이·문자 위반은 저장할 때 걸린다.
   */
  const handleDuplicateCheck = () => {
    if (!isChanged || isChecking) return;

    setIsChecking(true);
    setSaveError(null);

    checkNicknameDuplicate({ nickname: trimmedNickname })
      // 문구는 서버가 준 것을 그대로 쓴다. 판정과 문구의 출처를 하나로 둔다.
      .then((result) => setDuplicateCheck({ ...result, nickname: trimmedNickname }))
      .catch((cause: unknown) => {
        setDuplicateCheck({
          valid: false,
          message:
            cause instanceof Error
              ? cause.message
              : '닉네임을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.',
          nickname: trimmedNickname,
        });
      })
      .finally(() => setIsChecking(false));
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSave) return;

    setIsSaving(true);
    setSaveError(null);

    try {
      await changeNickname(trimmedNickname);
      // 204 라 새 닉네임이 응답에 없다 — 스토어는 조회로 채운다(머리말 참고).
      await fetchProfile();
      /*
       * replace 로 돌아간다 — 저장을 마친 폼은 뒤로가기로 되돌아올 자리가 아니다
       * (돌아와도 방금 바꾼 값이 다시 "현재 닉네임"이라 아무것도 못 한다).
       */
      navigate(ROUTES.my, { replace: true });
    } catch (cause: unknown) {
      setSaveError(describeSaveError(cause));
      setIsSaving(false);
    }
  };

  /*
   * 안내문은 할 말이 있을 때만 뜬다 — 디자인의 Textfield 도 sub 를 끈 상태다.
   * 저장 실패가 가장 최근 소식이므로 중복확인 결과보다 앞선다.
   */
  const hint = saveError ?? checkResult?.message ?? null;
  const isHintError = saveError !== null || checkResult?.valid === false;

  return (
    <div className="profile-edit-page">
      <header className="profile-edit-page__topbar">
        <button
          className="profile-edit-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="profile-edit-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="profile-edit-page__topbar-title">프로필 수정</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="profile-edit-page__topbar-spacer" aria-hidden="true" />
      </header>

      <form className="profile-edit-page__form" onSubmit={handleSubmit} noValidate>
        {/*
          프로필 사진. ＋ 배지가 파일 선택창을 열고, 고르는 즉시 올라가 바뀐다
          (별도 확정 단계가 없는 계약이라 아래 "저장"과 묶지 않는다).
        */}
        <div className="profile-edit-page__avatar-box">
          <img className="profile-edit-page__avatar" src={avatarUrl} alt="" />

          <button
            className="profile-edit-page__avatar-edit"
            type="button"
            aria-label="프로필 사진 바꾸기"
            onClick={() => imageInputRef.current?.click()}
            disabled={isUploadingImage}
          >
            <span className="profile-edit-page__avatar-edit-icon" aria-hidden="true" />
          </button>

          <input
            ref={imageInputRef}
            type="file"
            accept={PROFILE_IMAGE_ACCEPT}
            onChange={(event) => void handleImageChange(event)}
            hidden
          />
        </div>

        {(isUploadingImage || imageError !== null) && (
          <p
            className={`profile-edit-page__image-status${
              imageError !== null ? ' profile-edit-page__image-status--error' : ''
            }`}
            role={imageError !== null ? 'alert' : 'status'}
          >
            {imageError ?? '사진을 올리는 중이에요…'}
          </p>
        )}

        <div className="profile-edit-page__group">
          <label className="profile-edit-page__label" htmlFor="profile-edit-nickname">
            닉네임
          </label>

          <div className="profile-edit-page__field">
            <div className="profile-edit-page__input-row">
              <input
                className={`profile-edit-page__input profile-edit-page__input--with-action${
                  isHintError ? ' profile-edit-page__input--invalid' : ''
                }`}
                id="profile-edit-nickname"
                type="text"
                name="nickname"
                value={nickname}
                onChange={(event) => {
                  setNickname(event.target.value);
                  setSaveError(null);
                }}
                placeholder={currentNickname === '' ? '김승요' : currentNickname}
                autoComplete="nickname"
                maxLength={10}
                aria-describedby={hint === null ? undefined : 'profile-edit-nickname-hint'}
                aria-invalid={isHintError}
              />
              {/* 확인을 마친 값이거나 바뀐 게 없으면 같은 요청을 또 보낼 이유가 없어 잠근다. */}
              <button
                className="profile-edit-page__input-action"
                type="button"
                onClick={handleDuplicateCheck}
                disabled={!isChanged || isChecking || checkResult?.valid === true}
              >
                {isChecking ? '확인 중...' : '중복확인'}
              </button>
            </div>

            {hint !== null && (
              <p
                className={`profile-edit-page__hint${
                  isHintError
                    ? ' profile-edit-page__hint--error'
                    : ' profile-edit-page__hint--success'
                }`}
                id="profile-edit-nickname-hint"
                role={saveError === null ? undefined : 'alert'}
              >
                <span className="profile-edit-page__hint-icon" aria-hidden="true" />
                {hint}
              </p>
            )}
          </div>
        </div>

        <button className="profile-edit-page__submit" type="submit" disabled={!canSave}>
          {isSaving ? '저장 중...' : '저장하기'}
        </button>
      </form>
    </div>
  );
}
