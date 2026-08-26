package repit.repit_api_server.global.logging;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 로그에 실을 본문과 헤더를 한 줄로 다듬는다.
 * 원문을 그대로 남기면 비밀번호나 토큰이 그대로 찍히고, 긴 본문 한 건이 로그 전체를 덮어버린다.
 */
final class LogPayloads {

    static final String MASKED = "***";

    // 값 자체가 노출되면 안 되는 필드. JSON 본문과 폼 본문 양쪽에서 같은 이름을 쓴다.
    private static final String SENSITIVE_NAMES =
            "password|passwd|token|access_?token|refresh_?token|authorization|secret|access_?key|secret_?key|credential";

    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(\"(?:" + SENSITIVE_NAMES + ")\"\\s*:\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern SENSITIVE_FORM_FIELD = Pattern.compile(
            "((?:" + SENSITIVE_NAMES + ")=)[^&\\s]*", Pattern.CASE_INSENSITIVE);

    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*[\\r\\n]+\\s*");

    // 이 접두로 시작하는 콘텐츠 타입만 사람이 읽을 수 있다고 본다. 나머지는 크기만 남긴다.
    private static final List<String> READABLE_CONTENT_TYPES = List.of(
            "application/json",
            "application/xml",
            "application/x-www-form-urlencoded",
            "application/graphql",
            "text/"
    );

    private LogPayloads() {
    }

    /**
     * 본문을 한 줄짜리 로그 조각으로 만든다. 읽을 수 없는 형식이거나 너무 길면 요약으로 대신한다.
     */
    static String summarize(byte[] body, String contentType, Charset charset, int maxLength) {
        if (body == null || body.length == 0) {
            return null;
        }
        if (!isReadable(contentType)) {
            return "(" + shortContentType(contentType) + ", " + humanReadableSize(body.length) + ")";
        }

        String text = new String(body, charset != null ? charset : StandardCharsets.UTF_8);
        String oneLine = LINE_BREAKS.matcher(text).replaceAll(" ").trim();
        String masked = mask(oneLine);

        if (masked.length() <= maxLength) {
            return masked;
        }
        return masked.substring(0, maxLength) + "...(총 " + humanReadableSize(body.length) + ")";
    }

    /**
     * 값 없이 자격 증명이 있었다는 사실만 남긴다. 토큰 앞자리도 그 자체로 단서가 되므로 통째로 가린다.
     */
    static String maskAuthorization(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        int typeEnd = headerValue.indexOf(' ');
        if (typeEnd <= 0) {
            return MASKED;
        }
        return headerValue.substring(0, typeEnd) + " " + MASKED;
    }

    static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = SENSITIVE_JSON_FIELD.matcher(text).replaceAll("$1\"" + MASKED + "\"");
        return SENSITIVE_FORM_FIELD.matcher(result).replaceAll("$1" + MASKED);
    }

    static String humanReadableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static boolean isReadable(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            // 타입을 밝히지 않은 본문은 대개 JSON이라 읽어본다. 깨져도 길이 제한이 걸려 있어 로그가 넘치지는 않는다.
            return true;
        }
        String lowerCase = contentType.toLowerCase();
        return READABLE_CONTENT_TYPES.stream().anyMatch(lowerCase::startsWith);
    }

    private static String shortContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "binary";
        }
        int parameterStart = contentType.indexOf(';');
        return parameterStart < 0 ? contentType : contentType.substring(0, parameterStart);
    }
}
