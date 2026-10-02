package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.PoolQuestionEntity;

import java.util.List;

public interface PoolQuestionRepository extends JpaRepository<PoolQuestionEntity, Long> {

    /** 이 면접에 이미 꺼내 준 세트. 준비를 다시 시도할 때 새 세트를 태우지 않으려고 본다. */
    List<PoolQuestionEntity> findAllByInterviewIdOrderByQuestionIdAsc(Long interviewId);

    List<PoolQuestionEntity> findAllByCycleIdAndUsedAtIsNullOrderBySetNoAscQuestionIdAsc(Long cycleId);

    /** 같은 모드의 최근 사이클 질문 본문. 새 사이클이 이것과 겹치지 않게 만든다. */
    @Query("""
            select q.question from PoolQuestionEntity q, QuestionCycleEntity c
             where q.cycleId = c.cycleId
               and c.userId = :userId
               and c.mode = :mode
               and c.cycleId <> :cycleId
             order by c.cycleId desc, q.questionId asc
            """)
    List<String> findRecentQuestions(@Param("userId") Long userId, @Param("mode") InterviewMode mode,
                                     @Param("cycleId") Long cycleId, Pageable pageable);
}
