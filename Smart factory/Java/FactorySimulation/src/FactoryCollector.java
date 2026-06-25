import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

// ============================================================
// 센서 데이터 객체 구조 (장비 유형별 필드 확장)
// ============================================================
class SensorData {
    String  eqId;
    double  temperature;
    double  vibration;
    double  humidity;   // 일부 장비만 사용 (-1이면 미측정)
    int     rpm;        // 회전 장비만 사용 (-1이면 미측정)
    boolean isAnomaly;
    String  anomalyLevel; // "NORMAL" / "WARN" / "CRITICAL"

    public SensorData(String eqId, double temperature, double vibration, double humidity, int rpm) {
        this.eqId        = eqId;
        this.temperature = temperature;
        this.vibration   = vibration;
        this.humidity    = humidity;
        this.rpm         = rpm;
        this.anomalyLevel = detectAnomalyLevel(temperature, vibration);
        this.isAnomaly   = !this.anomalyLevel.equals("NORMAL");
    }

    // SQL의 CASE WHEN과 동일한 이상 판단 기준
    private String detectAnomalyLevel(double temp, double vib) {
        if (temp >= 90.0 || vib >= 60.0) return "CRITICAL";
        if (temp >= 80.0 || vib >= 40.0) return "WARN";
        return "NORMAL";
    }

    @Override
    public String toString() {
        return String.format("[%s] 온도=%.1f°C  진동=%.1f Hz  RPM=%s  [%s]",
            eqId, temperature, vibration,
            rpm == -1 ? "N/A" : String.valueOf(rpm),
            anomalyLevel);
    }
}

// ============================================================
// 설비 정보 객체
// ============================================================
class Equipment {
    String eqId;
    String eqName;
    String eqType;
    String location;
    String status; // "RUNNING" / "ERROR" / "STOPPED" / "MAINTENANCE"

    // 정상 범위 기준값 (장비마다 다름)
    double baseTempMin, baseTempMax;
    double baseVibMin,  baseVibMax;
    int    baseRpmMin,  baseRpmMax;
    boolean hasHumidity;

    public Equipment(String eqId, String eqName, String eqType, String location,
                     double baseTempMin, double baseTempMax,
                     double baseVibMin,  double baseVibMax,
                     int baseRpmMin, int baseRpmMax, boolean hasHumidity) {
        this.eqId        = eqId;
        this.eqName      = eqName;
        this.eqType      = eqType;
        this.location    = location;
        this.status      = "RUNNING";
        this.baseTempMin = baseTempMin;
        this.baseTempMax = baseTempMax;
        this.baseVibMin  = baseVibMin;
        this.baseVibMax  = baseVibMax;
        this.baseRpmMin  = baseRpmMin;
        this.baseRpmMax  = baseRpmMax;
        this.hasHumidity = hasHumidity;
    }
}

// ============================================================
// 메인 수집기
// ============================================================
public class FactoryCollector {

    // MySQL 연결 정보 (본인의 DB 계정 정보에 맞게 수정)
    private static final String DB_URL  = "jdbc:mysql://localhost:3306/smart_factory"
                                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "admin1234"; // 본인의 MySQL 비밀번호

    // 수집 주기(ms) / 라운드 수
    private static final int COLLECT_INTERVAL_MS = 1500;
    private static final int TOTAL_ROUNDS        = 10;

    // 이상 발생 확률: 정상 상태 장비가 한 틱에 이상 데이터를 낼 확률
    private static final double ANOMALY_CHANCE    = 0.20;  // 20%
    private static final double CRITICAL_CHANCE   = 0.08;  // ANOMALY 중 CRITICAL 비율

    private static final Random  RAND    = new Random();
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss");

    // ============================================================
    // 장비 6대 정의 (유형별로 정상 범위가 다름)
    // ============================================================
    private static final List<Equipment> EQUIPMENTS = Arrays.asList(
        new Equipment("EQ-001", "1호 프레스 가공기",   "프레스","Line-1",
                      40, 65,   10, 22,  1100, 1300, false),
        new Equipment("EQ-002", "2호 플라스틱 사출기", "사출기","Line-1",
                      45, 70,   12, 25,   800,  950, true),
        new Equipment("EQ-003", "3호 CNC 선반",        "CNC","Line-2",
                      50, 68,   14, 28,  2300, 2500, false),
        new Equipment("EQ-004", "4호 용접 로봇",       "용접로봇","Line-2",
                      60, 78,   16, 32,    -1,   -1, false),
        new Equipment("EQ-005", "5호 컨베이어 벨트",   "컨베이어","Line-3",
                      30, 50,    6, 16,   550,  650, false),
        new Equipment("EQ-006", "6호 레이저 절단기",   "레이저절단","Line-3",
                      55, 75,   18, 35,    -1,   -1, false)
    );

