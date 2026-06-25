-- ============================================================
-- 스마트 팩토리 DB  (장비 6대 / 인덱스 최적화 / 트리거 / 확장 로그)
-- ============================================================

CREATE DATABASE IF NOT EXISTS smart_factory CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE smart_factory;

-- ============================================================
-- 1. 설비 마스터
-- ============================================================
CREATE TABLE IF NOT EXISTS equipments (
    eq_id         VARCHAR(20)  NOT NULL,
    eq_name       VARCHAR(100) NOT NULL,
    eq_type       VARCHAR(30)  NOT NULL,
    location      VARCHAR(50)  NOT NULL,
    status        ENUM('RUNNING','STOPPED','ERROR','MAINTENANCE') NOT NULL DEFAULT 'STOPPED',
    registered_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (eq_id)
);

-- ============================================================
-- 2. 실시간 센서 데이터
-- ============================================================
CREATE TABLE IF NOT EXISTS sensor_data (
    data_id     BIGINT        NOT NULL AUTO_INCREMENT,
    eq_id       VARCHAR(20)   NOT NULL,
    temperature DECIMAL(6,2)  NOT NULL,
    vibration   DECIMAL(6,2)  NOT NULL,
    humidity    DECIMAL(5,2)      NULL,
    rpm         INT               NULL,
    recorded_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (data_id),
    FOREIGN KEY (eq_id) REFERENCES equipments(eq_id)
);

-- eq_id 기반 조회 최적화
CREATE INDEX idx_sensor_eq        ON sensor_data (eq_id);
-- recorded_at 기준 시계열 조회 최적화
CREATE INDEX idx_sensor_time      ON sensor_data (recorded_at);
-- eq_id + recorded_at 복합: "특정 장비의 최근 N건" 조회 핵심 인덱스
CREATE INDEX idx_sensor_eq_time   ON sensor_data (eq_id, recorded_at DESC);

-- ============================================================
-- 3. 설비 상태 변경 이력
-- ============================================================
CREATE TABLE IF NOT EXISTS status_history (
    history_id INT          NOT NULL AUTO_INCREMENT,
    eq_id      VARCHAR(20)  NOT NULL,
    prev_status ENUM('RUNNING','STOPPED','ERROR','MAINTENANCE') NULL,
    new_status  ENUM('RUNNING','STOPPED','ERROR','MAINTENANCE') NOT NULL,
    changed_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_id),
    FOREIGN KEY (eq_id) REFERENCES equipments(eq_id)
);

-- eq_id 기반 이력 조회
CREATE INDEX idx_hist_eq        ON status_history (eq_id);
-- "장비별 최근 상태" 빠른 조회 (eq_id, changed_at DESC)
CREATE INDEX idx_hist_eq_time   ON status_history (eq_id, changed_at DESC);

-- ─────────────────────────────────────────
-- 트리거: 상태 중복 기록 방지
-- prev_status = new_status 이면 INSERT 차단
-- ─────────────────────────────────────────
DELIMITER $$
CREATE TRIGGER trg_no_duplicate_status
BEFORE INSERT ON status_history
FOR EACH ROW
BEGIN
    IF NEW.prev_status IS NOT NULL AND NEW.prev_status = NEW.new_status THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'status_history: prev_status와 new_status가 동일합니다 — 중복 기록 차단';
    END IF;
END$$
DELIMITER ;

-- ============================================================
-- 4. 이상 징후 오류 로그  (확장)
-- ============================================================
CREATE TABLE IF NOT EXISTS error_logs (
    log_id      INT          NOT NULL AUTO_INCREMENT,
    eq_id       VARCHAR(20)  NOT NULL,

    -- log_level 은 표시용 원문 유지, severity 로 숫자 정규화하여 범위 조회 최적화
    log_level   ENUM('INFO','WARN','CRITICAL') NOT NULL,
    severity    TINYINT      NOT NULL DEFAULT 1
                COMMENT '1=INFO  2=WARN  3=CRITICAL — 숫자 범위 조회용 정규화',

    error_code  VARCHAR(20)      NULL,
    message     VARCHAR(500) NOT NULL,
    sensor_temp DECIMAL(6,2)     NULL,
    sensor_vib  DECIMAL(6,2)     NULL,

    resolved    TINYINT(1)   NOT NULL DEFAULT 0,
    resolved_at TIMESTAMP        NULL     COMMENT '해결 처리된 시각 (resolved=1 로 변경될 때 기록)',
    alert_sent  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '외부 알림(이메일/슬랙 등) 발송 여부',

    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id),
    FOREIGN KEY (eq_id) REFERENCES equipments(eq_id)
);

-- eq_id 기반 로그 조회
CREATE INDEX idx_log_eq          ON error_logs (eq_id);
-- 미해결 로그 조회 최적화  (resolved=0 이 대부분이므로 선택도 높음)
CREATE INDEX idx_log_resolved    ON error_logs (resolved);
-- "특정 장비의 미해결 로그" 복합 인덱스 — Java autoRecovery() UPDATE 경로
CREATE INDEX idx_log_eq_resolved ON error_logs (eq_id, resolved);

