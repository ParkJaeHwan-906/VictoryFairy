package com.skhynix.quiz.quizroom;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizType;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 퀴즈방 테스트 공용 픽스처. 엔티티 id 는 DB 가 채우는 값이라 리플렉션으로 넣는다. */
public final class QuizRoomFixtures {

    public static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 14, 12, 0);

    private QuizRoomFixtures() {
    }

    public static UserAccount account(Long id, String nickname, String profileImgUrl) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("password1!").build();
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "profileImgUrl", profileImgUrl);
        return account;
    }

    public static QuizRoom room(Long id, UserAccount author, int quizCount, long likeCount) {
        QuizRoom room = QuizRoom.builder().author(author).title("제목" + id).description("소개" + id)
                .quizCount(quizCount).createdAt(CREATED_AT).build();
        ReflectionTestUtils.setField(room, "id", id);
        ReflectionTestUtils.setField(room, "likeCount", likeCount);
        return room;
    }

    public static QuizRoomQuiz quiz(Long id, QuizRoom room, QuizRoomQuizType type, String question, int answer) {
        QuizRoomQuiz quiz = QuizRoomQuiz.builder().room(room).type(type).question(question).answer(answer).build();
        ReflectionTestUtils.setField(quiz, "id", id);
        return quiz;
    }

    public static QuizRoomQuizOption option(QuizRoomQuiz quiz, int no, String text) {
        return QuizRoomQuizOption.builder().quiz(quiz).no(no).text(text).build();
    }

    public static List<QuizRoomQuizOption> options(QuizRoomQuiz quiz, String... texts) {
        return java.util.stream.IntStream.range(0, texts.length).mapToObj(no -> option(quiz, no, texts[no])).toList();
    }

    /** quiz {@code JwtAuthenticationFilter} 가 만드는 principal 형태(Long, credentials=null). */
    public static RequestPostProcessor authenticatedAs(Long userAccountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userAccountId, null, List.of()));
    }
}