    // ============================================================
    // 메인
    // ============================================================
    public static void main(String[] args) {

        printBanner();

        // 큐(Queue): 폐쇄망 설비 → 서버 간 데이터 버퍼 (Java 코드의 핵심 구조)
        Queue<SensorData> bufferQueue = new LinkedList<>();

        System.out.println("[시스템] MySQL 데이터베이스 연결 시도...\n");

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {

            System.out.println("[DB] 연결 성공!\n");
            System.out.println("=".repeat(65));

            for (int round = 1; round <= TOTAL_ROUNDS; round++) {

                System.out.printf("%n[Round %2d / %d]  %s%n",
                    round, TOTAL_ROUNDS,
                    LocalDateTime.now().format(DT_FMT));
                System.out.println("-".repeat(65));

                // ── STEP 1: 전체 설비에서 센서 데이터 수집 → 큐에 적재 ──
                System.out.println("[수집] 설비 데이터 수집 후 큐(Buffer)에 적재 중...");
                for (Equipment eq : EQUIPMENTS) {
                    if (eq.status.equals("STOPPED") || eq.status.equals("MAINTENANCE")) {
                        System.out.printf("  %-8s  [%s] — 수집 건너뜀%n", eq.eqId, eq.status);
                        continue;
                    }
                    SensorData data = generateSensorData(eq);
                    bufferQueue.offer(data);
                    System.out.printf("  %-8s  %s%n", eq.eqId, data);
                }

                System.out.printf("%n[큐] 현재 대기 건수: %d 건%n%n", bufferQueue.size());

                // ── STEP 2: 큐에서 FIFO로 꺼내 DB INSERT + 이상 감지 ──
                System.out.println("[DB] 큐 → DB 적재 시작 (FIFO)...");

                String sensorSql = "INSERT INTO sensor_data "
                    + "(eq_id, temperature, vibration, humidity, rpm) VALUES (?, ?, ?, ?, ?)";
                String logSql = "INSERT INTO error_logs "
                    + "(eq_id, log_level, error_code, message, sensor_temp, sensor_vib) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
                String statusSql  = "UPDATE equipments SET status = ? WHERE eq_id = ?";
                String historySql = "INSERT INTO status_history (eq_id, prev_status, new_status) "
                    + "VALUES (?, ?, ?)";

                try (PreparedStatement psSensor  = conn.prepareStatement(sensorSql,
                                                        Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement psLog     = conn.prepareStatement(logSql);
                     PreparedStatement psStatus  = conn.prepareStatement(statusSql);
                     PreparedStatement psHistory = conn.prepareStatement(historySql)) {

                    while (!bufferQueue.isEmpty()) {
                        SensorData data = bufferQueue.poll(); // Dequeue (FIFO)

                        // sensor_data INSERT
                        psSensor.setString(1, data.eqId);
                        psSensor.setDouble(2, data.temperature);
                        psSensor.setDouble(3, data.vibration);
                        if (data.humidity >= 0) {
                            psSensor.setDouble(4, data.humidity);
                        } else {
                            psSensor.setNull(4, java.sql.Types.DECIMAL);
                        }
                        if (data.rpm >= 0) {
                            psSensor.setInt(5, data.rpm);
                        } else {
                            psSensor.setNull(5, java.sql.Types.INTEGER);
                        }
                        psSensor.executeUpdate();

                        ResultSet genKeys = psSensor.getGeneratedKeys();
                        long dataId = genKeys.next() ? genKeys.getLong(1) : -1;

                        System.out.printf("  [INSERT] sensor_data #%d  ← %s  (온도=%.1f°C, 진동=%.1f Hz)%n",
                            dataId, data.eqId, data.temperature, data.vibration);

                        // ── 이상 감지 시 처리 ──
                        if (data.isAnomaly) {
                            Equipment eq = findEquipment(data.eqId);

                            // 오류 코드 결정
                            String errorCode = resolveErrorCode(data);
                            String message   = buildErrorMessage(data, eq);

                            // error_logs INSERT
                            psLog.setString(1, data.eqId);
                            psLog.setString(2, data.anomalyLevel);
                            psLog.setString(3, errorCode);
                            psLog.setString(4, message);
                            psLog.setDouble(5, data.temperature);
                            psLog.setDouble(6, data.vibration);
                            psLog.executeUpdate();

                            System.out.printf("  [!] %s 이상 감지 → error_logs 기록: [%s] %s%n",
                                data.eqId, data.anomalyLevel, errorCode);

                            // 설비 상태를 ERROR로 변경 (CRITICAL인 경우만)
                            if (data.anomalyLevel.equals("CRITICAL") && eq != null
                                    && !eq.status.equals("ERROR")) {
                                String prevStatus = eq.status;
                                eq.status = "ERROR";

                                psStatus.setString(1, "ERROR");
                                psStatus.setString(2, data.eqId);
                                psStatus.executeUpdate();

                                psHistory.setString(1, data.eqId);
                                psHistory.setString(2, prevStatus);
                                psHistory.setString(3, "ERROR");
                                psHistory.executeUpdate();

                                System.out.printf("  [!!] %s 상태 변경: %s → ERROR (status_history 기록)%n",
                                    data.eqId, prevStatus);
                            }
                        }

                        Thread.sleep(200); // 처리 간 가시성 확보
                    }
                }

                // ── STEP 3: ERROR 상태 장비 자동 복구 시뮬레이션 (30% 확률) ──
                autoRecovery(conn);

                System.out.println("\n[시스템] Round " + round + " 완료 — 다음 수집까지 대기 중...");
                System.out.println("=".repeat(65));

                if (round < TOTAL_ROUNDS) {
                    Thread.sleep(COLLECT_INTERVAL_MS);
                }
            }

            System.out.println("\n[시스템] 전체 " + TOTAL_ROUNDS + " 라운드 수집 완료. 모든 데이터가 DB에 동기화되었습니다.");
            printSummary(conn);

        } catch (Exception e) {
            System.out.println("\n[오류 발생] DB 연결 또는 쿼리 실행 실패!");
            e.printStackTrace();
        }
    }

    // ============================================================
    // 센서 데이터 랜덤 생성
    // ============================================================
    private static SensorData generateSensorData(Equipment eq) {
        double temperature, vibration;
        double humidity = -1;
        int    rpm      = -1;

        boolean makeAnomaly   = RAND.nextDouble() < ANOMALY_CHANCE;
        boolean makeCritical  = makeAnomaly && (RAND.nextDouble() < CRITICAL_CHANCE);

        if (makeCritical) {
            // CRITICAL: 임계치를 크게 초과
            temperature = randRange(eq.baseTempMax + 15, eq.baseTempMax + 30);
            vibration   = randRange(eq.baseVibMax  + 20, eq.baseVibMax  + 35);
        } else if (makeAnomaly) {
            // WARN: 임계치를 약간 초과
            temperature = RAND.nextBoolean()
                ? randRange(80, 89)
                : randRange(eq.baseTempMin, eq.baseTempMax);
            vibration   = RAND.nextBoolean()
                ? randRange(40, 55)
                : randRange(eq.baseVibMin, eq.baseVibMax);
        } else {
            // NORMAL
            temperature = randRange(eq.baseTempMin, eq.baseTempMax);
            vibration   = randRange(eq.baseVibMin,  eq.baseVibMax);
        }

        if (eq.hasHumidity) {
            humidity = randRange(40, 75);
        }
        if (eq.baseRpmMin > 0) {
            rpm = (int) randRange(eq.baseRpmMin, eq.baseRpmMax);
        }

        return new SensorData(eq.eqId, round2(temperature), round2(vibration), round2(humidity), rpm);
    }

    // ============================================================
    // ERROR 설비 자동 복구 (점검 완료 시뮬레이션)
    // ============================================================
    private static void autoRecovery(Connection conn) throws Exception {
        for (Equipment eq : EQUIPMENTS) {
            if (!eq.status.equals("ERROR")) continue;
            if (RAND.nextDouble() < 0.30) { // 30% 확률로 복구
                String prevStatus = eq.status;
                eq.status = "RUNNING";

                String sql = "UPDATE equipments SET status = 'RUNNING' WHERE eq_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, eq.eqId);
                    ps.executeUpdate();
                }
                String hisSql = "INSERT INTO status_history (eq_id, prev_status, new_status) "
                              + "VALUES (?, ?, 'RUNNING')";
                try (PreparedStatement ps = conn.prepareStatement(hisSql)) {
                    ps.setString(1, eq.eqId);
                    ps.setString(2, prevStatus);
                    ps.executeUpdate();
                }
                // 미해결 오류 로그 해결 처리
                String resolveSql = "UPDATE error_logs SET resolved = 1 "
                                  + "WHERE eq_id = ? AND resolved = 0";
                try (PreparedStatement ps = conn.prepareStatement(resolveSql)) {
                    ps.setString(1, eq.eqId);
                    ps.executeUpdate();
                }
                System.out.printf("  [복구] %s 자동 복구 완료 → RUNNING (미해결 로그 해결 처리)%n", eq.eqId);
            }
        }
    }

