package repit.repit_api_server.domain.userdata.analysis.behavior;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Jackson이 타입 없이 읽은 JSON(Map·List·String·Number·Boolean·null)을 계약 타입 그대로만 받는다.
 *
 * <p>숫자 문자열("60000"), 문자열 불리언("false"), 정수 자리의 실수(60000.5), NaN·Infinity를 고쳐 받지 않는다. 분석 서버는
 * 엄격한 타입으로 내보내므로 다른 타입이 왔다면 계약이 깨진 것이다.
 */
final class StrictJson {

    private StrictJson() {
    }

    static void require(boolean condition, String path, String rule) {
        if (!condition) {
            throw new VideoResultException(path, rule);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String path) {
        require(value instanceof Map<?, ?>, path, "object required");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, int min, int max, String path) {
        require(value instanceof List<?>, path, "array required");
        List<Object> list = (List<Object>) value;
        require(list.size() >= min && list.size() <= max, path, "array size out of range");
        return list;
    }

    /** 정확히 이 필드들만 있어야 한다. 빠진 것도, 정의되지 않은 것도 받지 않는다. */
    static void exactKeys(Map<String, Object> object, Collection<String> keys, String path) {
        require(object.size() == keys.size() && object.keySet().containsAll(keys), path, "unexpected or missing fields");
    }

    /** 키가 있고 값이 null이어야 한다. */
    static void nullField(Map<String, Object> object, String key, String path) {
        require(object.containsKey(key) && object.get(key) == null, path + "." + key, "null required");
    }

    static long integer(Object value, long min, long max, String path) {
        require(value instanceof Integer || value instanceof Long || value instanceof BigInteger, path, "integer required");
        BigInteger number = value instanceof BigInteger big ? big : BigInteger.valueOf(((Number) value).longValue());
        require(number.compareTo(BigInteger.valueOf(min)) >= 0 && number.compareTo(BigInteger.valueOf(max)) <= 0,
                path, "integer out of range");
        return number.longValue();
    }

    static double number(Object value, double min, double max, String path) {
        require(value instanceof Number, path, "number required");
        double number = value instanceof BigDecimal decimal ? decimal.doubleValue() : ((Number) value).doubleValue();
        require(Double.isFinite(number), path, "finite number required");
        require(number >= min && number <= max, path, "number out of range");
        return number;
    }

    /** 비어 있지 않은 문자열. 길이는 UTF-16 단위가 아니라 유니코드 문자 수로 센다(분석 서버·웹과 같다). */
    static String text(Object value, int maxChars, String path) {
        require(value instanceof String, path, "string required");
        String text = (String) value;
        int chars = text.codePointCount(0, text.length());
        require(chars >= 1 && chars <= maxChars, path, "string length out of range");
        return text;
    }

    static boolean bool(Object value, String path) {
        require(value instanceof Boolean, path, "boolean required");
        return (Boolean) value;
    }

    static void constant(Object value, String expected, String path) {
        require(expected.equals(value), path, "unsupported value");
    }
}
