-- 클레임 직렬화용 게이트 행. 동시 실행 상한 판단(RUNNING 개수 세기 → 점유)을
-- 이 행 하나의 FOR UPDATE 잠금 안에서 하도록 만들어 check-then-act 경합을 없앤다.
create table collect_claim_gate (
    id int primary key
);
insert into collect_claim_gate (id) values (1);
