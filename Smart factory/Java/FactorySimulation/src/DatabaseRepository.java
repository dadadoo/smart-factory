import java.sql.*;

// DB 입출력 전담 — 센서 적재, 오류 로그, 상태 변경, 복구, 요약 조회
// 각 쿼리는 RetryExecutor를 통해 실패 시 재시도합니다.
public class DatabaseRepository {

    private final Connection     conn;
    private final AnomalyDetector detector;

    private static final String SQL_SENSOR =
        "INSERT INTO sensor_data (eq_id, temperature, vibration, humidity, rpm) VALUES (?, ?, ?, ?, ?)";
    private static final String SQL_LOG =
        "INSERT INTO error_logs (eq_id, log_level, error_code, message, sensor_temp, sensor_vib) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SQL_STATUS =
        "UPDATE equipments SET status = ? WHERE eq_id = ?";
    private static final String SQL_HISTORY =
        "INSERT INTO status_history (eq_id, prev_status, new_status) VALUES (?, ?, ?)";

    public DatabaseRepository(Connection conn, AnomalyDetector detector) {
        this.conn     = conn;
        this.detector = detector;
    }

    // ── 센서 데이터 INSERT → 생성된 PK 반환 ─────────────────

    public long insertSensorData(PreparedStatement ps, SensorData data) throws Exception {
        AppConfig cfg = AppConfig.getInstance();
        return RetryExecutor.run(
            () -> doInsertSensor(ps, data),
            "DB", "sensor_data INSERT (" + data.eqId + ")",
            cfg.queryRetryMax(), cfg.queryRetryDelayMs()
        );
    }

    private long doInsertSensor(PreparedStatement ps, SensorData data) throws SQLException {
        ps.setString(1, data.eqId);
        ps.setDouble(2, data.temperature);
        ps.setDouble(3, data.vibration);
        if (data.humidity >= 0) ps.setDouble(4, data.humidity);
        else                    ps.setNull(4, Types.DECIMAL);
        if (data.rpm >= 0)      ps.setInt(5, data.rpm);
        else                    ps.setNull(5, Types.INTEGER);
        ps.executeUpdate();

        try (ResultSet keys = ps.getGeneratedKeys()) {
            return keys.next() ? keys.getLong(1) : -1;
        }
    }

    // ── 이상 감지 처리: 로그 INSERT + CRITICAL 시 상태 전환 ──

    public void handleAnomaly(SensorData data, Equipment eq) throws Exception {
        String errorCode = detector.resolveErrorCode(data);
        String message   = detector.buildErrorMessage(data, eq);

        AppConfig cfg = AppConfig.getInstance();
        RetryExecutor.runVoid(
            () -> { doInsertLog(data, errorCode, message); return null; },
            "DB", "error_logs INSERT (" + data.eqId + ")",
            cfg.queryRetryMax(), cfg.queryRetryDelayMs()
        );

        AppLogger.warn(AppLogger.TAG_ANOMALY,
            String.format("%s [%s] %s — 온도=%.1f°C 진동=%.1f Hz",
                data.eqId, data.anomalyLevel, errorCode,
                data.temperature, data.vibration));

        if (data.anomalyLevel.equals("CRITICAL") && eq != null && !eq.status.equals("ERROR")) {
            changeStatus(eq, "ERROR");
            AppLogger.critical(AppLogger.TAG_ANOMALY,
                eq.eqId + " → ERROR 전환 (CRITICAL 임계 초과)");
        }
    }

