// 설비 정보 + 정상 범위 기준값 — 런타임 상태(status) 포함
public class Equipment {
    public final String eqId;
    public final String eqName;
    public final String eqType;
    public final String location;

    public final double baseTempMin, baseTempMax;
    public final double baseVibMin,  baseVibMax;
    public final int    baseRpmMin,  baseRpmMax;
    public final boolean hasHumidity;

    public String status; // "RUNNING" / "ERROR" / "STOPPED" / "MAINTENANCE"

    public Equipment(String eqId, String eqName, String eqType, String location,
                     double baseTempMin, double baseTempMax,
                     double baseVibMin,  double baseVibMax,
                     int baseRpmMin, int baseRpmMax, boolean hasHumidity) {
        this.eqId        = eqId;
        this.eqName      = eqName;
        this.eqType      = eqType;
        this.location    = location;
        this.baseTempMin = baseTempMin;
        this.baseTempMax = baseTempMax;
        this.baseVibMin  = baseVibMin;
        this.baseVibMax  = baseVibMax;
        this.baseRpmMin  = baseRpmMin;
        this.baseRpmMax  = baseRpmMax;
        this.hasHumidity = hasHumidity;
        this.status      = "RUNNING";
    }

    public boolean isActive() {
        return !status.equals("STOPPED") && !status.equals("MAINTENANCE");
    }
}
