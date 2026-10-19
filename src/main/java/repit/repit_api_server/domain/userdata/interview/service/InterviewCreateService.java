package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewCreateRequestEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewCreateRequestRepository;
import repit.repit_api_server.global.error.ErrorCodes;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 최종 면접 생성(POST /api/interviews/create). 웹이 기업 선택 화면의 시작 버튼에서 한 번 부른다.
 *
 * <p>면접·면접관·요청 키를 한 트랜잭션에 저장한다. 검증이 하나라도 실패하면 모두 롤백돼 면접 없는 키나 키 없는 면접이
 * 남지 않는다. 이 트랜잭션은 외부 서버를 부르지 않는다 — 질문 준비는 기존대로 웹이 생성 뒤 따로 시작한다
 * (POST /api/interviews/{id}). 그래서 같은 키를 다시 보내도 준비 작업이 새로 생기지 않는다.
 *
 * <p>{@code Idempotency-Key}는 선택이다. 없으면 예전처럼 요청마다 새 면접을 만든다. 있으면 (사용자, 키)마다 면접 하나다.
 * <ul>
 *   <li>같은 키·같은 설정: 처음 만든 면접을 그대로 돌려준다. 그 사이 기업이 숨겨졌어도 다시 검증하지 않는다.</li>
 *   <li>같은 키·다른 설정: 409 IDEMPOTENCY_KEY_REUSED.</li>
 *   <li>같은 키가 동시에 오면 유일 제약이 하나만 남긴다. 진 쪽은 롤백된 뒤 트랜잭션 밖에서 다시 읽어 위 규칙으로 답한다.</li>
 * </ul>
 * 키 저장소를 읽지 못하면 그대로 실패한다. 키 보장을 건너뛰고 새 면접을 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InterviewCreateService {

    private static final Logger log = LoggerFactory.getLogger(InterviewCreateService.class);

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    // 웹이 JavaScript number로 다루는 id라 안전한 정수 범위를 넘으면 같은 값으로 되돌아오지 않는다.
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final InterviewService interviewService;
    private final InterviewCreateRequestRepository interviewCreateRequestRepository;
    private final TransactionTemplate transactionTemplate;

    public InterviewResponse create(Long userId, String idempotencyKey, CreateInterviewRequest request) {
        UUID key = parseKey(idempotencyKey);
        verifyCompanyId(request.getCompanyId());
        if (key == null) {
            return transactionTemplate.execute(status -> interviewService.createInterview(userId, request));
        }

        String hash = InterviewCreateFingerprint.of(request);
        Optional<InterviewCreateRequestEntity> existing =
                interviewCreateRequestRepository.findByUserIdAndIdempotencyKey(userId, key);
        if (existing.isPresent()) {
            return replay(userId, existing.get(), hash);
        }

        try {
            return transactionTemplate.execute(status -> {
                InterviewResponse created = interviewService.createInterview(userId, request);
                interviewCreateRequestRepository.saveAndFlush(InterviewCreateRequestEntity.builder()
                        .userId(userId)
                        .idempotencyKey(key)
                        .requestHash(hash)
                        .interviewId(created.getInterviewId())
                        .build());
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 다른 요청이 먼저 커밋했다. 이 트랜잭션은 이미 롤백됐으니 밖에서 새로 읽는다.
            InterviewCreateRequestEntity raced = interviewCreateRequestRepository
                    .findByUserIdAndIdempotencyKey(userId, key)
                    .orElseThrow(() -> e);
            log.info("같은 요청 키로 먼저 만든 면접을 돌려줍니다. userId={}, interviewId={}", userId, raced.getInterviewId());
            return replay(userId, raced, hash);
        }
    }

    private InterviewResponse replay(Long userId, InterviewCreateRequestEntity existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new BusinessException("이 요청 키는 다른 면접 설정에 이미 쓰였습니다. 새로 시작하려면 새 요청 키를 보내주세요.",
                    HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED);
        }
        return interviewService.getInterviewById(userId, existing.getInterviewId());
    }

    /** 헤더가 없으면 null. 있으면 하이픈이 들어간 UUID여야 한다. 빈 값도 형식 오류다. */
    static UUID parseKey(String value) {
        if (value == null) {
            return null;
        }
        if (!UUID_FORMAT.matcher(value.strip()).matches()) {
            throw invalid(IDEMPOTENCY_KEY_HEADER + " 헤더는 UUID여야 합니다.");
        }
        return UUID.fromString(value.strip().toLowerCase(Locale.ROOT));
    }

    // 고르지 않았으면 키를 생략한다. 보냈다면 서버가 부여한 양의 id여야 한다.
    static void verifyCompanyId(Long companyId) {
        if (companyId != null && (companyId < 1 || companyId > MAX_SAFE_INTEGER)) {
            throw invalid("companyId는 1 이상 " + MAX_SAFE_INTEGER + " 이하의 정수여야 합니다.");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.INVALID_INTERVIEW_REQUEST);
    }
}
