// 이상 감지 결과에 따른 오류 코드·메시지 생성 — 임계값은 AppConfig에서 읽음
public class AnomalyDetector {

    public String resolveErrorCode(SensorData data) {
        AppConfig cfg = AppConfig.getInstance();
        boolean tempOver = data.temperature >= cfg.tempWarn();
        boolean vibOver  = data.vibration   >= cfg.vibWarn();
        if (tempOver && vibOver) return "MULTI_ERR";
        if (tempOver)            return "OVERHEAT";
        if (vibOver)             return "VIB_SPIKE";
        return "UNKNOWN";
    }

    public String buildErrorMessage(SensorData data, Equipment eq) {
        AppConfig cfg    = AppConfig.getInstance();
        String    name   = (eq != null) ? eq.eqName : data.eqId;
        boolean tempOver = data.temperature >= cfg.tempWarn();
        boolean vibOver  = data.vibration   >= cfg.vibWarn();

        if (tempOver && vibOver)
            return String.format("%s 온도(%.1f°C)와 진동(%.1f Hz) 동시 임계 초과 — 즉시 가동 중지 필요",
                name, data.temperature, data.vibration);
        if (tempOver)
            return String.format("%s 온도 %.1f°C 초과 과열 감지 — 냉각 시스템 점검",
                name, data.temperature);
        return String.format("%s 진동 %.1f Hz 급증 — 베어링/회전체 마모 의심",
            name, data.vibration);
    }
}
