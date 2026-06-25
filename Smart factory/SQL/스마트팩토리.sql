-- ============================================================
-- 스마트 팩토리 확장 DB (장비 6대 / 랜덤 상태 변화 대응)
-- ============================================================

-- 1. 데이터베이스 생성 및 진입
CREATE DATABASE IF NOT EXISTS smart_factory;
USE smart_factory;

-- ============================================================
-- 2. 설비 마스터 테이블 (장비 6대)
-- ============================================================
CREATE TABLE equipments (
    eq_id        VARCHAR(20)  NOT NULL,           -- 설비 고유 코드 (예: 'EQ-001')
    eq_name      VARCHAR(100) NOT NULL,           -- 설비 이름
    eq_type      VARCHAR(30)  NOT NULL,           -- 설비 유형 (예: '프레스', '사출기', '컨베이어')
    location     VARCHAR(50)  NOT NULL,           -- 공장 내 위치
    status       VARCHAR(10)  NOT NULL DEFAULT 'STOPPED', -- 'RUNNING' / 'STOPPED' / 'ERROR' / 'MAINTENANCE'
    registered_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (eq_id)
);

-- ============================================================
-- 3. 실시간 센서 데이터 수집 테이블
--    (1~2초 간격으로 데이터가 쌓이므로 BIGINT AUTO_INCREMENT)
-- ============================================================
CREATE TABLE sensor_data (
    data_id      BIGINT       NOT NULL AUTO_INCREMENT,
    eq_id        VARCHAR(20)  NOT NULL,
    temperature  DECIMAL(6,2) NOT NULL,  -- 온도 (°C), 예: 75.45
    vibration    DECIMAL(6,2) NOT NULL,  -- 진동 (Hz)
    humidity     DECIMAL(5,2)     NULL,  -- 습도 (%), 일부 장비만 측정
    rpm          INT              NULL,  -- 회전수 (RPM), 회전 장비만 해당
    recorded_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (data_id),
    FOREIGN KEY (eq_id) REFERENCES equipments(eq_id)
);

-- ============================================================
-- 4. 설비 상태 변경 이력 테이블
--    (RUNNING → ERROR 등 상태가 바뀔 때마다 기록)
-- ============================================================
CREATE TABLE status_history (
    history_id   INT          NOT NULL AUTO_INCREMENT,
    eq_id        VARCHAR(20)  NOT NULL,
    prev_status  VARCHAR(10)      NULL,   -- 이전 상태 (최초 등록 시 NULL)
    new_status   VARCHAR(10)  NOT NULL,   -- 변경된 상태
    changed_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_id),
    FOREIGN KEY (eq_id) REFERENCES equipments(eq_id)
);

-- ============================================================
-- 5. 설비 이상 징후 오류 로그 테이블
-- ============================================================
CREATE TABLE error_logs (
    log_id       INT          NOT NULL AUTO_INCREMENT,
    eq_id        VARCHAR(20)  NOT NULL,
    log_level    VARCHAR(10)  NOT NULL,   -- 'INFO' / 'WARN' / 'CRITICAL'
    error_code   VARCHAR(20)      NULL,   -- 오류 분류 코드 (예: 'OVERHEAT', 'VIB_SPIKE')
    message      VARCHAR(500) NOT NULL,   -- 오류 상세 내용
    sensor_temp  DECIMAL(6,2)     NULL,   -- 오류 발생 시점의 온도 스냅샷
    sensor_vib   DECIMAL(6,2)     NULL,   -- 오류 발생 시점의 진동 스냅샷
    resolved     TINYINT(1)   NOT NULL DEFAULT 0, -- 0: 미해결, 1: 해결됨
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id),
    FOREIGN KEY (eq_id) REFERENCES equipments(eq_id)
);

-- ============================================================
-- 6. 장비 6대 등록
-- ============================================================
INSERT INTO equipments (eq_id, eq_name, eq_type, location, status) VALUES
('EQ-001', '1호 프레스 가공기',      '프레스',     'A동 1라인', 'RUNNING'),
('EQ-002', '2호 플라스틱 사출기',    '사출기',     'A동 2라인', 'RUNNING'),
('EQ-003', '3호 CNC 선반',           'CNC',        'B동 1라인', 'RUNNING'),
('EQ-004', '4호 용접 로봇',          '용접로봇',   'B동 2라인', 'RUNNING'),
('EQ-005', '5호 컨베이어 벨트',      '컨베이어',   'C동 1라인', 'RUNNING'),
('EQ-006', '6호 레이저 절단기',      '레이저절단', 'C동 2라인', 'RUNNING');

-- 초기 상태 이력 등록 (RUNNING으로 시작)
INSERT INTO status_history (eq_id, prev_status, new_status) VALUES
('EQ-001', NULL, 'RUNNING'),
('EQ-002', NULL, 'RUNNING'),
('EQ-003', NULL, 'RUNNING'),
('EQ-004', NULL, 'RUNNING'),
('EQ-005', NULL, 'RUNNING'),
('EQ-006', NULL, 'RUNNING');

