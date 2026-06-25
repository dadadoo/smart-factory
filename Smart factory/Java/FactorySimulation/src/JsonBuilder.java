/**
 * 외부 라이브러리 없이 JSON 문자열을 조립하는 경량 유틸리티.
 * 객체·배열·값(문자열/숫자/불리언/null) 조합을 지원합니다.
 */
public class JsonBuilder {

    private final StringBuilder sb = new StringBuilder();
    private boolean first = true;
    private final boolean isArray;

    private JsonBuilder(boolean isArray) {
        this.isArray = isArray;
        sb.append(isArray ? '[' : '{');
    }

    public static JsonBuilder obj()   { return new JsonBuilder(false); }
    public static JsonBuilder array() { return new JsonBuilder(true);  }

    // ── 객체 전용 ────────────────────────────────────────────

    public JsonBuilder str(String key, String val) {
        return field(key, val == null ? "null" : "\"" + escape(val) + "\"");
    }

    public JsonBuilder num(String key, Number val) {
        return field(key, val == null ? "null" : val.toString());
    }

    public JsonBuilder bool(String key, boolean val) {
        return field(key, val ? "true" : "false");
    }

    public JsonBuilder raw(String key, String json) {
        return field(key, json == null ? "null" : json);
    }

    // ── 배열 전용 ────────────────────────────────────────────

    public JsonBuilder add(String json) {
        sep();
        sb.append(json);
        return this;
    }

    // ── 공통 ─────────────────────────────────────────────────

    public String build() {
        return sb + (isArray ? "]" : "}");
    }

    // ── 내부 ─────────────────────────────────────────────────

    private JsonBuilder field(String key, String valueJson) {
        sep();
        sb.append('"').append(escape(key)).append("\":").append(valueJson);
        return this;
    }

    private void sep() {
        if (!first) sb.append(',');
        first = false;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
