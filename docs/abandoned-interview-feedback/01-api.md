# 01. API 서버 구현 명세

> 상태: 구현 설계안. 이번 PR은 문서만 포함하며 아래 구현·테스트·데이터 정리는 실행하지 않는다.

대상: Alpha-origin/Repit-api-server

기준: main@6acc8e46bbb15ae07a139d40a87ac51fa6472723

역할: 피드백 실행 자격의 최종 집행자. DB에 저장된 면접 상태로 모든 실행·저장·조회 경로를 통제한다.

선행: [공통 계약](00-common-contracts.md)

## 1. 현재 확인한 경로

기준 패키지는 `src/main/java/repit/repit_api_server/`다.

| 위치 | 현재 동작 | 변경 |
| --- | --- | --- |
| domain/userdata/interview/service/InterviewService.saveInterview | 상태 누락·IN_PROGRESS를 COMPLETED로 보정. claim 후 기록 저장 | 신규 결과의 명시적 종료 상태 검증. 최종 기록 멱등성 유지 |
| .../interview/service/ChatInterviewResultService.handleResult | 저장 후 항상 dispatch 호출 | 저장된 상태를 다시 읽고 COMPLETED만 실행 준비 |
| .../feedback/service/FeedbackService | 수동·자동 모두 중단 면접 채점 가능 | 공통 상태 검사, 완료만 접수·조회 |
| .../feedback/service/FeedbackDispatchService | transcript·녹화·sweep에서 FEEDBACK/AUDIO 실행 | 모든 입구·claim 후·외부 호출 직전 검사 |
| .../analysis/service/AudioAnalysisService | 종료 면접의 녹음을 요청·복구·재전송 | 완료만 허용, 중단의 기존 대기 종료 |
| .../analysis/service/VideoAnalysisService | 녹화 sweep·상태 조회 복구·재분석 | 완료만 허용. 영상 존재만으로 실행하지 않음 |
| .../recording/service/InterviewRecordingService | 업로드 후 음성 dispatch 재검사 | 업로드 성공과 분석 생략 분리 |

