import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

// 수집 흐름 오케스트레이션
// DB 연결 재시도 / 설비별 수집 오류 격리 / 구조화 로그 출력
public class FactoryCollector {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final List<Equipment> EQUIPMENTS = Arrays.asList(
        new Equipment("EQ-001", "1호 프레스 가공기",   "프레스",    "Line-1", 40, 65, 10, 22, 1100, 1300, false),
        new Equipment("EQ-002", "2호 플라스틱 사출기", "사출기",    "Line-1", 45, 70, 12, 25,  800,  950, true),
        new Equipment("EQ-003", "3호 CNC 선반",        "CNC",       "Line-2", 50, 68, 14, 28, 2300, 2500, false),
        new Equipment("EQ-004", "4호 용접 로봇",       "용접로봇",  "Line-2", 60, 78, 16, 32,   -1,   -1, false),
        new Equipment("EQ-005", "5호 컨베이어 벨트",   "컨베이어",  "Line-3", 30, 50,  6, 16,  550,  650, false),
        new Equipment("EQ-006", "6호 레이저 절단기",   "레이저절단","Line-3", 55, 75, 18, 35,   -1,   -1, false)
    );

    public static void main(String[] args) {
        AppConfig cfg = AppConfig.getInstance();
        printBanner(cfg);

        SensorDataGenerator generator = new SensorDataGenerator(new Random());
        AnomalyDetector     detector  = new AnomalyDetector();
        Queue<SensorData>   queue     = new LinkedList<>();

        // DB 연결 — 재시도(지수 백오프) 포함
        Connection conn = connectWithRetry(cfg);
        if (conn == null) {
            AppLogger.error(AppLogger.TAG_SYSTEM,
                "DB 연결 최종 실패 — 수집기를 종료합니다.");
            System.exit(1);
        }

        // API 서버 — 조회 전용 별도 DB 연결로 기동 (port=0 이면 비활성)
        ApiServer apiServer = null;
        if (cfg.apiPort() > 0) {
            Connection readConn = connectWithRetry(cfg);
            if (readConn != null) {
                try {
                    apiServer = new ApiServer(cfg.apiPort(), readConn);
                    apiServer.start();
                } catch (Exception e) {
                    AppLogger.error(AppLogger.TAG_SYSTEM, "API 서버 기동 실패 — 수집은 계속 진행", e);
                }
            }
        }

        try {
            DatabaseRepository repo     = new DatabaseRepository(conn, detector);
            DataArchiver       archiver = new DataArchiver(conn);
            int totalRounds = cfg.collectRounds();

            // 기동 시 아카이빙 1회 실행
            try { archiver.archive(); }
            catch (Exception e) {
                AppLogger.error(AppLogger.TAG_SYSTEM, "아카이빙 실패 — 수집은 계속 진행", e);
            }

            AppLogger.info(AppLogger.TAG_SYSTEM,
                "수집기 시작 — 라운드: " + (totalRounds == 0 ? "∞" : totalRounds));

            for (int round = 1; totalRounds == 0 || round <= totalRounds; round++) {
                AppLogger.info(AppLogger.TAG_SYSTEM,
                    String.format("===== Round %d / %s  [%s] =====",
                        round, totalRounds == 0 ? "∞" : totalRounds,
                        LocalDateTime.now().format(DT_FMT)));

                // STEP 1: 설비 → 큐 (설비별 오류는 격리)
                int collected = collectToQueue(generator, queue);
                AppLogger.info(AppLogger.TAG_QUEUE,
                    String.format("수집 완료: %d건 적재 (총 %d건 대기)", collected, queue.size()));

                // STEP 2: 큐 → DB (쿼리 재시도 포함)
                int flushed = flushQueue(queue, repo);
                AppLogger.info(AppLogger.TAG_DB,
                    String.format("DB 적재 완료: %d건 / 실패: %d건", flushed, collected - flushed));

                // STEP 3: 자동 복구
                repo.autoRecovery(EQUIPMENTS);

                AppLogger.info(AppLogger.TAG_SYSTEM,
                    "Round " + round + " 완료\n");

                if (totalRounds == 0 || round < totalRounds) {
                    Thread.sleep(cfg.collectIntervalMs());
                }
            }

            AppLogger.info(AppLogger.TAG_SYSTEM,
                "전체 " + totalRounds + " 라운드 수집 완료");
            repo.printSummary();

        } catch (Exception e) {
            AppLogger.error(AppLogger.TAG_SYSTEM, "예상치 못한 오류 발생", e);
            e.printStackTrace();
        } finally {
            if (apiServer != null) apiServer.stop();
            try { conn.close(); } catch (Exception ignored) {}
            AppLogger.info(AppLogger.TAG_SYSTEM, "DB 연결 종료");
        }
    }

