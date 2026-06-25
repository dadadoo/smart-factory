// 센서 수집 결과 값 객체 — 이상 수준 판정은 AppConfig 임계값 기준
public class SensorData {
    public final String  eqId;
    public final double  temperature;
    public final double  vibration;
    public final double  humidity;   // -1 이면 미측정
    public final int     rpm;        // -1 이면 미측정
    public final boolean isAnomaly;
    public final String  anomalyLevel; // "NORMAL" / "WARN" / "CRITICAL"

    public SensorData(String eqId, double temperature, double vibration, double humidity, int rpm) {
        this.eqId        = eqId;
        this.temperature = temperature;
        this.vibration   = vibration;
        this.humidity    = humidity;
        this.rpm         = rpm;
        this.anomalyLevel = detectLevel(temperature, vibration);
        this.isAnomaly   = !this.anomalyLevel.equals("NORMAL");
    }

    // 임계값을 AppConfig에서 읽어 SQL CASE WHEN 기준과 동기화
    public static String detectLevel(double temp, double vib) {
        AppConfig cfg = AppConfig.getInstance();
        if (temp >= cfg.tempCritical() || vib >= cfg.vibCritical()) return "CRITICAL";
        if (temp >= cfg.tempWarn()     || vib >= cfg.vibWarn())     return "WARN";
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
