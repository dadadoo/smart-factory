import java.sql.*;

/**
 * 설비별 KPI (OEE, MTBF, MTTR) 를 DB에서 계산합니다.
 *
 * OEE = 가동률(Availability) × 성능률(Performance) × 품질률(Quality)
 *   - 가동률  : RUNNING 상태 유지 비율   (v_kpi_availability)
 *   - 성능률  : 실수집 / 기대수집 비율   (sensor_data 밀도)
 *   - 품질률  : NORMAL 수집 비율         (v_kpi_quality)
 *
 * MTBF : 평균 고장 간격 (분)  — v_kpi_mtbf
 * MTTR : 평균 복구 시간 (분)  — v_kpi_mttr
 */
public class KpiCalculator {

    private final Connection conn;

    public KpiCalculator(Connection conn) {
        this.conn = conn;
    }

    /**
     * 설비별 KPI 결과를 JSON 배열 문자열로 반환합니다.
     * API 서버가 그대로 응답에 사용합니다.
     */
    public String calcAllJson() throws SQLException {
        JsonBuilder arr = JsonBuilder.array();

        String sql =
            "SELECT eq_id, eq_name, " +
            "       IFNULL(availability_pct, 0) AS avail, " +
            "       IFNULL(performance_pct,  0) AS perf, " +
            "       IFNULL(quality_pct,      0) AS qual, " +
            "       IFNULL(oee_pct,          0) AS oee, " +
            "       mtbf_min, mttr_min, " +
            "       IFNULL(failure_count,    0) AS failures " +
            "FROM v_kpi_oee ORDER BY eq_id";

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                Double mtbf = rs.getObject("mtbf_min") != null
                    ? rs.getDouble("mtbf_min") : null;
                Double mttr = rs.getObject("mttr_min") != null
                    ? rs.getDouble("mttr_min") : null;

                JsonBuilder obj = JsonBuilder.obj()
                    .str("eqId",         rs.getString("eq_id"))
                    .str("eqName",       rs.getString("eq_name"))
                    .num("availability", rs.getDouble("avail"))
                    .num("performance",  rs.getDouble("perf"))
                    .num("quality",      rs.getDouble("qual"))
                    .num("oee",          rs.getDouble("oee"))
                    .num("failures",     rs.getInt("failures"));

                if (mtbf != null) obj.num("mtbfMin", mtbf);
                else              obj.raw("mtbfMin", "null");
                if (mttr != null) obj.num("mttrMin", mttr);
                else              obj.raw("mttrMin", "null");

                arr.add(obj.build());
            }
        }

        // 공장 전체 평균 OEE 요약도 함께 반환
        String summSql =
            "SELECT ROUND(AVG(oee_pct),1)          AS factory_oee, " +
            "       ROUND(AVG(availability_pct),1)  AS factory_avail, " +
            "       ROUND(AVG(performance_pct),1)   AS factory_perf, " +
            "       ROUND(AVG(quality_pct),1)        AS factory_qual, " +
            "       ROUND(AVG(mtbf_min),1)           AS factory_mtbf, " +
            "       ROUND(AVG(mttr_min),1)           AS factory_mttr " +
            "FROM v_kpi_oee";

        String summaryJson;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(summSql)) {
            rs.next();
            summaryJson = JsonBuilder.obj()
                .num("factoryOee",   nullableDouble(rs, "factory_oee"))
                .num("availability", nullableDouble(rs, "factory_avail"))
                .num("performance",  nullableDouble(rs, "factory_perf"))
                .num("quality",      nullableDouble(rs, "factory_qual"))
                .num("mtbfMin",      nullableDouble(rs, "factory_mtbf"))
                .num("mttrMin",      nullableDouble(rs, "factory_mttr"))
                .build();
        }

        return JsonBuilder.obj()
            .raw("summary",    summaryJson)
            .raw("equipments", arr.build())
            .build();
    }

    private static double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? 0.0 : v;
    }
}
