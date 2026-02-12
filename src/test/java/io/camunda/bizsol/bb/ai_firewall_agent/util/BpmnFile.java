package io.camunda.bizsol.bb.ai_firewall_agent.util;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Test utility for loading BPMN files and performing string-level replacements on them before
 * deployment. Useful for overriding property values (e.g. endpoints, model names) in {@code
 * zeebe:input} mappings without depending on external tools.
 */
public class BpmnFile {

    private BpmnFile() {}

    /**
     * Read a BPMN model from a file.
     *
     * @param file the BPMN file to read
     * @return the parsed model instance
     */
    public static BpmnModelInstance read(File file) {
        return Bpmn.readModelFromFile(file);
    }

    /**
     * Perform string-level replacements on a BPMN file and return the parsed model. Each {@link
     * Replace} is applied in order via {@link String#replace(CharSequence, CharSequence)}.
     *
     * <p>Example: swap the LLM endpoint for a WireMock URL before deploying:
     *
     * <pre>
     * BpmnFile.replace(bpmnFile,
     *     Replace.replace("http://localhost:11434/v1", "http://localhost:8089/v1"));
     * </pre>
     */
    public static BpmnModelInstance replace(File file, Replace... replaces) {
        try {
            String modelXml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (var replace : replaces) {
                modelXml = modelXml.replace(replace.oldValue(), replace.newValue());
            }
            return Bpmn.readModelFromStream(
                    new ByteArrayInputStream(modelXml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record Replace(String oldValue, String newValue) {
        public static Replace replace(String oldValue, String newValue) {
            return new Replace(oldValue, newValue);
        }
    }
}
