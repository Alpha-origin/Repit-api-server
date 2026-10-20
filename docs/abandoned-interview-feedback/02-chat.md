# 02. Chat 서버 구현 명세

- 대상: `Alpha-origin/Repit-chat-server`
- 기준: `main@95f2cc0f57dadba8ad70c1064e252949dc13d2a7`
- 선행: [공통 계약](00-common-contracts.md)
- 상태: 구현 설계안. 아래 경로는 `src/main/java/com/person/repit/interview/` 기준이다.

## 1. 변경 위치와 현재 동작

| 위치 | 확인한 현재 동작 | 구현 기준 |
|---|---|---|
| `service/impl/ChatInterviewServiceImpl.java` | QUIT는 ABANDONED, COMPLETE는 진행 종료 검사 없이 `finishOnce(COMPLETED)` | QUIT 보존, 조기 COMPLETE 거부 |
| `service/InterviewOperationLock.java` | 답변·종료·복구가 공유하는 잠금 | 같은 잠금 안에서 상태 재조회·종료 판정 |
| `service/InterviewResultDeliveryService.java` | outbox 등록·전송·저장 상태 기반 응답 | 중단 기록도 동일하게 재전송 |
| `service/FinalizedInterviewRecovery.java`, `service/InterviewResultRetryWorker.java` | 확정 세션 복구·전송 재시도 | 상태·기록 보존 |
| `dto/response/ChatProgressResponse.java` | REST 종료 상태 포함 | 실제 최종 상태 반환 유지 |
| `dto/response/ChatWebSocketMessageResponse.java` | END에 status 없음 | 실제 최종 status 필드 추가 |
| `websocket/ReactiveChatInterviewWebSocketHandler.java` | COMPLETE/ANSWER 종료는 progress, QUIT는 end(message) | 모든 END에 동일 상태 계약 적용 |

근거: [종료 서비스](https://github.com/Alpha-origin/Repit-chat-server/blob/95f2cc0f57dadba8ad70c1064e252949dc13d2a7/src/main/java/com/person/repit/interview/service/impl/ChatInterviewServiceImpl.java), [소켓 응답 DTO](https://github.com/Alpha-origin/Repit-chat-server/blob/95f2cc0f57dadba8ad70c1064e252949dc13d2a7/src/main/java/com/person/repit/interview/dto/response/ChatWebSocketMessageResponse.java).

## 2. 정상 완료 판정

자동 종료의 기존 `moveToNextQuestion`·`getCurrentQuestion()`·질문 진행 인덱스를 재사용한다. 원질문·꼬리질문 삽입과 진행을 모두 반영한다. 답변 개수만으로 판정하거나 새 답변 품질 조건을 넣지 않는다.

명시적 COMPLETE 요청도 `InterviewOperationLock` 안에서 다음 순서로 처리한다.

1. 기존 outbox의 종료 결과가 있으면 이를 반환한다.
2. 최신 세션이 이미 종료됐으면 기존 최종 상태를 유지하고 필요한 기록 전송만 복구한다.
3. IN_PROGRESS이며 기존 진행이 끝났을 때만 COMPLETED를 확정한다.
4. 질문이 남았다면 완료를 확정하거나 outbox를 만들지 않는다.

[가정] 조기 COMPLETE는 기존 오류 envelope를 사용한 REST 409, WebSocket ERROR로 응답한다. 소켓은 정상 진행을 계속할 수 있게 유지한다. 새로운 이벤트 타입은 만들지 않는다. 구현 시 기존 예외 매핑을 확인하여 같은 의미를 보장한다.

## 3. QUIT와 경합

QUIT는 답변 유무와 관계없이 IN_PROGRESS를 ABANDONED로 확정한다. 이미 수락한 답변·생성된 질문·면접관 정보·기업 문맥을 전송 대상에 남긴다. 종료 중인 클라이언트의 미전송 답변까지 새로 생성해 보완하지 않는다.

- 마지막 ANSWER와 QUIT, COMPLETE와 QUIT가 경합하면 잠금 안에서 먼저 확정된 종료 상태를 유지한다.
- ABANDONED 뒤 답변이 오면 새 답변·꼬리질문·TTS를 생성하지 않고 확정 결과를 반환한다.
- COMPLETED 뒤 QUIT가 오면 COMPLETED 응답을 반환한다. 요청 동사로 응답 상태를 정하지 않는다.
- AI 꼬리질문 처리 후의 오래된 세션 객체가 확정 상태를 되돌리지 않도록 기존 잠금 범위·복구 경로를 함께 확인한다.
- 전송 실패로 세션과 outbox가 함께 남아도 같은 종료 상태·payload로 복구한다.

## 4. 기록 전송·복구 보존

`ChatInterviewAllRequest.from(session)`은 명시적 종료 status를 포함해야 한다. ABANDONED도 `InterviewResultDeliveryService.submit`·outbox·재시도 worker를 거친다. API의 분석 정책 생략은 기록 저장 2xx이며 outbox는 기존대로 SENT가 된다.

현재 최종 세션을 만료 없이 보존한 뒤 outbox로 전달하는 안전 장치, 전송 성공 후 세션 정리, 재시도 가능 장애와 BLOCKED 구분을 유지한다. API 장애 중 QUIT를 피드백 차단 목적으로 버리지 않는다. 상태 누락 outbox는 공통 계약과 [전달 계획](05-delivery-and-acceptance.md)에 따라 조사·복구하며, 답변 수 기반 완료 보정은 금지한다.

소켓 disconnect·TTL 만료를 새로운 COMPLETED 전이로 연결하지 않는다. 새 만료/분산 취소 정책은 이번 범위 밖이다.

## 5. 종료 응답

WebSocket DTO에 `InterviewStatus status`를 추가한다. `progress(progress)`의 END 분기와 QUIT의 `end(...)` 모두 실제 `ChatProgressResponse.status`를 전달한다. QUIT 경로도 공통 변환을 재사용할 수 있다. 메시지 문자열은 판정 기준이 아니다.

```json
{ "type": "END", "status": "COMPLETED", "message": "면접이 종료되었습니다" }
```

```json
{ "type": "END", "status": "ABANDONED", "message": "면접이 중단되었습니다" }
```

위 message는 예시다. REST와 소켓이 같은 최종 상태를 반환하도록 한다. 전송 재시도 중이어도 서버에서 확정한 종료 상태와 API 저장 완료 여부를 혼동하지 않는다.

## 6. 구현 후 검증 기준

- 답변 0개·일부 답변 QUIT 모두 ABANDONED 기록과 명시 status 전송.
- 정상 마지막 답변 종료·진행 종료 후 COMPLETE 모두 COMPLETED 유지.
- 질문 남은 COMPLETE는 상태 변경·기록 전송·피드백 요청 없이 거부.
- 종료 요청의 양방향 경합·중복·늦은 ANSWER에서 최종 상태 불변.
- API 장애→재시작·재전송에서 ABANDONED 답변·질문 payload 보존, 성공 후 SENT.
- REST와 모든 END 경로의 상태 일치 및 기존 QUESTION/ERROR 호환.
- disconnect·TTL만으로 완료되지 않음.

구현 후 명령은 저장소 JDK 설정에 맞춘 `./gradlew test`다. 이번 문서 작업에서는 실행하지 않는다.
