import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  COMMUNITY_POST_CONTENT_MAX_LENGTH,
  COMMUNITY_POST_IMAGE_MAX,
  COMMUNITY_POST_TITLE_MAX_LENGTH,
  createCommunityPost,
  getCommunityCategories,
  updateCommunityPost,
} from '../api';
import type { CommunityCategory, CommunityPostDetail } from '../api';
import CommunityImagePicker from '../components/CommunityImagePicker';
import { useCommunityImageUploads } from '../hooks/useCommunityImageUploads';
import { communityPostPath, readCommunityEditState, ROUTES } from '../routes';
import { toCommunityMessage } from '../utils/community';
import '../styles/CommunityPostEditorPage.css';

/**
 * CommunityPostEditorPage — 게시글 작성 · 수정.
 *
 * `/community/write` 면 작성, `/community/posts/:postId/edit` 면 수정이다. NavBar 없는 전체
 * 화면이고 상단 바 · 입력칸 · 하단 CTA 는 프로필 수정 화면(`ProfileEditPage`)과 같은 모양이다.
 *
 * ── 수정 화면은 상세를 다시 부르지 않는다 ─────────────────────────────
 * 상세 조회는 **조회수를 올리는 GET** 이다. 그래서 수정할 글은 상세 화면이 들고 있던 값을
 * 라우터 state 로 넘겨받는다(`CommunityEditState`). 주소를 직접 쳐서 state 가 비면 상세로
 * 돌려보낸다 — 거기서 "수정"을 다시 누르면 된다.
 *
 * ── 이미지는 고를 때 올리고, 저장할 때 EP 만 보낸다 ──────────────────
 * 사진은 고르는 즉시 `temp/` 로 선업로드되고(`useCommunityImageUploads`), 저장 요청에는 그
 * EP 목록이 실린다. 수정은 **전체 교체**라 남겨 둔 기존 `community/…` EP 도 함께 보낸다 —
 * 목록에서 뺀 기존 사진은 서버가 지운다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function CommunityPostEditorPage() {
  const { postId: rawPostId } = useParams();
  const location = useLocation();

  if (rawPostId === undefined) return <PostEditorForm original={null} />;

  const postId = Number(rawPostId);
  const original = Number.isInteger(postId) ? readCommunityEditState(location.state, postId) : null;

  // 수정할 원본이 없다 — 상세로 보내 거기서 다시 들어오게 한다(머리말 참고).
  if (original === null) {
    return <Navigate to={Number.isInteger(postId) ? communityPostPath(postId) : ROUTES.community} replace />;
  }

  return <PostEditorForm original={original} />;
}

function PostEditorForm({ original }: { original: CommunityPostDetail | null }) {
  const navigate = useNavigate();
  const isEdit = original !== null;

  const [categories, setCategories] = useState<CommunityCategory[]>([]);
  const [categoriesFailed, setCategoriesFailed] = useState(false);
  /** 값은 반드시 카테고리 응답에서 온 id 다 — 환경마다 달라 코드에 박지 않는다. */
  const [categoryId, setCategoryId] = useState<number | null>(original?.categoryId ?? null);
  const [title, setTitle] = useState(original?.title ?? '');
  const [content, setContent] = useState(original?.content ?? '');

  const images = useCommunityImageUploads(COMMUNITY_POST_IMAGE_MAX, original?.imageUrls ?? []);

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;

    getCommunityCategories()
      .then((list) => {
        if (alive) setCategories(list);
      })
      .catch(() => {
        if (alive) setCategoriesFailed(true);
      });

    return () => {
      alive = false;
    };
  }, []);

  const trimmedTitle = title.trim();
  const trimmedContent = content.trim();
  const canSubmit =
    categoryId !== null &&
    trimmedTitle.length > 0 &&
    trimmedContent.length > 0 &&
    !images.isUploading &&
    !isSubmitting;

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSubmit || categoryId === null) return;

    setIsSubmitting(true);
    setSubmitError(null);

    const body = {
      categoryId,
      title: trimmedTitle,
      content: trimmedContent,
      imageUrls: images.endpoints,
    };

    try {
      if (original === null) {
        const { postId } = await createCommunityPost(body);
        // 글쓰기 화면은 뒤로가기로 되돌아올 자리가 아니다 — 새 글 상세로 바꿔 끼운다.
        navigate(communityPostPath(postId), { replace: true });
      } else {
        await updateCommunityPost(original.postId, body);
        /*
         * 상세(바로 앞 history)로 돌아간다. 상세는 다시 그려지며 바뀐 글을 받는다 —
         * 조회수는 작성자 본인이라 오르지 않는다.
         */
        navigate(-1);
      }
    } catch (cause: unknown) {
      setSubmitError(
        toCommunityMessage(
          cause,
          isEdit
            ? '글을 수정하지 못했어요. 잠시 후 다시 시도해 주세요.'
            : '글을 올리지 못했어요. 잠시 후 다시 시도해 주세요.',
        ),
      );
      setIsSubmitting(false);
    }
  };

  const pageTitle = isEdit ? '글 수정' : '글쓰기';

  return (
    <div className="community-editor-page">
      <header className="community-editor-page__topbar">
        <button
          className="community-editor-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="community-editor-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="community-editor-page__topbar-title">{pageTitle}</h1>
        {/* 타이틀을 가운데 두기 위한 빈 자리(다른 화면들과 같다) */}
        <span className="community-editor-page__topbar-spacer" aria-hidden="true" />
      </header>

      <form className="community-editor-page__form" onSubmit={(event) => void handleSubmit(event)} noValidate>
        <fieldset className="community-editor-page__group">
          <legend className="community-editor-page__label">게시판</legend>
          {categoriesFailed ? (
            <p className="community-editor-page__hint community-editor-page__hint--error" role="alert">
              게시판 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
            </p>
          ) : (
            <div className="community-editor-page__chips" role="radiogroup" aria-label="게시판">
              {categories.map((category) => (
                <button
                  key={category.id}
                  className={`community-editor-page__chip${
                    categoryId === category.id ? ' community-editor-page__chip--active' : ''
                  }`}
                  type="button"
                  role="radio"
                  aria-checked={categoryId === category.id}
                  onClick={() => setCategoryId(category.id)}
                >
                  {category.name}
                </button>
              ))}
            </div>
          )}
        </fieldset>

        <div className="community-editor-page__group">
          <div className="community-editor-page__label-row">
            <label className="community-editor-page__label" htmlFor="community-editor-title">
              제목
            </label>
            <span className="community-editor-page__counter">
              {title.length}/{COMMUNITY_POST_TITLE_MAX_LENGTH}
            </span>
          </div>
          <input
            className="community-editor-page__input"
            id="community-editor-title"
            type="text"
            value={title}
            onChange={(event) => {
              setTitle(event.target.value);
              setSubmitError(null);
            }}
            placeholder="제목을 입력해주세요"
            maxLength={COMMUNITY_POST_TITLE_MAX_LENGTH}
            autoComplete="off"
          />
        </div>

        <div className="community-editor-page__group">
          <div className="community-editor-page__label-row">
            <label className="community-editor-page__label" htmlFor="community-editor-content">
              내용
            </label>
            <span className="community-editor-page__counter">
              {content.length}/{COMMUNITY_POST_CONTENT_MAX_LENGTH}
            </span>
          </div>
          <textarea
            className="community-editor-page__textarea"
            id="community-editor-content"
            value={content}
            onChange={(event) => {
              setContent(event.target.value);
              setSubmitError(null);
            }}
            placeholder="응원 팀 이야기, 오늘 경기 소감을 자유롭게 남겨주세요"
            maxLength={COMMUNITY_POST_CONTENT_MAX_LENGTH}
          />
        </div>

        <div className="community-editor-page__group">
          <span className="community-editor-page__label">사진</span>
          <CommunityImagePicker
            items={images.items}
            maxCount={COMMUNITY_POST_IMAGE_MAX}
            canAddMore={images.canAddMore}
            onAddFiles={images.addFiles}
            onRemove={images.remove}
            disabled={isSubmitting}
          />
          {images.error !== null && (
            <p className="community-editor-page__hint community-editor-page__hint--error" role="alert">
              <span className="community-editor-page__hint-icon" aria-hidden="true" />
              {images.error}
            </p>
          )}
        </div>

        {submitError !== null && (
          <p className="community-editor-page__hint community-editor-page__hint--error" role="alert">
            <span className="community-editor-page__hint-icon" aria-hidden="true" />
            {submitError}
          </p>
        )}

        <button className="community-editor-page__submit" type="submit" disabled={!canSubmit}>
          {isSubmitting
            ? isEdit
              ? '수정 중...'
              : '등록 중...'
            : images.isUploading
              ? '사진 올리는 중...'
              : isEdit
                ? '수정하기'
                : '등록하기'}
        </button>
      </form>
    </div>
  );
}
