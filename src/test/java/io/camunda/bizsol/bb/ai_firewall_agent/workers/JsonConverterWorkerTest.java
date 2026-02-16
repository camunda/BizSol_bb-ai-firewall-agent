package io.camunda.bizsol.bb.ai_firewall_agent.workers;

import static org.junit.jupiter.api.Assertions.*;

import io.camunda.client.exception.BpmnError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plain unit tests for {@link JsonConverterWorker} – no Spring context, no CPT. */
class JsonConverterWorkerTest {

    private final JsonConverterWorker worker = new JsonConverterWorker();

    // ── happy-path tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Parses a flat JSON object into a Map under the 'result' key")
    void parsesFlatJsonObject() {
        String json =
                """
                {"decision":"allow","confidence":0.99}""";

        Map<String, Object> result = worker.convertJsonString(json);

        assertNotNull(result);
        assertTrue(result.containsKey("result"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) result.get("result");
        assertEquals("allow", inner.get("decision"));
        assertEquals(0.99, inner.get("confidence"));
    }

    @Test
    @DisplayName("Parses a nested JSON object correctly")
    void parsesNestedJsonObject() {
        String json =
                """
                {"outer":{"inner":"value"}}""";

        Map<String, Object> result = worker.convertJsonString(json);

        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) result.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) outer.get("outer");
        assertEquals("value", inner.get("inner"));
    }

    @Test
    @DisplayName("Parses a JSON array as the top-level element")
    void parsesJsonArray() {
        String json =
                """
                [1, 2, 3]""";

        Map<String, Object> result = worker.convertJsonString(json);

        assertInstanceOf(List.class, result.get("result"));
        assertEquals(List.of(1, 2, 3), result.get("result"));
    }

    @Test
    @DisplayName("Parses a JSON string primitive")
    void parsesJsonStringPrimitive() {
        String json = "\"hello\"";

        Map<String, Object> result = worker.convertJsonString(json);

        assertEquals("hello", result.get("result"));
    }

    @Test
    @DisplayName("Parses an empty JSON object")
    void parsesEmptyJsonObject() {
        Map<String, Object> result = worker.convertJsonString("{}");

        assertNotNull(result.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) result.get("result");
        assertTrue(inner.isEmpty());
    }

    // ── error-path tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Throws BpmnError for malformed JSON")
    void throwsOnMalformedJson() {
        BpmnError ex = assertThrows(BpmnError.class, () -> worker.convertJsonString("{bad json}"));

        assertEquals("jsonConversionError", ex.getErrorCode());
        assertTrue(ex.getErrorMessage().startsWith("Invalid JSON format:"));
    }

    @Test
    @DisplayName("Throws on null jsonString")
    void throwsOnNullInput() {
        assertThrows(Exception.class, () -> worker.convertJsonString(null));
    }

    @Test
    @DisplayName("Throws BpmnError for XML input")
    void throwsOnXmlInput() {
        String xml = "<root><item>value</item></root>";

        BpmnError ex = assertThrows(BpmnError.class, () -> worker.convertJsonString(xml));

        assertEquals("jsonConversionError", ex.getErrorCode());
        assertTrue(ex.getErrorMessage().startsWith("Invalid JSON format:"));
    }

    @Test
    @DisplayName("Throws BpmnError for plain text input")
    void throwsOnPlainTextInput() {
        BpmnError ex =
                assertThrows(
                        BpmnError.class,
                        () -> worker.convertJsonString("Hello, this is plain text."));

        assertEquals("jsonConversionError", ex.getErrorCode());
        assertTrue(ex.getErrorMessage().startsWith("Invalid JSON format:"));
    }
}
