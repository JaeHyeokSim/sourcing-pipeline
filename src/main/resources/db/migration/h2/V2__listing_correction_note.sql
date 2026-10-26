-- 반려 보정 이력. 셀러가 입력한 값과 마켓에 올라간 값이 달라진 건을 화면에서 구분하기 위한 값
alter table market_listing add column correction_note varchar(500);
