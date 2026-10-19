# 04. Web 구현 명세

- 대상: `Alpha-origin/Repit-web`
- 기준: `main@87a7ddec55fc7dc795eed01ae9d2449c27e56354`
- 선행: [공통 계약](00-common-contracts.md)
- 상태: 구현 설계안. 구현 시 저장소 `AGENTS.md`와 실제 작업 기준 브랜치를 다시 확인한다.

## 1. 기존 동작과 변경 위치

경로는 `src/features/` 아래 기준이다.

| 위치 | 현재 확인한 동작 | 구현 기준 |
|---|---|---|
| `feedback-page/feedback-list/model/useFeedbackList.ts` | COMPLETED 면접만 목록에 포함 | 유지 |
| `feedback-page/feedback-list/api/getAllInterviews.ts` | 피드백 생성 전 완료 검사 | 유지 |
| `feedback-page/model/useFeedbackInterview.ts` | 최초 로딩에 완료 검사, 재시도·polling 별도 경로 | 재시도·409·늦은 응답도 차단 |
| `interview-page/interview/api/shared.ts` | 없는 소켓 status는 null | END 문자열로 완료 보정 금지 |
| `interview-page/interview/api/interview-socket/index.ts` | 소켓 이벤트 변환 | 신규 END status 전달 |
| `interview-page/interview/api/quit-interview/index.ts` | REST 응답 data 반환 | 최종 status 검증·사용 |
| `interview-page/interview/model/useInterviewSession.ts` | `saveQuit`는 errorMessage/statusCode만 사용, 종료 Promise 공유 | 서버 상태로 종료 경로 정리 |
| `interview-page/interview/api/upload-recording/index.ts` | ANSWER/FULL_INTERVIEW 업로드 | 보존 유지, 분석 트리거 분리 |

근거: [면접 세션 hook](https://github.com/Alpha-origin/Repit-web/blob/87a7ddec55fc7dc795eed01ae9d2449c27e56354/src/features/interview-page/interview/model/useInterviewSession.ts), [피드백 hook](https://github.com/Alpha-origin/Repit-web/blob/87a7ddec55fc7dc795eed01ae9d2449c27e56354/src/features/feedback-page/model/useFeedbackInterview.ts).

## 2. 서버 확정 상태에 따른 종료

종료 버튼 의도와 서버가 확정한 결과를 구분한다. 사용자가 나가기를 눌렀어도 먼저 COMPLETED가 확정됐으면 해당 결과를 사용한다. END status=ABANDONED이면 완료 화면으로 이동하거나 피드백 요청을 만들지 않는다.

| 종료 결과 | 클라이언트 처리 |
|---|---|
| COMPLETED 확정 | 기존 완료 경로 유지. API 기록 전달 지연은 기존 저장 확인 경로 사용 |
| ABANDONED 확정 | 기존 중단 이동 경로(`/main`) 사용, 생성·대기·재시도 UI 종료 |
| 구 END의 status 없음 | 완료로 추측하지 않고 기존 조회/종료 응답으로 상태 확인 |
| IN_PROGRESS·확인 실패 | 완료 처리 금지. 기존 오류·다시 확인 안내 유지 |

`saveQuit`는 `data.status`를 파싱하고 종료 결과에 반영한다. 단순 성공 boolean만으로 상태를 잃지 않도록 종료 Promise의 결과 계약을 정리한다. [가정] 기존 hook 내부의 종료 결과 타입을 확장하고 새 전역 상태나 서버 필드를 추가하지 않는다.

기존 404 취급을 곧 COMPLETED/ABANDONED 확정으로 해석하지 않는다. 세션 정리 뒤 404가 나면 기존 면접 상세/API 기록 확인으로 최종 상태를 확인한다. API가 아직 IN_PROGRESS이면 완료를 추정하지 않는다. 새 자동 COMPLETE 호출로 확인 절차를 대체하지 않는다.

## 3. 종료·소켓·녹화 경합

기존 `closePromiseRef`·`quitPromiseRef`·녹음 중지 Promise를 재사용하여 종료를 한 번만 수행한다. 나가기 버튼·헤더 이동·뒤로가기·로그아웃도 같은 종료 경로를 사용한다.

- 소켓 END, REST QUIT 응답, 마지막 답변 응답이 도착해도 먼저 확인한 서버 최종 상태와 모순되는 로컬 의도로 덮어쓰지 않는다.
- 현재 `isSessionClosedRef` 때문에 종료 중 소켓 이벤트를 무조건 무시하는 경로와 REST 최종 상태 반영을 함께 검토한다. 종료 정리는 중복하지 않되 최종 상태를 잃지 않는다.
- 중단 시 이미 수집한 ANSWER/FULL_INTERVIEW 파일의 기존 중지·업로드를 유지한다. 업로드 완료를 분석 완료·면접 완료로 해석하지 않는다.
- 녹화가 API 종료 기록보다 먼저 도착해도 Web에서 별도 분석 요청으로 보완하지 않는다. API가 IN_PROGRESS에서 기다렸다가 저장된 종료 상태로 결정한다.
- `recording.endReason`, 파일 존재, 답변 수, 타이머만으로 면접 상태를 결정하지 않는다.
- 종료 요청 실패 시 기존 재시도 UX를 유지한다. 브라우저 강제 종료의 전달 보장은 추가하지 않는다.

## 4. 피드백·재시도·조회

기존 COMPLETED 전용 목록·최초 요청 규칙을 유지하고 다음 우회를 보완한다.

1. 재시도 버튼과 음성·영상 재분석도 현재 면접이 COMPLETED일 때만 활성화·호출한다.
2. URL 직접 진입에서 비완료면 결과 카드·PENDING 화면·기업 코멘트·이전 비교를 표시하지 않는다.
3. API 409는 정책상 불가로 표시한다. 자동 재시도·생성 fallback·404 대체 생성으로 이어가지 않는다.
4. 비대상 상태를 확인하면 polling과 진행 중 요청을 정리한다. 늦은 성공 응답이 이전 피드백 상태를 다시 채우지 못하도록 기존 abort·request 식별 방식을 재사용한다.
5. 피드백 캐시·다른 면접에서 넘어온 결과·과거 중단 성공 결과가 남아도 현재 상태 검사 뒤에 렌더링한다.
6. 완료 면접의 실제 분석 장애·재채점·기존 미디어 재시도 제한·기업 코멘트는 유지한다.

서버 호출 비용 차단은 API 책임이다. 클라이언트 검사는 상태 안내와 불필요한 요청 방지를 위한 보완이다. 새로운 결과 화면이나 탭을 추가하지 않는다.

## 5. 구현 후 검증 기준

- 목록·직접 URL·최초 생성·재생성·음성/영상 재분석에서 비완료 호출·카드 없음.
- 나가기·헤더 이동·뒤로가기·로그아웃의 QUIT와 업로드 유지.
- COMPLETED 뒤 QUIT 응답과 ABANDONED 뒤 늦은 END 모두 서버 상태에 맞게 처리.
- 구 END·404·연결 단절을 완료로 추정하지 않음.
- 종료 기록 전 업로드·업로드 지연·중지 중 END 경합에서 중복 종료·생성 없음.
- 409 후 polling 정지와 늦은 응답 차단, 완료 면접 정상 흐름 유지.

구현 후 기존 테스트 체계로 필요한 경합 회귀를 검증하고 필수 `npm run lint && npm run build`를 수행한다. 이번 문서 작업에서는 실행하지 않는다.
