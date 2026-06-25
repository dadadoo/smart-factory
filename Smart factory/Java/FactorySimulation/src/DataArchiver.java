import java.sql.*;

/**
 * 오래된 sensor_data 를 sensor_data_archive 로 이전 후 삭제합니다.
 *
 * 실행 시점:
 *   - FactoryCollector 기동 시 1회 (startup archiving)
 *   - 필요하면 라운드마다 호출 가능
 *
 * 보존 기간: AppConfig.archiveRetentionDays() (기본 30일)
 */
public class DataArchiver {

    private final Connection conn;

    public DataArchiver(Connection conn) {
        this.conn = conn;
    }

    /**
     * 보존 기간이 지난 sensor_data 를 아카이브 테이블로 이전하고
     * 이전된 행을 원본에서 삭제합니다.
     *
     * @return 이전된 행 수
     */
    public int archive() throws SQLException {
        int days = AppConfig.getInstance().archiveRetentionDays();

        // 1) 아카이브로 복사 (archived_at 은 DEFAULT CURRENT_TIMESTAMP)
        String insertSql =
            "INSERT IGNORE INTO sensor_data_archive " +
            "    (data_id, eq_id, temperature, vibration, humidity, rpm, recorded_at) " +
            "SELECT data_id, eq_id, temperature, vibration, humidity, rpm, recorded_at " +
            "FROM sensor_data " +
            "WHERE recorded_at < NOW() - INTERVAL ? DAY";

        int archived = 0;
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setInt(1, days);
            archived = ps.executeUpdate();
        }

        if (archived == 0) {
            AppLogger.info(AppLogger.TAG_SYSTEM,
                String.format("아카이빙: 보존 기간(%d일) 초과 데이터 없음", days));
            return 0;
        }

        // 2) 원본 삭제
        String deleteSql =
            "DELETE FROM sensor_data WHERE recorded_at < NOW() - INTERVAL ? DAY";
        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setInt(1, days);
            ps.executeUpdate();
        }

        AppLogger.info(AppLogger.TAG_SYSTEM,
            String.format("아카이빙 완료: %d건 이전 → sensor_data_archive (보존 기간: %d일)",
                archived, days));
        return archived;
    }

    /**
     * CSV 내보내기용: 최근 N일치 sensor_data 를 CSV 문자열로 반환합니다.
     */
    public String exportCsv(int days) throws SQLException {
        String sql =
            "SELECT sd.data_id, sd.eq_id, e.eq_name, " +
            "       sd.temperature, sd.vibration, " +
            "       IFNULL(sd.humidity, '') AS humidity, " +
            "       IFNULL(sd.rpm, '')      AS rpm, " +
            "       CASE WHEN sd.temperature >= 90 OR sd.vibration >= 60 THEN 'CRITICAL' " +
            "            WHEN sd.temperature >= 80 OR sd.vibration >= 40 THEN 'WARN' " +
            "            ELSE 'NORMAL' END AS anomaly_level, " +
            "       sd.recorded_at " +
            "FROM sensor_data sd " +
            "JOIN equipments e ON e.eq_id = sd.eq_id " +
            "WHERE sd.recorded_at >= NOW() - INTERVAL ? DAY " +
            "ORDER BY sd.data_id DESC";

        StringBuilder sb = new StringBuilder();
        sb.append("data_id,eq_id,eq_name,temperature,vibration,humidity,rpm,anomaly_level,recorded_at\n");

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, days);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getLong("data_id")).append(',')
                      .append(rs.getString("eq_id")).append(',')
                      .append('"').append(rs.getString("eq_name")).append('"').append(',')
                      .append(rs.getDouble("temperature")).append(',')
                      .append(rs.getDouble("vibration")).append(',')
                      .append(rs.getString("humidity")).append(',')
                      .append(rs.getString("rpm")).append(',')
                      .append(rs.getString("anomaly_level")).append(',')
                      .append(rs.getTimestamp("recorded_at"))
                      .append('\n');
                }
            }
        }
        return sb.toString();
    }
}
