import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 구조화 콘솔 로거
 *
 * 포맷: [HH:mm:ss.SSS] [LEVEL] [TAG     ] 메시지
 * 예시: [14:23:01.452] [WARN ] [ANOMALY ] EQ-002 온도 82.4°C → WARN
 *
 * 태그 상수는 이 클래스에서 관리해 오타를 방지합니다.
 */
public class AppLogger {

    // ── 태그 상수 ─────────────────────────────────────────────
    public static final String TAG_SYSTEM  = "SYSTEM  ";
    public static final String TAG_CONFIG  = "CONFIG  ";
    public static final String TAG_DB      = "DB      ";
    public static final String TAG_COLLECT = "COLLECT ";
    public static final String TAG_QUEUE   = "QUEUE   ";
    public static final String TAG_ANOMALY = "ANOMALY ";
    public static final String TAG_RECOVER = "RECOVER ";
    public static final String TAG_RETRY   = "RETRY   ";
    public static final String TAG_SUMMARY = "SUMMARY ";

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ── 레벨별 출력 메서드 ────────────────────────────────────

    public static void info(String tag, String msg) {
        print("INFO ", tag, msg);
    }

    public static void warn(String tag, String msg) {
        print("WARN ", tag, msg);
    }

    public static void error(String tag, String msg) {
        print("ERROR", tag, msg);
    }

    public static void error(String tag, String msg, Throwable cause) {
        print("ERROR", tag, msg + " — " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    public static void critical(String tag, String msg) {
        print("CRIT ", tag, msg);
    }

    // ── 자주 쓰는 복합 메서드 ────────────────────────────────

    /** 센서 수집 결과 한 줄 출력 */
    public static void sensor(SensorData d) {
        String msg = String.format("%-8s 온도=%5.1f°C  진동=%5.1f Hz  RPM=%-5s → %s",
            d.eqId, d.temperature, d.vibration,
            d.rpm == -1 ? "N/A" : d.rpm,
            d.anomalyLevel);

        switch (d.anomalyLevel) {
            case "CRITICAL" -> critical(TAG_COLLECT, msg);
            case "WARN"     -> warn(TAG_COLLECT, msg);
            default         -> info(TAG_COLLECT, msg);
        }
    }

    /** DB INSERT 성공 */
    public static void dbInsert(String eqId, long dataId, String level) {
        String msg = String.format("sensor_data #%-5d ← %s  [%s]", dataId, eqId, level);
        if ("NORMAL".equals(level)) info(TAG_DB, msg);
        else                         warn(TAG_DB, msg);
    }

    /** 재시도 시도 */
    public static void retryAttempt(String tag, int attempt, int max, String op, Throwable cause) {
        warn(TAG_RETRY, String.format("[%s] %s 실패 (시도 %d/%d) — %s: %s",
            tag.trim(), op, attempt, max,
            cause.getClass().getSimpleName(), cause.getMessage()));
    }

    /** 재시도 성공 */
    public static void retrySuccess(String tag, int attempt, String op) {
        info(TAG_RETRY, String.format("[%s] %s 재시도 성공 (시도 %d)", tag.trim(), op, attempt));
    }

    /** 재시도 최종 실패 */
    public static void retryExhausted(String tag, int max, String op) {
        error(TAG_RETRY, String.format("[%s] %s — %d회 재시도 후 최종 실패", tag.trim(), op, max));
    }

    // ── 내부 출력 ─────────────────────────────────────────────

    private static void print(String level, String tag, String msg) {
        System.out.printf("[%s] [%s] [%s] %s%n",
            LocalDateTime.now().format(FMT), level, tag, msg);
    }
}
