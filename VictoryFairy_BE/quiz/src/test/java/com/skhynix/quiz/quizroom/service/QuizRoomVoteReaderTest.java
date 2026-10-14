package com.skhynix.quiz.quizroom.service;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.account;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.option;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.quiz;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.room;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizType;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizOptionRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomVoteCountView;
import com.skhynix.quiz.quizroom.dto.VotesPayload;
import com.skhynix.quiz.quizroom.dto.VotesPayload.OptionCount;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizRoomVoteReaderTest {

    @Mock
    private QuizRoomQuizOptionRepository optionRepository;
    @Mock
    private QuizRoomAnswerRepository answerRepository;

    @InjectMocks
    private QuizRoomVoteReader reader;

    private final QuizRoom room = room(1L, account(1L, "작성자", null), 3, 0);
    private final QuizRoomQuiz q11 = quiz(11L, room, QuizRoomQuizType.MULTIPLE_CHOICE, "q11", 0);
    private final QuizRoomQuiz q12 = quiz(12L, room, QuizRoomQuizType.OX, "q12", 0);
    private final QuizRoomQuiz q13 = quiz(13L, room, QuizRoomQuizType.OX, "q13", 1);

    private List<QuizRoomQuizOption> allOptions() {
        List<QuizRoomQuizOption> options = new ArrayList<>();
        for (int no = 0; no < 3; no++) {
            options.add(option(q11, no, "보기" + no));
        }
        for (QuizRoomQuiz ox : List.of(q12, q13)) {
            options.add(option(ox, 0, "O"));
            options.add(option(ox, 1, "X"));
        }
        return options;
    }

    @Test
    @DisplayName("[QUIZ-CQR-88] 구독 직후 스냅샷은 문항마다 1건씩, 저장된 답에서 센 분포다 (문항 11: 보기0 두 명, 보기1 한 명)")
    void readRoom_buildsOneVotesPerQuizFromStoredAnswers() {
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(allOptions());
        given(answerRepository.countVotesByRoomId(1L)).willReturn(List.of(
                new QuizRoomVoteCountView(11L, 0, 2L), new QuizRoomVoteCountView(11L, 1, 1L)));

        List<VotesPayload> votes = reader.readRoom(1L);

        assertThat(votes).extracting(VotesPayload::quizId).containsExactly(11L, 12L, 13L);
        assertThat(votes.get(0).options()).containsExactly(new OptionCount(0, 2), new OptionCount(1, 1),
                new OptionCount(2, 0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-89] 아무도 안 답한 O/X 문항도 0표 보기까지 번호 오름차순으로 싣는다")
    void readRoom_zeroVoteOxQuizListsBothOptions() {
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(allOptions());
        given(answerRepository.countVotesByRoomId(1L)).willReturn(List.of());

        List<VotesPayload> votes = reader.readRoom(1L);

        assertThat(votes.get(1).options()).containsExactly(new OptionCount(0, 0), new OptionCount(1, 0));
        assertThat(votes.get(2).options()).containsExactly(new OptionCount(0, 0), new OptionCount(1, 0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-88] 조회는 문항 수와 무관하게 보기 1건 + 집계 1건, 두 번만 한다")
    void readRoom_queriesTwiceRegardlessOfQuizCount() {
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(allOptions());
        given(answerRepository.countVotesByRoomId(1L)).willReturn(List.of());

        reader.readRoom(1L);

        verify(optionRepository, times(1)).findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L);
        verify(answerRepository, times(1)).countVotesByRoomId(1L);
        verifyNoMoreInteractions(optionRepository, answerRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-88] 문항이 하나도 없는 방은 빈 목록이다")
    void readRoom_noQuizzes_isEmpty() {
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(List.of());
        given(answerRepository.countVotesByRoomId(1L)).willReturn(List.of());

        assertThat(reader.readRoom(1L)).isEmpty();
    }

    @Test
    @DisplayName("[QUIZ-CQR-123] 소유자가 비워진(탈퇴 후 삭제된) 답도 집계 쿼리가 계정 축을 보지 않으므로 분포에 그대로 남는다 - 리더는 계정 id 를 전혀 쓰지 않는다")
    void readRoom_doesNotDependOnAccountColumn() {
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(allOptions());
        given(answerRepository.countVotesByRoomId(1L)).willReturn(List.of(new QuizRoomVoteCountView(13L, 1, 5L)));

        List<VotesPayload> votes = reader.readRoom(1L);

        assertThat(votes.get(2).options()).containsExactly(new OptionCount(0, 0), new OptionCount(1, 5));
    }

    @Test
    @DisplayName("[QUIZ-CQR-90] 제출 직후 갱신 분포는 그 문항 하나만, 보기 전부를 번호 순으로 싣는다")
    void readQuiz_returnsUpdatedDistributionForOneQuiz() {
        given(optionRepository.findAllByQuiz_IdOrderByNoAsc(11L)).willReturn(List.of(option(q11, 0, "a"),
                option(q11, 1, "b"), option(q11, 2, "c")));
        given(answerRepository.countVotesByQuizId(11L)).willReturn(List.of(new QuizRoomVoteCountView(11L, 1, 2L)));

        VotesPayload votes = reader.readQuiz(11L);

        assertThat(votes.quizId()).isEqualTo(11L);
        assertThat(votes.options()).containsExactly(new OptionCount(0, 0), new OptionCount(1, 2),
                new OptionCount(2, 0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-95] 분포 페이로드에는 보기 번호와 표 수만 있다 - 정답 키가 없다")
    void votesPayload_hasNoAnswerComponent() {
        assertThat(VotesPayload.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("quizId", "options");
        assertThat(OptionCount.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("no", "count");
    }
}
