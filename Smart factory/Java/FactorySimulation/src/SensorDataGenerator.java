import java.util.Random;

// 설비별 정상 범위 기반 센서 데이터 생성 — 이상 확률은 AppConfig에서 읽음
public class SensorDataGenerator {

    private final Random rand;

    public SensorDataGenerator(Random rand) {
        this.rand = rand;
    }

    public SensorData generate(Equipment eq) {
        AppConfig cfg = AppConfig.getInstance();

        boolean makeAnomaly  = rand.nextDouble() < cfg.anomalyChance();
        boolean makeCritical = makeAnomaly && rand.nextDouble() < cfg.criticalChance();

        double temperature, vibration;
        if (makeCritical) {
            temperature = range(eq.baseTempMax + 15, eq.baseTempMax + 30);
            vibration   = range(eq.baseVibMax  + 20, eq.baseVibMax  + 35);
        } else if (makeAnomaly) {
            temperature = rand.nextBoolean()
                ? range(cfg.tempWarn(), cfg.tempCritical() - 1)
                : range(eq.baseTempMin, eq.baseTempMax);
            vibration   = rand.nextBoolean()
                ? range(cfg.vibWarn(),  cfg.vibCritical()  - 1)
                : range(eq.baseVibMin,  eq.baseVibMax);
        } else {
            temperature = range(eq.baseTempMin, eq.baseTempMax);
            vibration   = range(eq.baseVibMin,  eq.baseVibMax);
        }

        double humidity = eq.hasHumidity ? round2(range(40, 75)) : -1;
        int    rpm      = eq.baseRpmMin > 0 ? (int) range(eq.baseRpmMin, eq.baseRpmMax) : -1;

        return new SensorData(eq.eqId, round2(temperature), round2(vibration), humidity, rpm);
    }

    private double range(double min, double max) {
        return min + rand.nextDouble() * (max - min);
    }

    private static double round2(double v) {
        return v < 0 ? v : Math.round(v * 100.0) / 100.0;
    }
}
