# routine 프롬프트 — `vf-quiz-daily`

- **정본은 이 파일이다.** claude.ai 콘솔의 프롬프트는 이 파일의 복사본이며,
  콘솔에서 직접 고치지 않는다. 고칠 일이 생기면 이 파일을 PR로 바꾸고 나서
  콘솔에 붙여 넣는다(등록 절차: 같은 디렉토리 `README.md` 2번 섹션).
- **트리거 ID**: `trig_01BsUUWgTAj7CUD9jk6JADC3`
- **크론**: `50 23 * * *` (UTC) = 매일 08:50 KST — `game_schedule` export가
  08:30 KST에 도는 것을 전제로 그 뒤에 실행된다
- **모델**: Sonnet 5 / **환경**: `vf-question-creation`
- **환경변수**: `S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
  `AWS_DEFAULT_REGION` — 값은 환경에만 두고 프롬프트에 평문으로 넣지 않는다

---

```
VictoryFairy 데일리 퀴즈 생성 루틴이다.

[자격·보안]
- AWS 자격증명은 환경변수(AWS_ACCESS_KEY_ID 등)로 주입되어 있다. 값을 절대 출력하거나 파일에 기록하지 마라. 대상 버킷은 $S3_BUCKET 이다.

[준비]
1. git fetch origin sotaeho/ai/feat-llm-wiki-quiz && git checkout sotaeho/ai/feat-llm-wiki-quiz 로 파이프라인 브랜치로 전환하라. (이 브랜치가 dev_ai에 머지되면 이 단계는 제거된다)
2. VictoryFairy_AI/question-gen/ROUTINE.md 를 읽고 '사전 조건'부터 검증하라. aws CLI나 py-collector/.venv 가 없으면 문서에 적힌 폴백 절차(python3 -m pip install --quiet awscli / venv 생성 후 PyYAML 설치)로 직접 설치하라 — 이 설치는 명시적으로 허용된 작업이다.

[실행]
3. ROUTINE.md 절차를 순서대로 수행하라. 결정적 단계(템플릿 선택·데이터 바인딩·evidence 원문 대조·물량 선별·quizId 부여)는 VictoryFairy_AI/runner/ 패키지의 catalog/binding/finalize 모듈을 우선 활용하라.
4. 작업 단위는 경기다 — 오늘 스케줄의 경기마다 경기 문항 묶음을 먼저 만들고, 공통 문항 묶음을 마지막에 만든다. 물량은 question-gen/config/scoring.yaml의 volume이 정본이니 실행 시점에 그 파일을 읽어라.
5. 산출물은 s3://$S3_BUCKET/quiz-candidates/{KST 오늘 날짜}/ 에 업로드한다 (기존 계약·멱등 규칙 그대로).

[규칙]
- 사전 조건 폴백 설치는 예외로 하되, 그 외 단계의 실패는 그날 업로드를 생략하고(fail-closed) 마지막 응답에 실패 지점과 에러 전문을 보고하라. 단 시간 초과는 경기 단위로 끊는다 — 완성한 경기 문항까지만 올리고 나머지 경기는 통째로 생략한다.
- git push·커밋은 하지 마라 (이 리포에서 세션 푸시는 불가능하다).
- LLM 금지 사항 준수: 수치 계산 금지, evidence 창작 금지, 카탈로그 수정 금지.

[보고]
- 마지막 응답에: 경기별·공통별 생성/통과/폐기 문항 수, 폐기 사유별 집계, 업로드된 파일 목록을 표로 출력하라. 특정 경기만 계속 물량이 적으면 그 팀 재료가 부족하다는 신호이므로 함께 언급하라.
```

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-04 | 최초 등록 (Bedrock 러너 → 클라우드 루틴 전환) |
| 2026-08-05 | 프롬프트를 리포로 이관해 정본화. 경기 단위 작업·물량 정본(scoring.yaml)·경기 단위 fail-closed·경기별 보고를 반영 |
