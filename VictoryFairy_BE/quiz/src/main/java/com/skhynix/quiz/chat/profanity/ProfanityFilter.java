package com.skhynix.quiz.chat.profanity;

import com.skhynix.profanity.ProfanityConfig;
import com.skhynix.profanity.ProfanityDetector;
import com.skhynix.profanity.ProfanitySpan;
import java.util.List;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

/**
 * 채팅 메시지의 욕설을 응원 구단 연상 단어로 치환한다.
 *
 * <p>탐지는 {@code :profanity} 의 {@link ProfanityDetector} 가 하고, 여기서는 구단별 치환어 조립만 한다
 * ({@link MaskWordTable} 은 quiz 전용이라 라이브러리로 옮기지 않았다).
 *
 * <p>{@code :profanity} 는 좁은 스캔(com.skhynix.quiz) 밖이라 {@code @Import} 로 등록한다. 앱 클래스가 아니라
 * 여기 붙인 이유: {@code @WebMvcTest} 슬라이스가 앱 클래스의 {@code @Import} 를 따라 JSON 적재까지 끌고 오지 않게.
 */
@Component
@Import(ProfanityConfig.class)
public class ProfanityFilter {

    private final ProfanityDetector detector;

    public ProfanityFilter(ProfanityDetector detector) {
        this.detector = detector;
    }

    /**
     * 원문에서 금지어 구간을 찾아 구단 치환어로 바꾼 문자열을 돌려준다.
     *
     * @param content 사용자가 보낸 원문
     * @param teamCode 발신자가 현재 응원하는 구단의 {@code teams.code}(표에 없으면 공통 후보로 폴백)
     * @return 마스킹된 문자열. 매칭이 없으면 원문과 문자 단위로 동일하다
     */
    public String mask(String content, String teamCode) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        List<ProfanitySpan> spans = detector.detect(content);
        if (spans.isEmpty()) {
            return content;
        }
        return replace(content, spans, teamCode);
    }

    /** 병합된 구간 하나를 치환어 하나로 통째 교체한다(길이에 맞춰 채우거나 반복하지 않는다). */
    private static String replace(String content, List<ProfanitySpan> spans, String teamCode) {
        StringBuilder masked = new StringBuilder(content.length());
        int cursor = 0;
        for (ProfanitySpan span : spans) {
            masked.append(content, cursor, span.start());
            masked.append(MaskWordTable.pick(teamCode, content.substring(span.start(), span.end())));
            cursor = span.end();
        }
        masked.append(content, cursor, content.length());
        return masked.toString();
    }
}
