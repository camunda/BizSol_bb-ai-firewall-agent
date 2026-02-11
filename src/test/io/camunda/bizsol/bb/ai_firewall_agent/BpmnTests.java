package io.camunda.bizsol.bb.ai_firewall_agent;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            // disable all real workers in tests to focus on BPMN test
            "camunda.client.worker.defaults.enabled=false"
        })
@CamundaSpringProcessTest
public class BpmnTests {
    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;

    @Test
    void shouldProcessPathC() {
        // given: the processes are deployed
        client.newDeployResourceCommand()
                .addResourceFromClasspath("blueprint-process.bpmn")
                .send()
                .join();

        // when
        final ProcessInstanceEvent processInstance =
                client.newCreateInstanceCommand()
                        .bpmnProcessId("Blueprint_Process")
                        .latestVersion()
                        .send()
                        .join();
        processTestContext.mockJobWorker("A").thenComplete(Map.of("path", "C"));
        processTestContext.mockJobWorker("C").thenComplete();

        // then
        CamundaAssert.assertThat(processInstance)
                .isCompleted()
                .hasCompletedElementsInOrder(
                        "StartEvent_1", "Activity_A", "Gateway_1", "Activity_C");
    }
}
