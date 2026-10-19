# 면접 답변 방식 계약 (#107)

면접 생성의 선택 필드 `answerMode`는 `TEXT` 또는 `VOICE`입니다. `VOICE`는 현재 웹의 영상/음성 답변을 뜻합니다. 생략·null은 기존 클라이언트 호환을 위해 허용하고 기록에도 null로 저장합니다. 기존 기록에 답변 방식을 추정해 채우지 않습니다.

```json
{"personaId": 1, "answerMode": "TEXT"}
```

- SOLO와 MULTI 생성 모두 저장합니다.
- 생성 응답 및 단건·목록 면접 조회의 `answerMode`로 그대로 반환합니다.
- 완료 기록 저장·피드백 재채점은 답변 방식을 변경하지 않습니다.
- 기존 요청의 나머지 필드는 그대로이며 잘못된 enum 문자열은 400입니다.
- `Idempotency-Key`가 같은데 TEXT↔VOICE를 바꾸면 409 `IDEMPOTENCY_KEY_REUSED`입니다.
- 필드가 없거나 null인 요청의 canonical 해시는 기존과 같습니다. 이미 제출한 요청은 본문을 바꾸지 않고 재전송해야 합니다.
- `V36__add_interview_answer_mode.sql`은 nullable 컬럼과 값 제약만 추가합니다. 선행 기업 계약 PR #106의 V35 뒤에 적용합니다.
- Chat·Analysis 요청/콜백 및 점수 산식·음성 분석 디스패치에는 영향이 없습니다.

웹은 TEXT의 음성 섹션을 숨기고, VOICE에는 녹음 실패 상태도 보여 줍니다. 기존 null 기록은 음성 조회의 녹음 유무로 표시를 보완하며 조회 장애를 TEXT로 단정하지 않습니다.

## 검증

격리 PostgreSQL 16에서 `./gradlew test` 877개 통과(실패·오류·건너뜀 0). 추가 회귀는 SOLO/MULTI × TEXT/VOICE의 생성·멱등 재전송·완료 후 단건/목록 조회·실제 컬럼 저장, 변경된 답변 방식의 409, 변경 전 해시 재현과 null 유지, JSON enum의 허용/거절·문자열 응답을 확인합니다. V36 SQL을 이전 스키마의 기존 행에 적용해 null이 유지되는 것도 격리 스키마에서 확인했습니다.