    private void doInsertLog(SensorData data, String errorCode, String message) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOG)) {
            ps.setString(1, data.eqId);
            ps.setString(2, data.anomalyLevel);
            ps.setString(3, errorCode);
            ps.setString(4, message);
            ps.setDouble(5, data.temperature);
            ps.setDouble(6, data.vibration);
            ps.executeUpdate();
        }
    }

    // ── 설비 상태 변경 + 이력 기록 ───────────────────────────

    public void changeStatus(Equipment eq, String newStatus) throws Exception {
        String prevStatus = eq.status;
        eq.status = newStatus;

        AppConfig cfg = AppConfig.getInstance();
        RetryExecutor.runVoid(
            () -> { doUpdateStatus(eq.eqId, newStatus); return null; },
            "DB", "equipments UPDATE (" + eq.eqId + " → " + newStatus + ")",
            cfg.queryRetryMax(), cfg.queryRetryDelayMs()
        );
        RetryExecutor.runVoid(
            () -> { doInsertHistory(eq.eqId, prevStatus, newStatus); return null; },
            "DB", "status_history INSERT (" + eq.eqId + ")",
            cfg.queryRetryMax(), cfg.queryRetryDelayMs()
        );

        AppLogger.info(AppLogger.TAG_DB,
            String.format("%s 상태 변경: %s → %s (status_history 기록)", eq.eqId, prevStatus, newStatus));
    }

    private void doUpdateStatus(String eqId, String newStatus) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_STATUS)) {
            ps.setString(1, newStatus); ps.setString(2, eqId);
            ps.executeUpdate();
        }
    }

    private void doInsertHistory(String eqId, String prev, String next) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_HISTORY)) {
            ps.setString(1, eqId); ps.setString(2, prev); ps.setString(3, next);
            ps.executeUpdate();
        }
    }

    // ── ERROR 설비 자동 복구 (30% 확률) ──────────────────────

    public void autoRecovery(Iterable<Equipment> equipments) throws Exception {
        for (Equipment eq : equipments) {
            if (!eq.status.equals("ERROR")) continue;
            if (Math.random() < 0.30) {
                changeStatus(eq, "RUNNING");
                resolveOpenLogs(eq.eqId);
                AppLogger.info(AppLogger.TAG_RECOVER,
                    eq.eqId + " 자동 복구 완료 → RUNNING (미해결 로그 해결 처리)");
            }
        }
    }

    private void resolveOpenLogs(String eqId) throws Exception {
        AppConfig cfg = AppConfig.getInstance();
        RetryExecutor.runVoid(
            () -> {
                String sql = "UPDATE error_logs SET resolved = 1 WHERE eq_id = ? AND resolved = 0";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, eqId); ps.executeUpdate();
                }
                return null;
            },
            "DB", "error_logs 해결 처리 (" + eqId + ")",
            cfg.queryRetryMax(), cfg.queryRetryDelayMs()
        );
    }

    // ── 수집 완료 요약 ────────────────────────────────────────

    public void printSummary() throws SQLException {
        AppLogger.info(AppLogger.TAG_SUMMARY, "설비 현황 -----------------------------------");
        String sql = "SELECT eq_id, eq_name, status, "
                   + "(SELECT COUNT(*) FROM error_logs el WHERE el.eq_id = e.eq_id AND resolved=0) AS unresolved "
                   + "FROM equipments e ORDER BY eq_id";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            System.out.printf("  %-8s  %-22s  %-12s  %s%n", "장비ID", "장비명", "상태", "미해결 오류");
            System.out.println("  " + "-".repeat(58));
            while (rs.next()) {
                System.out.printf("  %-8s  %-22s  %-12s  %d 건%n",
                    rs.getString("eq_id"), rs.getString("eq_name"),
                    rs.getString("status"), rs.getInt("unresolved"));
            }
        }

        AppLogger.info(AppLogger.TAG_SUMMARY, "error_logs 레벨별 건수 ----------------------");
        String cntSql = "SELECT log_level, COUNT(*) AS cnt FROM error_logs GROUP BY log_level ORDER BY severity DESC";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(cntSql)) {
            while (rs.next()) {
                System.out.printf("  [%-8s]  %d 건%n", rs.getString("log_level"), rs.getInt("cnt"));
            }
        }
    }

    public PreparedStatement prepareSensorInsert() throws SQLException {
        return conn.prepareStatement(SQL_SENSOR, Statement.RETURN_GENERATED_KEYS);
    }
}
