package io.camunda.bizsol.jsonconverter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class JsonConverterWorker {

    private static final Logger logger = LoggerFactory.getLogger(JsonConverterWorker.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @JobWorker(type = "json-converter")
    public Map<String, Object> convertJsonString(@Variable String jsonString) {
        try {
            logger.info("Converting JSON string: {}", jsonString);
            Object jsonObject = objectMapper.readValue(jsonString, Object.class);
            logger.info("Successfully converted JSON string");
            return Map.of("result", jsonObject);
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert JSON string: {}", jsonString, e);
            throw new RuntimeException("Invalid JSON format: " + e.getMessage(), e);
        }
    }
}

