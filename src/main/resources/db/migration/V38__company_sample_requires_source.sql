-- V35가 출처가 하나도 없는 기업까지 SAMPLE로 표시했다(NOT EXISTS가 빈 집합에서 참이다). 가져오기의 판정
-- (CompanyDataImporter.isSample: 출처가 하나 이상이고 모두 SAMPLE)과 맞춰 다시 계산한다. V35는 이미 적용된 곳이 있어
-- 고치지 않고 여기서 바로잡는다.
UPDATE company c
   SET sample = EXISTS (SELECT 1 FROM company_source s WHERE s.company_id = c.company_id)
            AND NOT EXISTS (SELECT 1
                              FROM company_source s
                             WHERE s.company_id = c.company_id
                               AND s.source_type <> 'SAMPLE');
