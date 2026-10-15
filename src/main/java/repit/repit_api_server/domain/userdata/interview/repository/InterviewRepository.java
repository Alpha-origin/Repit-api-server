package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;

import java.util.List;
import java.util.Optional;

public interface InterviewRepository extends JpaRepository<InterviewEntity, Long> {
    List<InterviewEntity> findAllByUserId(Long userId);

    Optional<InterviewEntity> findBySessionId(String sessionId);

    /**
     * 면접 기록 저장을 차지한다. 진행 중인 면접만 종료 상태로 바꾸고 1을 돌려받는다.
     *
     * <p>면접을 종료 상태로 바꾸는 곳은 기록 저장뿐이고 같은 트랜잭션에서 질문·답변을 넣는다. 그래서 종료 상태면 기록이
     * 이미 저장된 것이다. 채팅 서버가 같은 기록을 다시 보내면 0을 받아 아무것도 바꾸지 않는다.
     *
     * <p>먼저 읽어보고 판단하면 동시에 온 두 요청이 모두 처음이라고 보고 질문을 두 번 넣는다. 조건을 담은 갱신 한 번으로
     * 판단해, 뒤에 온 요청은 앞 요청이 끝날 때까지 행 잠금에서 기다렸다가 바뀐 상태를 보고 0을 받는다. 앞 요청이
     * 롤백되면 뒤 요청이 차지한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update InterviewEntity i
               set i.status = :status
             where i.interviewId = :interviewId
               and i.status = repit.repit_api_server.domain.userdata.interview.entity.enums.Status.IN_PROGRESS
            """)
    int claimResultSave(@Param("interviewId") Long interviewId, @Param("status") Status status);
}
