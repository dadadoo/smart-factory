import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;

/**
 * JDK 내장 HttpServer 기반 REST API 서버.
 * 수집기 스레드와 별도 스레드풀에서 동작하며 DB 조회 전용 연결을 사용합니다.
 *
 * 엔드포인트:
 *   GET /api/health    — 헬스체크
 *   GET /api/dashboard — 설비 현황 + 미해결 로그 + 지표
 *   GET /api/chart     — 설비별 온도 이력 (최근 20건)
 */
public class ApiServer {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final HttpServer server;
    private final Connection  readConn;

    public ApiServer(int port, Connection readConn) throws IOException {
        this.readConn = readConn;
        this.server   = HttpServer.create(new InetSocketAddress(port), 16);

        server.createContext("/api/health",    ex -> handle(ex, this::healthJson));
        server.createContext("/api/dashboard", ex -> handle(ex, this::dashboardJson));
        server.createContext("/api/chart",     ex -> handle(ex, this::chartJson));

        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
        AppLogger.info(AppLogger.TAG_SYSTEM,
            "API 서버 시작 — http://localhost:" + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        AppLogger.info(AppLogger.TAG_SYSTEM, "API 서버 종료");
        try { readConn.close(); } catch (Exception ignored) {}
    }

    // ── 공통 HTTP 처리 ────────────────────────────────────────

    @FunctionalInterface
    private interface JsonSupplier { String get() throws Exception; }

    private void handle(HttpExchange ex, JsonSupplier supplier) throws IOException {
        // CORS — 브라우저 file:// 오리진 허용
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        try {
            String body = supplier.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            AppLogger.error(AppLogger.TAG_SYSTEM, "API 오류: " + ex.getRequestURI(), e);
            String err = JsonBuilder.obj().str("error", e.getMessage()).build();
            byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // ── /api/health ───────────────────────────────────────────

    private String healthJson() {
        return JsonBuilder.obj()
            .str("status", "ok")
            .str("timestamp", LocalTime.now().format(TIME_FMT))
            .build();
    }

    // ── /api/dashboard ────────────────────────────────────────

    private String dashboardJson() throws SQLException {
        JsonBuilder eqArr  = JsonBuilder.array();
        JsonBuilder logArr = JsonBuilder.array();

        // 설비 현황 (v_dashboard 뷰)
        String eqSql =
            "SELECT e.eq_id, e.eq_name, e.eq_type, e.location, e.status, " +
            "  ls.temperature AS last_temp, ls.vibration AS last_vib, " +
            "  DATE_FORMAT(ls.recorded_at,'%H:%i:%S') AS last_updated, " +
            "  IFNULL(oc.cnt, 0) AS unresolved_errors " +
            "FROM equipments e " +
            "LEFT JOIN v_latest_sensor ls ON ls.eq_id = e.eq_id " +
            "LEFT JOIN (SELECT eq_id, COUNT(*) cnt FROM error_logs WHERE resolved=0 GROUP BY eq_id) oc ON oc.eq_id = e.eq_id " +
            "ORDER BY e.eq_id";

        try (Statement st = readConn.createStatement();
             ResultSet rs = st.executeQuery(eqSql)) {
            while (rs.next()) {
                double temp = rs.getDouble("last_temp");
                double vib  = rs.getDouble("last_vib");
                String level = SensorData.detectLevel(temp, vib);

                eqArr.add(JsonBuilder.obj()
                    .str("eqId",            rs.getString("eq_id"))
                    .str("eqName",          rs.getString("eq_name"))
                    .str("eqType",          rs.getString("eq_type"))
                    .str("location",        rs.getString("location"))
                    .str("status",          rs.getString("status"))
                    .num("lastTemp",        temp)
                    .num("lastVib",         vib)
                    .str("lastUpdated",     rs.getString("last_updated"))
                    .num("unresolvedErrors",rs.getInt("unresolved_errors"))
                    .str("anomalyLevel",    level)
                    .build());
            }
        }

        // 최근 오류 로그 40건 (v_open_logs 뷰에서 우선, 해결된 것도 포함)
        String logSql =
            "SELECT el.log_id, el.eq_id, e.eq_name, el.log_level, el.error_code, " +
            "  el.message, el.sensor_temp, el.sensor_vib, el.resolved, el.alert_sent, " +
            "  DATE_FORMAT(el.created_at,'%H:%i:%S') AS created_time " +
            "FROM error_logs el JOIN equipments e ON e.eq_id = el.eq_id " +
            "ORDER BY el.log_id DESC LIMIT 40";

        try (Statement st = readConn.createStatement();
             ResultSet rs = st.executeQuery(logSql)) {
            while (rs.next()) {
                logArr.add(JsonBuilder.obj()
                    .num("logId",     rs.getInt("log_id"))
                    .str("eqId",      rs.getString("eq_id"))
                    .str("eqName",    rs.getString("eq_name"))
                    .str("level",     rs.getString("log_level"))
                    .str("code",      rs.getString("error_code"))
                    .str("message",   rs.getString("message"))
                    .num("temp",      rs.getDouble("sensor_temp"))
                    .num("vib",       rs.getDouble("sensor_vib"))
                    .bool("resolved", rs.getInt("resolved") == 1)
                    .bool("alertSent",rs.getInt("alert_sent") == 1)
                    .str("time",      rs.getString("created_time"))
                    .build());
            }
        }

        // 집계 지표
        String metSql =
            "SELECT " +
            "  (SELECT COUNT(*) FROM sensor_data)          AS total_rows, " +
            "  (SELECT COUNT(*) FROM error_logs WHERE log_level='WARN'     AND resolved=0) AS warn_open, " +
            "  (SELECT COUNT(*) FROM error_logs WHERE log_level='CRITICAL' AND resolved=0) AS crit_open, " +
            "  (SELECT COUNT(*) FROM equipments WHERE status='RUNNING')    AS active_count, " +
            "  (SELECT COUNT(*) FROM equipments WHERE status='ERROR')      AS error_count";

        String metricsJson;
        try (Statement st = readConn.createStatement();
             ResultSet rs = st.executeQuery(metSql)) {
            rs.next();
            metricsJson = JsonBuilder.obj()
                .num("totalRows",    rs.getLong("total_rows"))
                .num("warnOpen",     rs.getInt("warn_open"))
                .num("critOpen",     rs.getInt("crit_open"))
                .num("activeCount",  rs.getInt("active_count"))
                .num("errorCount",   rs.getInt("error_count"))
                .build();
        }

        return JsonBuilder.obj()
            .str("timestamp",  LocalTime.now().format(TIME_FMT))
            .raw("metrics",    metricsJson)
            .raw("equipments", eqArr.build())
            .raw("logs",       logArr.build())
            .build();
    }

    // ── /api/chart ────────────────────────────────────────────
    // 설비별 최근 20건 온도 이력 → 차트 데이터

    private String chartJson() throws SQLException {
        JsonBuilder seriesObj = JsonBuilder.obj();
        JsonBuilder labelArr  = JsonBuilder.array();
        boolean labelsSet = false;

        String[] eqIds = {"EQ-001","EQ-002","EQ-003","EQ-004","EQ-005","EQ-006"};
        String histSql =
            "SELECT temperature, DATE_FORMAT(recorded_at,'%H:%i:%S') AS t " +
            "FROM sensor_data WHERE eq_id = ? ORDER BY data_id DESC LIMIT 20";

        try (PreparedStatement ps = readConn.prepareStatement(histSql)) {
            for (String eqId : eqIds) {
                ps.setString(1, eqId);
                // 역순으로 가져왔으므로 배열에 역순으로 담아 시간 오름차순으로 만듦
                java.util.Deque<Double> temps  = new java.util.ArrayDeque<>();
                java.util.Deque<String> labels = new java.util.ArrayDeque<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        temps.addFirst(rs.getDouble("temperature"));
                        labels.addFirst(rs.getString("t"));
                    }
                }

                JsonBuilder tempArr = JsonBuilder.array();
                temps.forEach(t -> tempArr.add(String.valueOf(t)));
                seriesObj.raw(eqId, tempArr.build());

                if (!labelsSet) {
                    labels.forEach(l -> labelArr.add("\"" + l + "\""));
                    labelsSet = true;
                }
            }
        }

        return JsonBuilder.obj()
            .str("timestamp", LocalTime.now().format(TIME_FMT))
            .raw("labels",    labelArr.build())
            .raw("series",    seriesObj.build())
            .build();
    }
}