근거: [README의 고정 커밋 링크](README.md#3-현재-원인).

## 2. 공통 정책 구현

신규 제안: `domain/userdata/interview/service/InterviewFeedbackPolicy`. 서비스 이름은 제안이며 기존 동등한 정책 객체가 생겼다면 재사용한다.

isEligible(interview): status == COMPLETED만 true.

수동 요청용 검사: 기존 소유권 확인 후 비완료면 BusinessException.conflict(409).

자동 경로용 판정: COMPLETED=실행, ABANDONED=정책 종료, IN_PROGRESS=보류.

회사 유무·답변 모드·답변 개수·recording.endReason은 자격 판정에 사용하지 않는다.

HTTP 컨트롤러에만 검사하지 않는다. 스케줄러가 부르는 서비스와 private 재전송 경로도 같은 정책을 적용한다.

다음 순서를 지킨다.

```text
면접 조회 → 소유권/내부 호출 권한 확인 → 저장 상태 확인
  → COMPLETED일 때만 녹화/전사 조회·URL 서명·분석 요청·접수 행 생성
```

상태 검사 실패 때문에 불필요한 S3 서명이나 외부 분석 호출이 먼저 나가지 않게 한다. 느린 외부 HTTP 호출을 DB 트랜잭션으로 길게 감싸지 않는다. 최종 상태가 불변이라는 공통 계약과 조건부 갱신을 함께 유지한다.

## 3. 종료 기록 수신

### InterviewService.saveInterview

식별자·기존 인증과 저장된 최종 기록 유무를 확인한다. 신규 결과는 status를 검증한 뒤 기존 claimResultSave로 첫 종료 기록만 저장한다. 검증 실패가 claim 상태를 바꾸지 않아야 한다.

이미 종료 기록이 있으면 기존 PK·질문·답변·최종 상태를 그대로 둔다.

신규 기록은 SaveInterviewRequest.status가 COMPLETED/ABANDONED인지 검사한다.

null/IN_PROGRESS → COMPLETED 보정을 제거한다. 신규 잘못된 종료 상태는 422다.

ABANDONED의 기록도 같은 트랜잭션으로 저장한다. 피드백을 막겠다고 기록 수신을 건너뛰지 않는다.

### ChatInterviewResultService.handleResult

저장 커밋 이후 interviewId로 실제 저장된 상태를 다시 읽는다. COMPLETED면 기존 onTranscriptSaved를 호출하고, ABANDONED면 정상 생략한다. 중복 요청의 status가 반대여도 DB의 첫 최종 상태를 따른다. 분석 생략은 성공 처리해 Chat의 outbox가 SENT로 정리될 수 있어야 한다.

## 4. 텍스트 피드백 생성·조회·콜백

### 실행

requestFeedback(userId, interviewId): 소유권 뒤 상태 검사. 기존 PENDING/SUCCEEDED/FAILED 재시도 판정 전에 적용한다.

requestFeedbackForFinishedInterview(interviewId): COMPLETED만 실행. ABANDONED/IN_PROGRESS에서 외부 호출 없이 반환한다.

startFeedback: 공통 마지막 검사도 둔다. 직접 부르는 새 경로가 추가돼도 우회되지 않게 한다.

SOLO/MULTI outbound 요청 DTO에 interviewStatus를 추가하고 검사한 DB 상태로 채운다.

질문·답변·기업 스냅샷·요청 문항 ID·회사 코멘트 근거·점수 계산은 기존 계약을 유지한다.

### 조회

getFeedback: 면접 소유권과 COMPLETED 검사 후 피드백을 조회한다. 중단 면접의 과거 성공 결과도 반환하지 않는다.

getAllFeedbacks: COMPLETED 면접의 피드백만 목록·과거 비교에 제공한다. 면접별 추가 쿼리 대신 기존 batch 조회 또는 repository join으로 필터링한다.

완료 면접에서 결과가 없는 경우의 기존 404, PENDING 만료 판정, 재채점 동작은 유지한다.

### 콜백

handleCallback → findTarget → createFromSession에 걸친 조기 콜백 복구 경로를 함께 수정한다.

jobId/sessionId와 기존 작업·면접 연결을 확인한다.

면접 상태가 ABANDONED/IN_PROGRESS면 피드백 행을 새로 만들지 않는다.

기존 행도 성공 결과·항목·면접관별 종합·companyFit으로 갱신하지 않는다.

알려진 비대상 콜백은 정책 생략 로그만 남기고 기존 컨트롤러의 2xx 응답으로 끝낸다.

COMPLETED의 접수 응답보다 먼저 온 콜백, 정상 중복 콜백은 기존 복구 정책을 유지한다.

## 5. dispatch·재시도·복구

수정 위치: FeedbackDispatchService, FeedbackDispatchRepository, FeedbackDispatchStatus.

onTranscriptSaved, onRecordingUploaded, sweep, claim 후 dispatch, 종류별 request에서 정책을 확인한다.

ABANDONED 신규 대기 행은 만들지 않는다. 이미 존재하는 WAITING·만료 SENDING은 SKIPPED로 닫는다.

SKIPPED는 findDue·claim·만료 claim 재개 대상에서 제외한다. 오류 backoff나 재시도 횟수를 늘리지 않는다.

IN_PROGRESS에서 먼저 올라온 녹화는 보관하고 실행만 보류한다. SKIPPED로 영구 종료하지 않는다.

최종 상태를 확인한 뒤 실행하므로 중단 판정과 외부 전송 사이에 새 생성이 열리지 않게 한다.

한 인스턴스의 오래된 SENDING 처리가 뒤늦게 markDone/scheduleRetry를 수행해 SKIPPED를 되살리지 않도록 기존 claimedAt 비교에 현재 상태 조건도 유지/보강한다.

신규 migration은 실제 다음 번호로 작성한다. 상태 컬럼의 DB 제약이 있다면 SKIPPED 허용을 추가하고, 중단 면접에 남은 대기 행의 정책 종료를 함께 다룬다. 실행 전 건수 확인·소량 배치를 사용한다.

## 6. 음성·영상 분석의 우회 차단

텍스트 서비스 검사만으로 충분하지 않다.

| 경로 | 검사 위치 |
| --- | --- |
| 음성 자동 생성 | AudioAnalysisService.requestForFinishedInterview 시작과 각 chunk 전송 직전 |
| 음성 재시도·미확인 접수 복구 | retry, 이전 요청 정리·동일 requestId 재전송·AudioAnalysisRecovery |
| 영상 최초 자동 생성 | VideoAnalysisService.sweep, VideoAnalysisRepository.findVideosToStart, open |
| 영상 상태 복구·자동 재분석 | findDueChecks, check, findReanalysisCandidates, reanalyze |
| 사용자 음성/영상 재분석 | 각 서비스 retry의 권한 확인 직후 |
| 조회 중 외부 작업 조회·복구 | 각 get에서 자격 검사 후에만 실행 |
| 늦은 결과 | 음성 handleCallback, VideoAnalysisCallbackHandler와 결과 조회 복구 저장 경로 |

음성·영상의 과거 ABANDONED 대기는 기존 FAILED와 정책 사유로 종료하고 자동 재분석 대상에서 제외한다. 미디어 파일 업로드 자체는 기존 성공으로 응답하며 분석 실행만 생략한다. FULL_INTERVIEW 파일이 먼저 업로드되고 API 면접 상태가 아직 IN_PROGRESS이면 영상 sweep은 기다린다. 이후 ABANDONED면 영구 미실행, COMPLETED면 기존 유예·분석 정책으로 실행한다.

## 7. 테스트

기존 FeedbackServiceFinishedInterviewTest의 중단 면접도 채점하는 기대를 변경한다. 기존 기록 보존·중복 수신 테스트는 삭제하지 않는다.

COMPLETED/ABANDONED/IN_PROGRESS × 자동/수동/재시도 × SOLO/MULTI.

중단의 텍스트·음성·영상 client 호출 0회, 신규 분석/피드백 행 0개.

status 누락·IN_PROGRESS 신규 result 422, 명시적 ABANDONED result 저장 2xx.

COMPLETED 기록 뒤 ABANDONED 중복 요청과 반대 순서에서 첫 최종 상태·질문 PK 유지.

WAITING·만료 SENDING 중단 작업의 SKIPPED 전이, sweep 반복에도 재실행 0회.

IN_PROGRESS 녹화 → 나중에 COMPLETED이면 정상 실행; ABANDONED이면 실행 0회.

늦은 성공·실패 콜백과 미확인 접수 조회 복구가 중단 결과를 저장하지 않음.

과거 중단 성공 피드백은 단건 차단·목록 제외, 완료 피드백 조회·기업 코멘트는 정상 유지.

요청 DTO의 interviewStatus 직렬화와 DB 값 사용 확인.

검증 명령: 저장소 Java 설정에 맞는 JDK에서 ./gradlew test. DB 조건부 갱신·claim 경합은 실제 DB 기반 persistence 테스트로도 확인한다.

## 8. 기술 가정과 적용 시 재확인

[가정] 정책 객체 이름은 `InterviewFeedbackPolicy`로 한다. 기존 동등 객체가 있으면 재사용한다. `SKIPPED`와 `INTERVIEW_ABANDONED`는 이번 명세의 신규 제안이며 현재 main에 이미 구현됐다는 뜻이 아니다.

기준 커밋의 최신 Flyway는 `V38__company_sample_requires_source.sql`이다. 현재 기준 다음 번호는 V39이나, 구현 직전 fetch 후 새 마이그레이션과 충돌하지 않는 실제 다음 번호를 사용한다. DB 정리와 enum/check 제약 변경은 구체적인 스키마 확인 후 별도 마이그레이션·실행 계획으로 작성한다.

배치 건수·대상 ID·정리 전후 상태를 기록하고 조건부 갱신한다. 활성 SENDING을 강제 탈취하지 않으며, 만료 후 정책 종료 대상으로 처리한다. 늦은 워커의 `markDone`/`scheduleRetry`도 상태와 claim 식별자를 함께 확인해야 한다.
