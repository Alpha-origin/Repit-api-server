package repit.repit_api_server.domain.userdata.analysis.behavior;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Jackson이 타입 없이 읽은 JSON 값을 다루는 도구. */
public final class JsonValues {

    private JsonValues() {
    }

    /**
     * 두 JSON 값이 의미상 같은지. 객체는 키 순서와 상관없이 같은 키·같은 값이면 같고, 배열은 순서까지 같아야 한다. 숫자는
     * 표기가 아니라 값으로 비교한다(1과 1.0, Integer와 Long이 같다) — jsonb로 저장했다 읽으면 숫자 타입이 바뀔 수 있다.
     */
    public static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Map<?, ?> left && b instanceof Map<?, ?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : left.entrySet()) {
                if (!right.containsKey(entry.getKey()) || !same(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> left && b instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            Iterator<?> l = left.iterator();
            Iterator<?> r = right.iterator();
            while (l.hasNext()) {
                if (!same(l.next(), r.next())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number left && b instanceof Number right) {
            BigDecimal x = decimal(left);
            BigDecimal y = decimal(right);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        return a.equals(b);
    }

    /**
     * 문자열 어딘가에 NUL(U+0000)이 있는지. PostgreSQL은 text·jsonb에 NUL을 넣지 못해 저장이 매번 실패한다 — 그런 결과는
     * 계약 위반으로 따로 남겨야 같은 실패를 끝없이 되풀이하지 않는다.
     */
    public static boolean containsNul(Object value) {
        if (value instanceof String text) {
            return text.indexOf('\u0000') >= 0;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsNul(entry.getKey()) || containsNul(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (containsNul(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BigDecimal decimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
        }
        return BigDecimal.valueOf(number.longValue());
    }
}