-- ============================================================
-- 7. 센서 데이터 샘플 삽입 (정상 + 이상 혼합)
-- ============================================================

-- EQ-001 프레스: 정상 범위
INSERT INTO sensor_data (eq_id, temperature, vibration, rpm) VALUES
('EQ-001', 45.20, 12.50, 1200),
('EQ-001', 46.15, 13.10, 1205),
('EQ-001', 45.80, 12.80, 1198);

-- EQ-002 사출기: 온도 이상 발생!
INSERT INTO sensor_data (eq_id, temperature, vibration, humidity, rpm) VALUES
('EQ-002', 82.40, 45.00, 68.5, 850);

-- EQ-003 CNC 선반: 정상
INSERT INTO sensor_data (eq_id, temperature, vibration, rpm) VALUES
('EQ-003', 55.30, 18.20, 2400),
('EQ-003', 56.10, 17.90, 2395);

-- EQ-004 용접 로봇: 진동 이상 발생!
INSERT INTO sensor_data (eq_id, temperature, vibration) VALUES
('EQ-004', 72.00, 52.30);

-- EQ-005 컨베이어: 정상
INSERT INTO sensor_data (eq_id, temperature, vibration, rpm) VALUES
('EQ-005', 38.50, 9.10, 600),
('EQ-005', 37.90, 9.30, 598);

-- EQ-006 레이저 절단기: 온도 + 진동 동시 이상!
INSERT INTO sensor_data (eq_id, temperature, vibration) VALUES
('EQ-006', 91.50, 61.70);

-- ============================================================
-- 8. 이상 감지 데이터 조회 (Java의 isAnomaly() 기준과 동일)
-- ============================================================
SELECT
    sd.data_id,
    sd.eq_id,
    eq.eq_name,
    sd.temperature,
    sd.vibration,
    sd.recorded_at,
    CASE
        WHEN sd.temperature >= 90 OR sd.vibration >= 60 THEN 'CRITICAL'
        WHEN sd.temperature >= 80 OR sd.vibration >= 40 THEN 'WARN'
        ELSE 'NORMAL'
    END AS anomaly_level
FROM sensor_data sd
JOIN equipments eq ON sd.eq_id = eq.eq_id
WHERE sd.temperature >= 80 OR sd.vibration >= 40
ORDER BY sd.recorded_at DESC;

-- ============================================================
-- 9. 오류 로그 삽입 (이상 감지된 장비들)
-- ============================================================
INSERT INTO error_logs (eq_id, log_level, error_code, message, sensor_temp, sensor_vib) VALUES
('EQ-002', 'CRITICAL', 'OVERHEAT',  'EQ-002 사출기 온도 80°C 초과 및 진동 급증 감지. 즉시 점검 필요.', 82.40, 45.00),
('EQ-004', 'WARN',     'VIB_SPIKE', 'EQ-004 용접 로봇 진동 40Hz 초과. 베어링 마모 의심.', 72.00, 52.30),
('EQ-006', 'CRITICAL', 'MULTI_ERR', 'EQ-006 레이저 절단기 온도 90°C + 진동 60Hz 동시 임계 초과. 즉시 가동 중지.', 91.50, 61.70);

-- EQ-002, EQ-006 상태 ERROR로 업데이트
UPDATE equipments SET status = 'ERROR' WHERE eq_id IN ('EQ-002', 'EQ-006');

-- 상태 변경 이력 기록
INSERT INTO status_history (eq_id, prev_status, new_status) VALUES
('EQ-002', 'RUNNING', 'ERROR'),
('EQ-006', 'RUNNING', 'ERROR');

-- ============================================================
-- 10. 전체 오류 로그 조회
-- ============================================================
SELECT
    el.log_id,
    el.eq_id,
    eq.eq_name,
    el.log_level,
    el.error_code,
    el.message,
    el.sensor_temp,
    el.sensor_vib,
    el.resolved,
    el.created_at
FROM error_logs el
JOIN equipments eq ON el.eq_id = eq.eq_id
ORDER BY el.created_at DESC;

-- ============================================================
-- 11. 설비별 현황 종합 뷰 (대시보드용)
-- ============================================================
SELECT
    eq.eq_id,
    eq.eq_name,
    eq.eq_type,
    eq.location,
    eq.status,
    latest.temperature AS last_temp,
    latest.vibration   AS last_vib,
    latest.recorded_at AS last_updated,
    (SELECT COUNT(*) FROM error_logs el WHERE el.eq_id = eq.eq_id AND el.resolved = 0) AS unresolved_errors
FROM equipments eq
LEFT JOIN sensor_data latest ON latest.data_id = (
    SELECT MAX(data_id) FROM sensor_data WHERE eq_id = eq.eq_id
)
ORDER BY eq.eq_id;