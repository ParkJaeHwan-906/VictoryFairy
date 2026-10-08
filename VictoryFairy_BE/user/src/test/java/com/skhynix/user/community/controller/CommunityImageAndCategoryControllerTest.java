package com.skhynix.user.community.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.community.dto.CategoryResponse;
import com.skhynix.user.community.dto.CommunityImageResponse;
import com.skhynix.user.community.service.CommunityCategoryService;
import com.skhynix.user.community.service.CommunityImageService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/** {@link CommunityImageController}(선업로드)와 {@link CommunityCategoryController}(카테고리 목록) 슬라이스. */
@WebMvcTest
@ContextConfiguration(classes = {CommunityImageController.class, CommunityCategoryController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CommunityImageAndCategoryControllerTest extends CommunitySliceSupport {

    @MockitoBean
    private CommunityImageService imageService;
    @MockitoBean
    private CommunityCategoryService categoryService;

    private static MockMultipartFile image() {
        return new MockMultipartFile("image", "a.png", "image/png", new byte[] {1, 2, 3});
    }

    // ---------- 이미지 업로드 ----------

    @Test
    @DisplayName("[AC-CM-140-1] 업로드는 200 + {imageUrl: temp/...} 하나이고 서비스가 multipart 'image' 파트를 받는다")
    void upload_returns200WithTempEndpoint() throws Exception {
        given(imageService.upload(any(MultipartFile.class)))
                .willReturn(new CommunityImageResponse("temp/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.png"));

        mockMvc.perform(multipart("/community/images").file(image()).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imageUrl").value("temp/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.png"))
                .andExpect(keySet("$.data", "imageUrl"))
                .andExpect(jsonPath("$.message").value(nullValue()));

        ArgumentCaptor<MultipartFile> captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(imageService).upload(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getOriginalFilename()).isEqualTo("a.png");
    }

    @Test
    @DisplayName("[AC-CM-144-1] 'image' 파트가 없으면(다른 이름 file 포함) 서비스에 null이 전달되고 400 '이미지를 첨부해 주세요.'가 나간다")
    void upload_missingPart_servicesGetsNullAnd400() throws Exception {
        given(imageService.upload(isNull())).willThrow(new BusinessException(ErrorCode.COMMUNITY_IMAGE_REQUIRED));

        mockMvc.perform(multipart("/community/images")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}))
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미지를 첨부해 주세요."));
    }

    @Test
    @DisplayName("[AC-CM-142-1] 형식 위반은 400 'JPG, PNG, WEBP 이미지만 업로드할 수 있습니다.'다")
    void upload_invalidFormat_returns400() throws Exception {
        given(imageService.upload(any(MultipartFile.class)))
                .willThrow(new BusinessException(ErrorCode.INVALID_COMMUNITY_IMAGE_FORMAT));

        mockMvc.perform(multipart("/community/images").file(image()).header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("JPG, PNG, WEBP 이미지만 업로드할 수 있습니다."));
    }

    @Test
    @DisplayName("[AC-CM-143-1] 서비스의 5MiB 초과는 413 + '이미지 크기는 5MB를 넘을 수 없습니다.'다")
    void upload_tooLarge_returns413() throws Exception {
        given(imageService.upload(any(MultipartFile.class)))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_IMAGE_TOO_LARGE));

        mockMvc.perform(multipart("/community/images").file(image()).header("Authorization", bearer()))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이미지 크기는 5MB를 넘을 수 없습니다."));
    }

    @Test
    @DisplayName("[AC-CM-200-3] 멀티파트 해석 단계의 MaxUploadSizeExceededException도 ApiResponse 래퍼 413으로 나간다")
    void upload_containerLevelOversize_returnsWrapped413() throws Exception {
        given(imageService.upload(any(MultipartFile.class))).willThrow(new MaxUploadSizeExceededException(1L));

        mockMvc.perform(multipart("/community/images").file(image()).header("Authorization", bearer()))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이미지 크기는 5MB를 넘을 수 없습니다."));
    }

    // ---------- 카테고리 ----------

    @Test
    @DisplayName("[AC-CM-11-1, AC-CM-11-2, AC-CM-11-3] 카테고리 목록은 200 + 배열이고 항목 키는 {id,name,teamId}, 자유게시판은 teamId:null(키는 유지)이다")
    void categories_returnsArrayWithKeys() throws Exception {
        given(categoryService.findAll()).willReturn(List.of(
                new CategoryResponse(1L, "LG 트윈스", 7L), new CategoryResponse(11L, "자유게시판", null)));

        mockMvc.perform(get("/community/categories").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(keySet("$.data[0]", "id", "name", "teamId"))
                .andExpect(jsonPath("$.data[0].teamId").value(7))
                .andExpect(keySet("$.data[1]", "id", "name", "teamId"))
                .andExpect(jsonPath("$.data[1].teamId").value(nullValue()));
    }
}
