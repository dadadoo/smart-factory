import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 애플리케이션 설정 싱글턴
 *
 * 로딩 우선순위 (낮음 → 높음):
 *  1. config/application.properties           — 공통 기본값
 *  2. config/application-{APP_ENV}.properties — 환경별 오버레이
 *  3. 시스템 환경변수                           — 배포 시 민감 값 주입
 *
 * 환경 선택: APP_ENV 환경변수 (기본값: "local")
 */
public class AppConfig {

    private static final AppConfig INSTANCE = new AppConfig();
    private final Properties props = new Properties();

    private AppConfig() {
        load("config/application.properties");

        String env = env("APP_ENV", "local");
        load("config/application-" + env + ".properties");

        // 환경변수로 각 키 개별 오버라이드
        overrideFromEnv("DB_URL",                 "db.url");
        overrideFromEnv("DB_USER",                "db.user");
        overrideFromEnv("DB_PASS",                "db.pass");
        overrideFromEnv("COLLECT_INTERVAL_MS",    "collect.interval.ms");
        overrideFromEnv("COLLECT_ROUNDS",         "collect.rounds");
        overrideFromEnv("ANOMALY_CHANCE",         "anomaly.chance");
        overrideFromEnv("CRITICAL_CHANCE",        "critical.chance");
        overrideFromEnv("THRESHOLD_TEMP_WARN",    "threshold.temp.warn");
        overrideFromEnv("THRESHOLD_TEMP_CRITICAL","threshold.temp.critical");
        overrideFromEnv("THRESHOLD_VIB_WARN",     "threshold.vib.warn");
        overrideFromEnv("THRESHOLD_VIB_CRITICAL", "threshold.vib.critical");
        overrideFromEnv("API_PORT",               "api.port");
        overrideFromEnv("DB_RETRY_MAX",           "db.retry.max");
        overrideFromEnv("DB_RETRY_DELAY_MS",      "db.retry.delay.ms");
        overrideFromEnv("QUERY_RETRY_MAX",        "query.retry.max");
        overrideFromEnv("QUERY_RETRY_DELAY_MS",   "query.retry.delay.ms");

        printActiveConfig();
    }

    public static AppConfig getInstance() { return INSTANCE; }

    // ── DB ──────────────────────────────────────────────────
    public String dbUrl()  { return require("db.url");  }
    public String dbUser() { return require("db.user"); }
    public String dbPass() { return require("db.pass"); }

    // ── 수집 설정 ────────────────────────────────────────────
    public int collectIntervalMs() { return intVal("collect.interval.ms"); }
    public int collectRounds()     { return intVal("collect.rounds");      }

    // ── 이상 확률 ────────────────────────────────────────────
    public double anomalyChance()  { return doubleVal("anomaly.chance");  }
    public double criticalChance() { return doubleVal("critical.chance"); }

    // ── 이상 임계값 ──────────────────────────────────────────
    public double tempWarn()     { return doubleVal("threshold.temp.warn");     }
    public double tempCritical() { return doubleVal("threshold.temp.critical"); }
    public double vibWarn()      { return doubleVal("threshold.vib.warn");      }
    public double vibCritical()  { return doubleVal("threshold.vib.critical");  }

    // ── API 서버 ─────────────────────────────────────────────
    public int apiPort() { return intVal("api.port"); }

    // ── 재시도 설정 ──────────────────────────────────────────
    public int  dbRetryMax()        { return intVal("db.retry.max");         }
    public long dbRetryDelayMs()    { return longVal("db.retry.delay.ms");   }
    public int  queryRetryMax()     { return intVal("query.retry.max");      }
    public long queryRetryDelayMs() { return longVal("query.retry.delay.ms");}

    // ── 내부 유틸 ────────────────────────────────────────────

    private void load(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is != null) { props.load(is); return; }
        } catch (IOException ignored) {}

        try (FileInputStream fis = new FileInputStream(path)) {
            props.load(fis);
        } catch (IOException e) {
            System.out.printf("[Config] %s 없음 — 건너뜀%n", path);
        }
    }

    private void overrideFromEnv(String envKey, String propKey) {
        String val = System.getenv(envKey);
        if (val != null && !val.isBlank()) props.setProperty(propKey, val.trim());
    }

    private static String env(String key, String defaultVal) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v.trim() : defaultVal;
    }

    private String require(String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("필수 설정 누락: " + key);
        return v.trim();
    }

    private int    intVal(String key)  { return Integer.parseInt(require(key));  }
    private long   longVal(String key) { return Long.parseLong(require(key));    }
    private double doubleVal(String k) { return Double.parseDouble(require(k)); }

    private void printActiveConfig() {
        String env = env("APP_ENV", "local");
        AppLogger.info(AppLogger.TAG_CONFIG, "환경: " + env.toUpperCase());
        AppLogger.info(AppLogger.TAG_CONFIG, "DB: " + dbUrl() + "  (user=" + dbUser() + ")");
        AppLogger.info(AppLogger.TAG_CONFIG, String.format(
            "수집 주기: %dms  라운드: %s  이상률: %.0f%%  CRITICAL: %.0f%%",
            collectIntervalMs(),
            collectRounds() == 0 ? "∞" : collectRounds(),
            anomalyChance() * 100, criticalChance() * 100));
        AppLogger.info(AppLogger.TAG_CONFIG, String.format(
            "임계값 — 온도 WARN≥%.0f° CRIT≥%.0f°  진동 WARN≥%.0f CRIT≥%.0f",
            tempWarn(), tempCritical(), vibWarn(), vibCritical()));
        AppLogger.info(AppLogger.TAG_CONFIG, String.format(
            "재시도 — DB: max=%d delay=%dms  쿼리: max=%d delay=%dms",
            dbRetryMax(), dbRetryDelayMs(), queryRetryMax(), queryRetryDelayMs()));
    }
}
