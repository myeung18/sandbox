package com.redhat.service.smartevents.integration.tests.steps;

import java.io.IOException;
import java.time.Duration;

import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redhat.service.smartevents.integration.tests.common.AwaitilityOnTimeOutHandler;
import com.redhat.service.smartevents.integration.tests.common.MetricsConverter;
import com.redhat.service.smartevents.integration.tests.context.PerfTestContext;
import com.redhat.service.smartevents.integration.tests.context.TestContext;
import com.redhat.service.smartevents.integration.tests.context.resolver.ContextResolver;
import com.redhat.service.smartevents.integration.tests.resources.HyperfoilResource;
import com.redhat.service.smartevents.integration.tests.resources.ManagerResource;
import com.redhat.service.smartevents.integration.tests.resources.webhook.performance.WebhookPerformanceResource;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.And;
import io.cucumber.java.en.When;

import static org.assertj.core.api.Assertions.assertThat;

public class PerformanceSteps {

    private final PerfTestContext perfContext;
    private final TestContext context;

    public PerformanceSteps(TestContext context, PerfTestContext perfContext) {
        this.context = context;
        this.perfContext = perfContext;
    }

    @When("^run benchmark with content:$")
    public void createBenchmarkOnHyperfoilWithContent(DocString benchmarkRequest) {
        String resolvedBenchmarkRequest = ContextResolver.resolveWithScenarioContext(context, benchmarkRequest.getContent());

        context.getScenario().log("Benchmark created as below\n\"" + resolvedBenchmarkRequest + "\n\"");
        String perfTestName = HyperfoilResource.addBenchmark(resolvedBenchmarkRequest, benchmarkRequest.getContentType());

        String runId = HyperfoilResource.runBenchmark(perfTestName);
        perfContext.addBenchmarkRun(perfTestName, runId);
        context.getScenario().log("Running benchmark ID " + runId);

        // Wait until scenario execution finish, by default the timeout is specified as part of Hyperfoil scenario
        // In case of some issue with Hyperfoil timeout the hardcoded waiting time here is 4 hours.
        Awaitility.await()
                .conditionEvaluationListener(new AwaitilityOnTimeOutHandler(() -> context.getScenario().log("Unfinished performance run: " + HyperfoilResource.getCompleteRun(runId))))
                .atMost(Duration.ofHours(4L))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(HyperfoilResource.isRunCompleted(runId))
                                .as("Waiting for performance run to finish")
                                .isTrue());
    }

    @And("^the benchmark run \"([^\"]*)\" was executed successfully$")
    public void benchmarkExecutionWasSuccessfully(String perfTestName) {
        if (perfContext.getBenchmarkRun(perfTestName) == null) {
            throw new RuntimeException("there is no benchmark run executed for " + perfTestName + " scenario");
        }

        assertThat(HyperfoilResource.containsFailedRunPhase(perfContext.getBenchmarkRun(perfTestName)))
                .as("Checking if benchmark run contains failed phases: " + HyperfoilResource.getCompleteRun(perfContext.getBenchmarkRun(perfTestName)))
                .isFalse();
    }

    @And("^the total of events received for benchmark \"([^\"]*)\" run in \"([^\"]*)\" phase is equal to the total of cloud events sent in:$")
    public void numberOfEventsReceivedIsEqualToEventsSent(String perfTestName, String phase, DataTable parametersDatatable) {
        parametersDatatable.entries()
                .forEach(entry -> {
                    String runId = perfContext.getBenchmarkRun(perfTestName);
                    String bridgeId = context.getBridge(entry.get("bridge")).getId();
                    String metric = entry.get("metric");
                    Integer totalEventsReceived = WebhookPerformanceResource.getCountEventsReceived(bridgeId, Integer.class);
                    int totalEventsSent = HyperfoilResource.getTotalRequestsSent(runId, phase, metric);
                    assertThat(totalEventsReceived)
                            .isEqualTo(totalEventsSent)
                            .isPositive();
                });
    }

    @When("^store generated report of benchmark run \"([^\"]*)\" to file \"([^\"]*)\"$")
    public void storeGeneratedReportOfBenchmarkRunToFile(String perfTestName, String fileName) {
        try {
            String runId = perfContext.getBenchmarkRun(perfTestName);
            String generatedReport = HyperfoilResource.generateReport(perfTestName, runId);
            HyperfoilResource.storeToHyperfoilResultsFolder(fileName, generatedReport);
        } catch (IOException e) {
            context.getScenario().log(String.format("Failed to store benchmark report into filesystem: %s", e.getMessage()));
        }
    }

    @When("^store results of benchmark run \"([^\"]*)\" to json file \"([^\"]*)\"$")
    public void storeBenchmarkResultsToFile(String perfTestName, String fileName) {
        try {
            String benchmarkRun = perfContext.getBenchmarkRun(perfTestName);
            Object totalStats = HyperfoilResource.getAllStatsJson(benchmarkRun);
            Gson gson = new Gson();
            String totalStatsJson = gson.toJson(totalStats);
            HyperfoilResource.storeToHyperfoilResultsFolder(fileName, totalStatsJson);
        } catch (IOException e) {
            context.getScenario().log(String.format("Failed to store results of benchmark run into filesystem: %s", e.getMessage()));
        }
    }

    @When("^store Manager metrics to json file \"([^\"]*)\"$")
    public void storeManagerMetricsToFile(String fileName) {
        try {
            String managerMetrics = ManagerResource.getManagerMetrics();
            JsonObject convertedMetrics = MetricsConverter.convertToJson(managerMetrics);
            Gson gson = new Gson();
            String convertedMetricsJson = gson.toJson(convertedMetrics);
            HyperfoilResource.storeToHyperfoilResultsFolder(fileName, convertedMetricsJson);
        } catch (IOException e) {
            context.getScenario().log(String.format("Failed to store Manager metrics into filesystem: %s", e.getMessage()));
        }
    }

    @When("^generate (\\d+) random letters into data property \"([^\"]*)\"$")
    public void generateRandomTextIntoDataProperty(int amountOfLetters, String dataPropertyName) {
        String generatedString = RandomStringUtils.randomAlphabetic(amountOfLetters);
        context.setTestData(dataPropertyName, generatedString);
    }
}