    // ============================================================
    // 오류 코드 / 메시지 생성
    // ============================================================
    private static String resolveErrorCode(SensorData data) {
        boolean tempOver = data.temperature >= 80;
        boolean vibOver  = data.vibration   >= 40;
        if (tempOver && vibOver)  return "MULTI_ERR";
        if (tempOver)             return "OVERHEAT";
        if (vibOver)              return "VIB_SPIKE";
        return "UNKNOWN";
    }

    private static String buildErrorMessage(SensorData data, Equipment eq) {
        String eqName = (eq != null) ? eq.eqName : data.eqId;
        boolean tempOver = data.temperature >= 80;
        boolean vibOver  = data.vibration   >= 40;
        if (tempOver && vibOver)
            return String.format("%s 온도(%.1f°C)와 진동(%.1f Hz) 동시 임계 초과 — 즉시 가동 중지 필요",
                eqName, data.temperature, data.vibration);
        if (tempOver)
            return String.format("%s 온도 %.1f°C 초과 과열 감지 — 냉각 시스템 점검",
                eqName, data.temperature);
        return String.format("%s 진동 %.1f Hz 급증 — 베어링/회전체 마모 의심",
            eqName, data.vibration);
    }

    // ============================================================
    // 최종 요약 출력 (간단한 집계 조회)
    // ============================================================
    private static void printSummary(Connection conn) throws Exception {
        System.out.println("\n[요약] 설비 현황 ----------------------------");
        String sql = "SELECT eq_id, eq_name, status, "
                   + "(SELECT COUNT(*) FROM error_logs el WHERE el.eq_id = e.eq_id AND resolved=0) AS unresolved "
                   + "FROM equipments e ORDER BY eq_id";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            System.out.printf("  %-8s  %-22s  %-12s  %s%n",
                "장비ID", "장비명", "상태", "미해결 오류");
            System.out.println("  " + "-".repeat(58));
            while (rs.next()) {
                System.out.printf("  %-8s  %-22s  %-12s  %d 건%n",
                    rs.getString("eq_id"),
                    rs.getString("eq_name"),
                    rs.getString("status"),
                    rs.getInt("unresolved"));
            }
        }
        System.out.println("\n[요약] error_logs 전체 건수 ----------------");
        String cntSql = "SELECT log_level, COUNT(*) AS cnt FROM error_logs GROUP BY log_level";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(cntSql)) {
            while (rs.next()) {
                System.out.printf("  [%s]  %d 건%n",
                    rs.getString("log_level"), rs.getInt("cnt"));
            }
        }
    }

    // ============================================================
    // 유틸
    // ============================================================
    private static Equipment findEquipment(String eqId) {
        return EQUIPMENTS.stream()
            .filter(e -> e.eqId.equals(eqId))
            .findFirst().orElse(null);
    }

    private static double randRange(double min, double max) {
        return min + RAND.nextDouble() * (max - min);
    }

    private static double round2(double v) {
        if (v < 0) return v;
        return Math.round(v * 100.0) / 100.0;
    }

    private static void printBanner() {
        System.out.println("=".repeat(65));
        System.out.println("  스마트 팩토리 데이터 수집기  v2.0");
        System.out.println("  장비 6대 / 랜덤 상태 변화 / 자동 오류 로그");
        System.out.println("=".repeat(65));
        System.out.printf("  등록 장비: %d 대%n", EQUIPMENTS.size());
        System.out.printf("  수집 라운드: %d 회 / 주기: %d ms%n", TOTAL_ROUNDS, COLLECT_INTERVAL_MS);
        System.out.printf("  이상 발생 확률: %.0f%%  (CRITICAL: %.0f%%)%n",
            ANOMALY_CHANCE * 100, CRITICAL_CHANCE * 100);
        System.out.println("=".repeat(65));
    }
}
