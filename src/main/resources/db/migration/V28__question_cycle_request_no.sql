-- 사이클은 실패하거나 콜백을 잃으면 같은 행으로 다시 요청한다. 그 사이 이전 요청의 콜백이 늦게 오면 작업 id만으로는
-- 가려낼 수 없다 -- 다시 요청할 때 작업 id를 비우고, 새 접수 응답이 오기 전까지는 비어 있기 때문이다.
-- 요청할 때마다 이 번호를 올리고 콜백 주소에 실어, 번호가 다른 콜백은 버린다.
ALTER TABLE question_cycle
    ADD COLUMN request_no INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN question_cycle.request_no IS
    '이 사이클을 요청한 차례. 다시 요청할 때마다 1씩 오르고 콜백 주소(requestNo)에 실린다. 다른 차례의 콜백은 버린다.';
