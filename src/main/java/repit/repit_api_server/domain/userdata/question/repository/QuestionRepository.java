package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;

import java.util.List;

public interface QuestionRepository extends JpaRepository<QuestionEntity, Long> {
    List<QuestionEntity> findAllByInterviewId(Long interviewId);

    // 저장 순서가 곧 면접 진행 순서다. PK가 그 순서로 발급되므로 그대로 정렬한다.
    List<QuestionEntity> findAllByInterviewIdOrderByQuestionIdAsc(Long interviewId);

    // 지운 자리에 같은 (interview_id, chat_question_id)를 다시 넣는다. 파생 삭제는 flush 순서상
    // insert가 먼저 나가 유니크 제약에 걸리므로, 즉시 실행되는 벌크 삭제를 쓴다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from QuestionEntity q where q.interviewId = :interviewId")
    void deleteAllByInterviewId(Long interviewId);
}