-- ─────────────────────────────────────────
-- 트리거: resolved=1 로 바뀔 때 resolved_at 자동 기록
-- ─────────────────────────────────────────
DELIMITER $$
CREATE TRIGGER trg_set_resolved_at
BEFORE UPDATE ON error_logs
FOR EACH ROW
BEGIN
    IF NEW.resolved = 1 AND OLD.resolved = 0 THEN
        SET NEW.resolved_at = CURRENT_TIMESTAMP;
    END IF;
END$$
DELIMITER ;

-- ─────────────────────────────────────────
-- 트리거: log_level → severity 자동 매핑 (INSERT 시)
-- ─────────────────────────────────────────
DELIMITER $$
CREATE TRIGGER trg_severity_insert
BEFORE INSERT ON error_logs
FOR EACH ROW
BEGIN
    SET NEW.severity = CASE NEW.log_level
        WHEN 'CRITICAL' THEN 3
        WHEN 'WARN'     THEN 2
        ELSE 1
    END;
END$$
DELIMITER ;

-- ─────────────────────────────────────────
-- 트리거: log_level 이 UPDATE 되면 severity 재동기화
-- ─────────────────────────────────────────
DELIMITER $$
CREATE TRIGGER trg_severity_update
BEFORE UPDATE ON error_logs
FOR EACH ROW
BEGIN
    IF NEW.log_level <> OLD.log_level THEN
        SET NEW.severity = CASE NEW.log_level
            WHEN 'CRITICAL' THEN 3
            WHEN 'WARN'     THEN 2
            ELSE 1
        END;
    END IF;
END$$
DELIMITER ;

-- ============================================================
-- 5. 장비 6대 등록
-- ============================================================
INSERT INTO equipments (eq_id, eq_name, eq_type, location, status) VALUES
('EQ-001', '1호 프레스 가공기',   '프레스',     'A동 1라인', 'RUNNING'),
('EQ-002', '2호 플라스틱 사출기', '사출기',     'A동 2라인', 'RUNNING'),
('EQ-003', '3호 CNC 선반',        'CNC',        'B동 1라인', 'RUNNING'),
('EQ-004', '4호 용접 로봇',       '용접로봇',   'B동 2라인', 'RUNNING'),
('EQ-005', '5호 컨베이어 벨트',   '컨베이어',   'C동 1라인', 'RUNNING'),
('EQ-006', '6호 레이저 절단기',   '레이저절단', 'C동 2라인', 'RUNNING');

-- 초기 상태 이력 (최초 기동 — prev_status NULL 허용)
INSERT INTO status_history (eq_id, prev_status, new_status) VALUES
('EQ-001', NULL, 'RUNNING'), ('EQ-002', NULL, 'RUNNING'),
('EQ-003', NULL, 'RUNNING'), ('EQ-004', NULL, 'RUNNING'),
('EQ-005', NULL, 'RUNNING'), ('EQ-006', NULL, 'RUNNING');

-- ============================================================
-- 6. 센서 데이터 샘플 (정상 + 이상 혼합)
-- ============================================================
INSERT INTO sensor_data (eq_id, temperature, vibration, rpm) VALUES
('EQ-001', 45.20, 12.50, 1200),
('EQ-001', 46.15, 13.10, 1205),
('EQ-001', 45.80, 12.80, 1198);

INSERT INTO sensor_data (eq_id, temperature, vibration, humidity, rpm) VALUES
('EQ-002', 82.40, 45.00, 68.5, 850);   -- WARN: 온도+진동 이상

INSERT INTO sensor_data (eq_id, temperature, vibration, rpm) VALUES
('EQ-003', 55.30, 18.20, 2400),
('EQ-003', 56.10, 17.90, 2395);

INSERT INTO sensor_data (eq_id, temperature, vibration) VALUES
('EQ-004', 72.00, 52.30);              -- WARN: 진동 이상

INSERT INTO sensor_data (eq_id, temperature, vibration, rpm) VALUES
('EQ-005', 38.50, 9.10, 600),
('EQ-005', 37.90, 9.30, 598);

INSERT INTO sensor_data (eq_id, temperature, vibration) VALUES
('EQ-006', 91.50, 61.70);              -- CRITICAL: 온도+진동 동시 임계 초과

-- ============================================================
-- 7. 오류 로그 샘플 (severity 는 트리거가 자동 기록)
-- ============================================================
INSERT INTO error_logs (eq_id, log_level, error_code, message, sensor_temp, sensor_vib) VALUES
('EQ-002', 'WARN',     'VIB_SPIKE', '2호 사출기 진동 40Hz 초과 — 베어링 마모 의심',            82.40, 45.00),
('EQ-004', 'WARN',     'VIB_SPIKE', '4호 용접 로봇 진동 40Hz 초과 — 베어링 마모 의심',         72.00, 52.30),
('EQ-006', 'CRITICAL', 'MULTI_ERR', '6호 레이저 절단기 온도+진동 동시 임계 초과 — 즉시 중지', 91.50, 61.70);

