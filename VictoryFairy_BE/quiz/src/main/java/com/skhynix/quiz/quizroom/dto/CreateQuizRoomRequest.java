package com.skhynix.quiz.quizroom.dto;

import com.skhynix.domain.quizroom.entity.QuizRoomQuizType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 퀴즈방 생성 본문. 문항 단위 위반은 {@code quizzes[i].필드} 키로 나간다.
 *
 * <p>값은 다듬지 않고 받은 그대로 저장한다(앞뒤 공백 포함). 길이는 {@code String.length()} 기준.
 */
public record CreateQuizRoomRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 50, message = "제목은 50자 이하여야 합니다.")
        String title,

        @NotBlank(message = "소개는 필수입니다.")
        @Size(max = 500, message = "소개는 500자 이하여야 합니다.")
        String description,

        @NotEmpty(message = "문항은 1개 이상이어야 합니다.")
        @Size(max = 20, message = "문항은 20개 이하여야 합니다.")
        List<@Valid @NotNull(message = "문항이 비어 있습니다.") QuizItem> quizzes) {

    public record QuizItem(

            @NotNull(message = "문항 유형은 필수입니다.")
            @Pattern(regexp = "객관식|O/X", message = "문항 유형은 객관식 또는 O/X 여야 합니다.")
            String type,

            @NotBlank(message = "문제는 필수입니다.")
            @Size(max = 200, message = "문제는 200자 이하여야 합니다.")
            String question,

            // O/X 는 이 값을 무시하고 O·X 로 저장한다 — 그래서 필드 제약이 아니라 유형별 검사(아래)로 본다
            List<String> options,

            @NotNull(message = "정답은 필수입니다.")
            Integer answer) {

        static final int MIN_OPTIONS = 2;
        static final int MAX_OPTIONS = 4;
        static final int MAX_OPTION_LENGTH = 100;
        static final List<String> OX_OPTIONS = List.of("O", "X");

        public QuizRoomQuizType resolvedType() {
            return QuizRoomQuizType.fromLabel(type).orElse(null);
        }

        /** 저장할 보기. O/X 는 요청값과 무관하게 O·X 두 개다. */
        public List<String> resolvedOptions() {
            return resolvedType() == QuizRoomQuizType.OX ? OX_OPTIONS : options;
        }

        @AssertTrue(message = "객관식 보기는 2~4개, 각 1~100자여야 합니다.")
        public boolean isOptionsValid() {
            if (resolvedType() != QuizRoomQuizType.MULTIPLE_CHOICE) {
                return true; // 유형 위반은 type 키가 따로 잡는다
            }
            if (options == null || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
                return false;
            }
            return options.stream().allMatch(option -> option != null && !option.isBlank()
                    && option.length() <= MAX_OPTION_LENGTH);
        }

        @AssertTrue(message = "정답은 보기 번호 범위 안이어야 합니다.")
        public boolean isAnswerInRange() {
            QuizRoomQuizType resolved = resolvedType();
            if (resolved == null || answer == null) {
                return true; // type·answer 키가 따로 잡는다
            }
            if (resolved == QuizRoomQuizType.MULTIPLE_CHOICE && !isOptionsValid()) {
                return true; // 보기가 틀리면 범위 자체를 정할 수 없다 — optionsValid 키가 잡는다
            }
            return answer >= 0 && answer < resolvedOptions().size();
        }
    }
}