    // ── DB 연결 재시도 ────────────────────────────────────────

    private static Connection connectWithRetry(AppConfig cfg) {
        int  maxRetry = cfg.dbRetryMax();
        long delay    = cfg.dbRetryDelayMs();

        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            AppLogger.info(AppLogger.TAG_DB,
                String.format("연결 시도 %d/%d — %s", attempt, maxRetry, cfg.dbUrl()));
            try {
                Connection conn = DriverManager.getConnection(
                    cfg.dbUrl(), cfg.dbUser(), cfg.dbPass());
                AppLogger.info(AppLogger.TAG_DB, "연결 성공 (시도 " + attempt + "/" + maxRetry + ")");
                return conn;
            } catch (Exception e) {
                AppLogger.error(AppLogger.TAG_DB,
                    String.format("연결 실패 (시도 %d/%d)", attempt, maxRetry), e);
                if (attempt < maxRetry) {
                    long wait = delay * (1L << (attempt - 1));
                    AppLogger.info(AppLogger.TAG_RETRY,
                        String.format("%.1f초 후 재시도...", wait / 1000.0));
                    try { Thread.sleep(wait); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // ── 수집: 설비별 오류 격리 ───────────────────────────────
    // 한 설비의 수집 실패가 다른 설비 수집을 중단시키지 않습니다.

    private static int collectToQueue(SensorDataGenerator generator, Queue<SensorData> queue) {
        int count = 0;
        for (Equipment eq : EQUIPMENTS) {
            if (!eq.isActive()) {
                AppLogger.info(AppLogger.TAG_COLLECT,
                    eq.eqId + " [" + eq.status + "] — 수집 건너뜀");
                continue;
            }
            try {
                SensorData data = generator.generate(eq);
                queue.offer(data);
                AppLogger.sensor(data);
                count++;
            } catch (Exception e) {
                // 단일 설비 수집 실패 — 로그만 기록하고 나머지 설비 계속 수집
                AppLogger.error(AppLogger.TAG_COLLECT,
                    eq.eqId + " 센서 데이터 수집 실패 — 이번 라운드 건너뜀", e);
            }
        }
        return count;
    }

    // ── 적재: 큐 → DB (항목별 실패는 건너뜀, 성공 건수 반환) ──

    private static int flushQueue(Queue<SensorData> queue, DatabaseRepository repo) throws Exception {
        int success = 0;
        try (PreparedStatement ps = repo.prepareSensorInsert()) {
            while (!queue.isEmpty()) {
                SensorData data = queue.poll();
                try {
                    long dataId = repo.insertSensorData(ps, data);
                    AppLogger.dbInsert(data.eqId, dataId, data.anomalyLevel);
                    success++;

                    if (data.isAnomaly) {
                        Equipment eq = findEquipment(data.eqId);
                        repo.handleAnomaly(data, eq);
                    }
                } catch (Exception e) {
                    // 재시도 후에도 실패한 항목 — 누락 기록 후 다음 항목 진행
                    AppLogger.error(AppLogger.TAG_DB,
                        String.format("%s INSERT 최종 실패 — 해당 데이터 누락 처리", data.eqId), e);
                }
                Thread.sleep(200);
            }
        }
        return success;
    }

    private static Equipment findEquipment(String eqId) {
        return EQUIPMENTS.stream().filter(e -> e.eqId.equals(eqId)).findFirst().orElse(null);
    }

    private static void printBanner(AppConfig cfg) {
        System.out.println("=".repeat(65));
        System.out.println("  스마트 팩토리 데이터 수집기  v2.2");
        System.out.println("=".repeat(65));
        System.out.printf("  등록 장비: %d 대%n", EQUIPMENTS.size());
        System.out.printf("  수집 라운드: %s 회 / 주기: %d ms%n",
            cfg.collectRounds() == 0 ? "∞" : cfg.collectRounds(),
            cfg.collectIntervalMs());
        System.out.printf("  DB 재시도: max=%d / delay=%dms%n",
            cfg.dbRetryMax(), cfg.dbRetryDelayMs());
        System.out.println("=".repeat(65));
    }
}