-- EQ-002, EQ-006 → ERROR 상태 전환
UPDATE equipments SET status = 'ERROR' WHERE eq_id IN ('EQ-002', 'EQ-006');

INSERT INTO status_history (eq_id, prev_status, new_status) VALUES
('EQ-002', 'RUNNING', 'ERROR'),
('EQ-006', 'RUNNING', 'ERROR');

-- ============================================================
-- 8. 뷰(VIEW) — 반복 사용 조회를 미리 정의
-- ============================================================

-- ① 장비별 최신 센서값 (대시보드 카드용)
CREATE OR REPLACE VIEW v_latest_sensor AS
SELECT sd.eq_id, sd.temperature, sd.vibration, sd.recorded_at
FROM sensor_data sd
WHERE sd.data_id = (
    SELECT MAX(s2.data_id) FROM sensor_data s2 WHERE s2.eq_id = sd.eq_id
);

-- ② 장비별 현재 상태 (status_history 최신 행)
CREATE OR REPLACE VIEW v_current_status AS
SELECT sh.eq_id, sh.new_status AS current_status, sh.changed_at AS status_since
FROM status_history sh
WHERE sh.history_id = (
    SELECT MAX(h2.history_id) FROM status_history h2 WHERE h2.eq_id = sh.eq_id
);

-- ③ 미해결 오류 로그 (알림 발송 대상 포함)
CREATE OR REPLACE VIEW v_open_logs AS
SELECT log_id, eq_id, log_level, severity, error_code, message,
       sensor_temp, sensor_vib, alert_sent, created_at
FROM error_logs
WHERE resolved = 0
ORDER BY severity DESC, created_at DESC;

-- ④ 종합 대시보드 뷰 (설비 상태 + 최신 센서 + 미해결 오류 수)
CREATE OR REPLACE VIEW v_dashboard AS
SELECT
    e.eq_id,
    e.eq_name,
    e.eq_type,
    e.location,
    e.status,
    ls.temperature   AS last_temp,
    ls.vibration     AS last_vib,
    ls.recorded_at   AS last_updated,
    IFNULL(open_cnt.cnt, 0) AS unresolved_errors
FROM equipments e
LEFT JOIN v_latest_sensor ls ON ls.eq_id = e.eq_id
LEFT JOIN (
    SELECT eq_id, COUNT(*) AS cnt
    FROM error_logs
    WHERE resolved = 0
    GROUP BY eq_id
) open_cnt ON open_cnt.eq_id = e.eq_id
ORDER BY e.eq_id;

-- ============================================================
-- 9. 조회 쿼리 모음
-- ============================================================

-- [A] 이상 감지 데이터 조회 (Java detectLevel 기준과 동일)
SELECT
    sd.data_id,
    sd.eq_id,
    e.eq_name,
    sd.temperature,
    sd.vibration,
    sd.recorded_at,
    CASE
        WHEN sd.temperature >= 90 OR sd.vibration >= 60 THEN 'CRITICAL'
        WHEN sd.temperature >= 80 OR sd.vibration >= 40 THEN 'WARN'
        ELSE 'NORMAL'
    END AS anomaly_level
FROM sensor_data sd
JOIN equipments e ON sd.eq_id = e.eq_id
WHERE sd.temperature >= 80 OR sd.vibration >= 40
ORDER BY sd.recorded_at DESC;

-- [B] 전체 오류 로그 조회 (심각도 내림차순)
SELECT
    el.log_id,
    el.eq_id,
    e.eq_name,
    el.log_level,
    el.severity,
    el.error_code,
    el.message,
    el.sensor_temp,
    el.sensor_vib,
    el.resolved,
    el.resolved_at,
    el.alert_sent,
    el.created_at
FROM error_logs el
JOIN equipments e ON el.eq_id = e.eq_id
ORDER BY el.severity DESC, el.created_at DESC;

-- [C] 장비별 최근 상태 조회 (v_current_status 뷰 활용)
SELECT cs.eq_id, e.eq_name, cs.current_status, cs.status_since
FROM v_current_status cs
JOIN equipments e ON e.eq_id = cs.eq_id;

-- [D] 대시보드 종합 현황 (v_dashboard 뷰)
SELECT * FROM v_dashboard;

-- [E] 특정 장비의 최근 10건 센서 이력
--     idx_sensor_eq_time 인덱스가 이 패턴을 커버
SELECT data_id, temperature, vibration, recorded_at
FROM sensor_data
WHERE eq_id = 'EQ-001'
ORDER BY recorded_at DESC
LIMIT 10;

-- [F] severity >= 2(WARN 이상) 미해결 로그
--     idx_log_eq_resolved 인덱스 활용
SELECT * FROM v_open_logs WHERE severity >= 2;

-- [G] 알림 미발송 CRITICAL 로그 (alert_sent = 0)
SELECT log_id, eq_id, message, created_at
FROM error_logs
WHERE log_level = 'CRITICAL' AND alert_sent = 0
ORDER BY created_at DESC;
