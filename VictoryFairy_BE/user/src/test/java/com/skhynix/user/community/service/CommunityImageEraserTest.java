package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link CommunityImageEraser} — 커밋 뒤 best-effort 삭제. USER-CM-96·155. */
@ExtendWith(MockitoExtension.class)
class CommunityImageEraserTest {

    private static final String PERM = "community/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.jpg";

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private CommunityImageEraser eraser;

    @Test
    @DisplayName("[AC-CM-96-1] 확정 EP들은 하나씩 DeleteObject로 지운다")
    void eraseQuietly_deletesEachPermanentEndpoint() {
        eraser.eraseQuietly(List.of(PERM, "community/b.png"));

        verify(storage).delete(PERM);
        verify(storage).delete("community/b.png");
    }

    @Test
    @DisplayName("[AC-CM-96-2] 삭제가 실패해도 예외를 올리지 않고 나머지 객체 삭제를 계속한다")
    void eraseQuietly_deleteFails_swallowedAndContinues() {
        willThrow(new IllegalStateException("s3 down")).given(storage).delete(PERM);

        assertThatCode(() -> eraser.eraseQuietly(List.of(PERM, "community/b.png"))).doesNotThrowAnyException();
        verify(storage).delete("community/b.png");
    }

    @Test
    @DisplayName("안전장치 - community/ 접두가 아닌 값(temp, 프로필, null)으로는 삭제를 내보내지 않는다")
    void eraseQuietly_nonPermanentEndpoint_neverDeleted() {
        eraser.eraseQuietly(List.of("temp/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.jpg",
                "user-profile-img/a.jpg"));
        eraser.eraseQuietly((String) null);

        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("빈 컬렉션이면 아무것도 하지 않는다")
    void eraseQuietly_empty_noop() {
        eraser.eraseQuietly(List.of());

        verifyNoInteractions(storage);
    }
}
