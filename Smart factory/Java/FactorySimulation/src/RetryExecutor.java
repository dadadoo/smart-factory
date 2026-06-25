/**
 * 지수 백오프(Exponential Backoff) 재시도 실행기
 *
 * 사용 예:
 *   long id = RetryExecutor.run(
 *       () -> repo.insertSensorData(ps, data),
 *       "DB", "sensor INSERT", cfg.queryRetryMax(), cfg.queryRetryDelayMs()
 *   );
 */
public class RetryExecutor {

    /** 예외를 던질 수 있는 공급자 (람다용 함수형 인터페이스) */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 작업을 최대 maxRetries 회 재시도합니다.
     * 각 재시도 간격은 baseDelayMs × 2^(attempt-1) 로 증가합니다.
     *
     * @param tag        로그에 표시할 호출자 태그 (예: "DB")
     * @param opName     작업 설명 (예: "sensor_data INSERT")
     * @return 작업 결과값
     * @throws Exception maxRetries 초과 시 마지막 예외를 그대로 던집니다.
     */
    public static <T> T run(ThrowingSupplier<T> task,
                            String tag, String opName,
                            int maxRetries, long baseDelayMs) throws Exception {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                T result = task.get();
                if (attempt > 1) {
                    AppLogger.retrySuccess(tag, attempt, opName);
                }
                return result;
            } catch (Exception e) {
                lastEx = e;
                if (attempt < maxRetries) {
                    AppLogger.retryAttempt(tag, attempt, maxRetries, opName, e);
                    long delay = baseDelayMs * (1L << (attempt - 1)); // 지수 증가
                    Thread.sleep(delay);
                }
            }
        }
        AppLogger.retryExhausted(tag, maxRetries, opName);
        throw lastEx;
    }

    /**
     * 반환값이 없는 작업용 오버로드
     */
    public static void runVoid(ThrowingSupplier<Void> task,
                               String tag, String opName,
                               int maxRetries, long baseDelayMs) throws Exception {
        run(task, tag, opName, maxRetries, baseDelayMs);
    }
}
