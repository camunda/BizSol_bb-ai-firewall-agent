package io.camunda.bizsol.bb.ai_firewall_agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;

/**
 * Regenerates safeguard-systemprompt-feel.txt and updates the embedded system prompt in
 * safeguard-agent.bpmn from the canonical safeguard-systemprompt.txt.
 *
 * <p>Usage: mvn compile exec:java
 */
public class SyncPrompt {

    private static final Path ARTIFACTS = Path.of("camunda-artifacts");

    public static void main(String[] args) throws IOException {
        String prompt = Files.readString(ARTIFACTS.resolve("safeguard-systemprompt.txt"));

        // --- FEEL string literal ---
        String feelBody = toFeelEscaped(prompt);
        String feelStr = "\"" + feelBody + "\"";

        validateWithFeelEngine(feelStr, prompt);
        Files.writeString(ARTIFACTS.resolve("safeguard-systemprompt-feel.txt"), feelStr);
        System.out.printf("FEEL file written (%d chars)%n", feelStr.length());

        // --- BPMN embedding (XML-escape on top of FEEL escaping) ---
        String xmlEscaped = toXmlEscaped(feelBody);
        updateBpmn(xmlEscaped);
    }

    /** FEEL string escaping: backslash, double-quote, newline, tab. */
    static String toFeelEscaped(String raw) {
        var sb = new StringBuilder(raw.length() + 256);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** XML attribute escaping applied on top of the FEEL-escaped content. */
    static String toXmlEscaped(String feel) {
        return feel.replace("&", "&amp;")
                .replace("\"", "&#34;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&#62;");
    }

    /** Parse + evaluate the FEEL string literal and verify round-trip fidelity. */
    private static void validateWithFeelEngine(String feelStr, String expected) {
        FeelEngineApi engine = FeelEngineBuilder.forJava().build();
        var result = engine.evaluateExpression(feelStr, Map.of());

        if (result.isFailure()) {
            System.err.println("FEEL validation FAILED: " + result.failure().message());
            System.exit(1);
        }

        Object evaluated = result.result();
        if (!expected.equals(evaluated)) {
            System.err.println("FEEL round-trip MISMATCH");
            System.err.printf(
                    "  expected length: %d, actual length: %d%n",
                    expected.length(), ((String) evaluated).length());
            System.exit(1);
        }
        System.out.printf("FEEL validation passed (round-trip OK, %d chars)%n", feelStr.length());
    }

    /** Replace the system-prompt value inside the BPMN start-event output mapping. */
    private static void updateBpmn(String xmlEscapedFeel) throws IOException {
        Path bpmnPath = ARTIFACTS.resolve("safeguard-agent.bpmn");
        String bpmn = Files.readString(bpmnPath);

        String startMarker = "source=\"=get or else(systemPrompt, &#34;";
        String endMarker = "&#34;)\" target=\"_systemPrompt\"";

        int startIdx = bpmn.indexOf(startMarker);
        if (startIdx < 0) {
            System.err.println("Start marker not found in BPMN");
            System.exit(1);
        }
        startIdx += startMarker.length();

        int endIdx = bpmn.indexOf(endMarker, startIdx);
        if (endIdx < 0) {
            System.err.println("End marker not found in BPMN");
            System.exit(1);
        }

        int oldLen = endIdx - startIdx;
        String updated = bpmn.substring(0, startIdx) + xmlEscapedFeel + bpmn.substring(endIdx);
        Files.writeString(bpmnPath, updated);
        System.out.printf(
                "BPMN updated (old: %d chars, new: %d chars, delta: %d)%n",
                oldLen, xmlEscapedFeel.length(), xmlEscapedFeel.length() - oldLen);
    }
}
