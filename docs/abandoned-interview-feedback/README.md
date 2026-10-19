# 면접 중도 종료 시 피드백 생성 방지 — 구현 명세

- 작성일: 2026-10-20, Asia/Seoul
- 이슈: [#111](https://github.com/Alpha-origin/Repit-api-server/issues/111)
- 상태: **구현 설계안**. 문서 작성이며 서비스 코드 수정·배포·테스트 실행을 의미하지 않는다.
- 정책: **서버가 정상 완료로 확정한 면접만 피드백을 생성한다. 중단 면접의 기록 저장은 유지한다.**

## 1. 공유할 파일

| 파일 | 담당·내용 |
|---|---|
| [00-common-contracts.md](00-common-contracts.md) | 중단 정의, 상태 전이, 서버 간 계약, 오류·이전 데이터 정책 |
| [01-api.md](01-api.md) | 핵심 차단: 자동·수동·재시도·콜백·음성·영상 분석 |
| [02-chat.md](02-chat.md) | QUIT 상태 확정, 조기 COMPLETE 방지, 기록 전송·복구 보존 |
| [03-analysis.md](03-analysis.md) | 피드백 접수 전 완료 상태 검증, 기본 채점·기업 코멘트 보존 |
| [04-web.md](04-web.md) | 기존 완료 전용 화면 유지, 재시도·종료 응답·녹화 경합 보완 |
| [05-delivery-and-acceptance.md](05-delivery-and-acceptance.md) | 호환 적용 순서, 이전 대기 작업 정리, 통합 인수 시나리오 |

이 README를 포함한 **7개 파일 전체**를 공유한다. 먼저 공통 계약을 읽고 담당 문서를 구현한다. [가정] 전달 문서의 원본 위치는 API 저장소의 `docs/abandoned-interview-feedback/`로 한다. 다른 저장소의 구현 지시도 이 묶음에서 함께 관리한다.

## 2. 코드 확인 기준

각 로컬 클론에서 `git fetch origin` 후 원격 `main`을 아래 커밋으로 고정하여 읽었다. 2026-10-20 확인 결과 요청에 제시된 커밋과 일치한다. 로컬 개발 브랜치·실제 운영 배포 버전과 동일하다고 가정하지 않는다.

| 역할 | 저장소 | 확인 커밋 |
|---|---|---|
| API | [Repit-api-server](https://github.com/Alpha-origin/Repit-api-server) | `6acc8e46bbb15ae07a139d40a87ac51fa6472723` |
| Chat | [Repit-chat-server](https://github.com/Alpha-origin/Repit-chat-server) | `95f2cc0f57dadba8ad70c1064e252949dc13d2a7` |
| Analysis | [repit-analysis-server-v2](https://github.com/Alpha-origin/repit-analysis-server-v2) | `3b2103dbcaab2b6a3f33402eae6a3cd19d0a356b` |
| Web | [Repit-web](https://github.com/Alpha-origin/Repit-web) | `87a7ddec55fc7dc795eed01ae9d2449c27e56354` |

Analysis 명세는 API의 `/feedback/solo`, `/feedback/multi`와 연결되는 v2 기준이다. 구 `Repit-analysis-server`, Auth, Config에는 신규 작업을 배정하지 않는다. 실제 적용 대상이 다르면 표와 변경 위치부터 다시 대조한다. Web도 이번 코드 확인은 `main` 기준이며 실제 작업 브랜치의 기준은 해당 저장소 컨벤션에 맞춰 다시 확인한다.

## 3. 현재 원인

1. Chat의 QUIT는 `ABANDONED`로 확정하고 정상 완료와 같은 outbox로 전달한다. 기록을 보존하는 이 동작은 유지한다.
2. API `ChatInterviewResultService.handleResult`는 저장 후 상태 구분 없이 `onTranscriptSaved`를 호출한다.
3. `FeedbackDispatchService`는 텍스트·음성 대기 행을 만들고, `FeedbackService`는 중단 면접도 채점할 수 있다.
4. 수동 요청·지연 재시도·녹화 업로드·영상 sweep이 별도 경로라 한 곳만 고치면 우회가 남는다.
5. Web은 목록·최초 생성·재생성에서 이미 COMPLETED를 검사한다. 화면에서 숨기는 것만으로 서버 호출 비용이 없어지지 않는다.
6. Chat WebSocket END에는 최종 status가 없다. Web 파서는 없는 상태를 null로 읽는다.

근거: [Chat 종료 경로](https://github.com/Alpha-origin/Repit-chat-server/blob/95f2cc0f57dadba8ad70c1064e252949dc13d2a7/src/main/java/com/person/repit/interview/service/impl/ChatInterviewServiceImpl.java), [API 기록 수신](https://github.com/Alpha-origin/Repit-api-server/blob/6acc8e46bbb15ae07a139d40a87ac51fa6472723/src/main/java/repit/repit_api_server/domain/userdata/interview/service/ChatInterviewResultService.java), [자동 dispatch](https://github.com/Alpha-origin/Repit-api-server/blob/6acc8e46bbb15ae07a139d40a87ac51fa6472723/src/main/java/repit/repit_api_server/domain/userdata/feedback/service/FeedbackDispatchService.java), [피드백 서비스](https://github.com/Alpha-origin/Repit-api-server/blob/6acc8e46bbb15ae07a139d40a87ac51fa6472723/src/main/java/repit/repit_api_server/domain/userdata/feedback/service/FeedbackService.java).

## 4. 목표 흐름

```text
정상 완료 → Chat COMPLETED 기록 전송 → API 저장 → 피드백·기업 코멘트·미디어 분석 허용
중도 종료 → Chat ABANDONED 기록 전송 → API 저장 → 생성·재생성·재시도 차단
                                                  └ 늦은 분석 콜백도 결과 반영 안 함
```

판단 기준은 API에 저장된 면접 상태다. 브라우저의 완료 화면 진입·답변 개수·녹음 endReason을 기준으로 삼지 않는다. 이미 외부 분석에 접수된 작업을 강제로 중지하는 분산 취소 API는 추가하지 않는다. 새 요청 차단과 늦은 결과의 저장·노출 방지를 별도로 검증한다.
