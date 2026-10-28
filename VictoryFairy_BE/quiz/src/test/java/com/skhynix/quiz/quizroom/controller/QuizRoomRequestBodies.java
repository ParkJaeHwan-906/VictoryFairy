package com.skhynix.quiz.quizroom.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 생성 요청 본문 조립. 필드를 하나씩 틀어 가며 검증 실패 케이스를 만든다. */
final class QuizRoomRequestBodies {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private QuizRoomRequestBodies() {
    }

    static Map<String, Object> mc(String question, List<String> options, Object answer) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "객관식");
        item.put("question", question);
        item.put("options", options);
        item.put("answer", answer);
        return item;
    }

    static Map<String, Object> ox(String question, Object answer) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "O/X");
        item.put("question", question);
        item.put("answer", answer);
        return item;
    }

    static Map<String, Object> validMc() {
        return mc("1994 우승 감독은?", List.of("이광환", "김성근", "백인천"), 0);
    }

    static Map<String, Object> room(Object title, Object description, Object quizzes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        if (quizzes != null) {
            body.put("quizzes", quizzes);
        }
        return body;
    }

    static Map<String, Object> valid() {
        return room("LG 레전드 퀴즈", "90년대 LG", List.of(validMc(), ox("LG의 연고지는 서울이다", 0)));
    }

    static Map<String, Object> withQuizzes(List<Map<String, Object>> quizzes) {
        return room("제목", "소개", quizzes);
    }

    static List<Map<String, Object>> repeat(int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(validMc());
        }
        return list;
    }

    static String json(Object body) {
        return MAPPER.writeValueAsString(body);
    }

    static String repeatChar(int n) {
        return "가".repeat(n);
    }
}
