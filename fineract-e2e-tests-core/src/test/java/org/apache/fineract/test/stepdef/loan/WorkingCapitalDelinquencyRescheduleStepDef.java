/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.test.stepdef.loan;

import static org.apache.fineract.client.feign.util.FeignCalls.fail;
import static org.apache.fineract.client.feign.util.FeignCalls.ok;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.feign.FineractFeignClient;
import org.apache.fineract.client.models.CommandProcessingResult;
import org.apache.fineract.client.models.DelinquencyBucketRequest;
import org.apache.fineract.client.models.MinimumPaymentPeriodAndRule;
import org.apache.fineract.client.models.PostDelinquencyBucketResponse;
import org.apache.fineract.client.models.PostWcLoanDelinquencyActionRequest;
import org.apache.fineract.client.models.PostWorkingCapitalLoanProductsRequest;
import org.apache.fineract.client.models.PostWorkingCapitalLoanProductsResponse;
import org.apache.fineract.client.models.PostWorkingCapitalLoansResponse;
import org.apache.fineract.client.models.WcLoanDelinquencyActionData;
import org.apache.fineract.client.models.WcLoanDelinquencyRangeScheduleData;
import org.apache.fineract.test.factory.WorkingCapitalRequestFactory;
import org.apache.fineract.test.helper.Utils;
import org.apache.fineract.test.stepdef.AbstractStepDef;
import org.apache.fineract.test.support.TestContext;
import org.apache.fineract.test.support.TestContextKey;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@RequiredArgsConstructor
public class WorkingCapitalDelinquencyRescheduleStepDef extends AbstractStepDef {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    private final FineractFeignClient fineractFeignClient;

    @Autowired
    private WorkingCapitalRequestFactory workingCapitalRequestFactory;

    @When("Admin creates a new Working Capital Loan Product with delinquency bucket")
    public void createProductWithDelinquencyBucket() {
        final Long bucketId = TestContext.GLOBAL.get(TestContextKey.DELINQUENCY_BUCKET_ID);
        assertThat(bucketId).isNotNull();

        final PostWorkingCapitalLoanProductsRequest request = workingCapitalRequestFactory.defaultWorkingCapitalLoanProductRequest()
                .name("WCLP-DLQ-" + Utils.randomStringGenerator(8)).delinquencyBucketId(bucketId);
        final PostWorkingCapitalLoanProductsResponse response = ok(
                () -> fineractFeignClient.workingCapitalLoanProducts().createWorkingCapitalLoanProduct(request));
        testContext().set(TestContextKey.WORKING_CAPITAL_LOAN_PRODUCT_CREATE_RESPONSE, response);
        testContext().set(TestContextKey.WORKING_CAPITAL_LOAN_PRODUCT_CREATE_REQUEST, request);
        log.info("Created WC product id={} with delinquency bucket id={}", response.getResourceId(), bucketId);
    }

    @When("Admin creates WC Delinquency Bucket with frequency {int} {word} and minimumPayment {int} {word}")
    public void createWcDelinquencyBucket(final int frequency, final String frequencyType, final int minimumPayment,
            final String minimumPaymentType) {
        final DelinquencyBucketRequest request = new DelinquencyBucketRequest().name("DB-WCL-" + Utils.randomStringGenerator(12))
                .bucketType("WORKING_CAPITAL").ranges(List.of(1L))
                .minimumPaymentPeriodAndRule(new MinimumPaymentPeriodAndRule().frequency(frequency).frequencyType(frequencyType)
                        .minimumPayment(new BigDecimal(minimumPayment)).minimumPaymentType(minimumPaymentType));

        final PostDelinquencyBucketResponse result = ok(
                () -> fineractFeignClient.delinquencyRangeAndBucketsManagement().createBucket(request));
        assertThat(result).isNotNull();
        assertThat(result.getResourceId()).isNotNull();
        TestContext.GLOBAL.set(TestContextKey.DELINQUENCY_BUCKET_ID, result.getResourceId());
        log.info("Created WC delinquency bucket id={} with frequency={} {} minimumPayment={} {}", result.getResourceId(), frequency,
                frequencyType, minimumPayment, minimumPaymentType);
    }

    @When("Admin creates WC delinquency reschedule action with minimumPayment {int} and frequency {int} {word}")
    public void createRescheduleAction(final int minimumPayment, final int frequency, final String frequencyType) {
        final Long loanId = getLoanId();
        final PostWcLoanDelinquencyActionRequest request = buildRescheduleRequest(new BigDecimal(minimumPayment), frequency, frequencyType);
        log.info("Creating RESCHEDULE action for WC loan {}: minimumPayment={}, frequency={} {}", loanId, minimumPayment, frequency,
                frequencyType);

        final CommandProcessingResult result = ok(
                () -> fineractFeignClient.workingCapitalLoanDelinquencyActions().createDelinquencyAction(loanId, request));
        assertThat(result).isNotNull();
        assertThat(result.getResourceId()).isNotNull();
        log.info("RESCHEDULE action created with id={}", result.getResourceId());
    }

    @Then("Admin fails to create WC delinquency reschedule action with minimumPayment {int} and frequency {int} {word}")
    public void failToCreateRescheduleAction(final int minimumPayment, final int frequency, final String frequencyType) {
        final Long loanId = getLoanId();
        final PostWcLoanDelinquencyActionRequest request = buildRescheduleRequest(new BigDecimal(minimumPayment), frequency, frequencyType);
        log.info("Attempting to create RESCHEDULE action for WC loan {} (expecting failure): minimumPayment={}, frequency={} {}", loanId,
                minimumPayment, frequency, frequencyType);

        fail(() -> fineractFeignClient.workingCapitalLoanDelinquencyActions().createDelinquencyAction(loanId, request));
    }

    @Then("WC loan delinquency range schedule has the following periods:")
    public void verifyPeriods(final DataTable table) {
        final Long loanId = getLoanId();
        final List<WcLoanDelinquencyRangeScheduleData> periods = ok(
                () -> fineractFeignClient.workingCapitalLoanDelinquencyRangeSchedule().retrieveDelinquencyRangeSchedule(loanId));

        final List<Map<String, String>> expectedRows = table.asMaps();
        assertThat(periods).as("Number of periods").hasSize(expectedRows.size());

        for (int i = 0; i < expectedRows.size(); i++) {
            final Map<String, String> expected = expectedRows.get(i);
            final WcLoanDelinquencyRangeScheduleData actual = periods.get(i);
            final String periodLabel = "Period " + (i + 1);

            assertThat(actual.getPeriodNumber()).as(periodLabel + " periodNumber")
                    .isEqualTo(Integer.parseInt(expected.get("periodNumber")));
            assertThat(actual.getFromDate()).as(periodLabel + " fromDate")
                    .isEqualTo(LocalDate.parse(expected.get("fromDate"), DATE_FORMAT));
            assertThat(actual.getToDate()).as(periodLabel + " toDate").isEqualTo(LocalDate.parse(expected.get("toDate"), DATE_FORMAT));
            assertThat(actual.getExpectedAmount()).as(periodLabel + " expectedAmount")
                    .isEqualByComparingTo(new BigDecimal(expected.get("expectedAmount")));
            assertThat(actual.getPaidAmount()).as(periodLabel + " paidAmount")
                    .isEqualByComparingTo(new BigDecimal(expected.get("paidAmount")));
            assertThat(actual.getOutstandingAmount()).as(periodLabel + " outstandingAmount")
                    .isEqualByComparingTo(new BigDecimal(expected.get("outstandingAmount")));

            final String criteriaMetStr = expected.get("minPaymentCriteriaMet");
            if (criteriaMetStr == null || criteriaMetStr.isBlank()) {
                assertThat(actual.getMinPaymentCriteriaMet()).as(periodLabel + " minPaymentCriteriaMet").isNull();
            } else {
                assertThat(actual.getMinPaymentCriteriaMet()).as(periodLabel + " minPaymentCriteriaMet")
                        .isEqualTo(Boolean.parseBoolean(criteriaMetStr));
            }
        }
    }

    @Then("WC loan delinquency range schedule has periods with expectedAmount {int}")
    public void verifyExpectedAmount(final int expectedAmount) {
        final Long loanId = getLoanId();
        final List<WcLoanDelinquencyRangeScheduleData> periods = ok(
                () -> fineractFeignClient.workingCapitalLoanDelinquencyRangeSchedule().retrieveDelinquencyRangeSchedule(loanId));

        assertThat(periods).isNotEmpty();
        final BigDecimal expected = new BigDecimal(expectedAmount);
        for (final WcLoanDelinquencyRangeScheduleData period : periods) {
            if (period.getMinPaymentCriteriaMet() == null) {
                assertThat(period.getExpectedAmount()).as("Period %d expectedAmount", period.getPeriodNumber())
                        .isEqualByComparingTo(expected);
            }
        }
        log.info("Verified {} unevaluated periods have expectedAmount={}", periods.size(), expectedAmount);
    }

    @Then("WC loan delinquency actions contain {int} action(s) with type RESCHEDULE")
    public void verifyRescheduleActionExists(final int count) {
        final Long loanId = getLoanId();
        final List<WcLoanDelinquencyActionData> actions = ok(
                () -> fineractFeignClient.workingCapitalLoanDelinquencyActions().retrieveDelinquencyActions(loanId));

        final long rescheduleCount = actions.stream().filter(a -> WcLoanDelinquencyActionData.ActionEnum.RESCHEDULE.equals(a.getAction()))
                .count();
        assertThat(rescheduleCount).as("RESCHEDULE action count").isEqualTo(count);
    }

    @Then("WC loan delinquency actions contain {int} action(s)")
    public void verifyActionCount(final int count) {
        final Long loanId = getLoanId();
        final List<WcLoanDelinquencyActionData> actions = ok(
                () -> fineractFeignClient.workingCapitalLoanDelinquencyActions().retrieveDelinquencyActions(loanId));

        assertThat(actions).hasSize(count);
    }

    private Long getLoanId() {
        final PostWorkingCapitalLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        assertThat(loanResponse).isNotNull();
        return loanResponse.getLoanId();
    }

    private PostWcLoanDelinquencyActionRequest buildRescheduleRequest(final BigDecimal minimumPayment, final int frequency,
            final String frequencyType) {
        return new PostWcLoanDelinquencyActionRequest().action("reschedule").minimumPayment(minimumPayment).frequency(frequency)
                .frequencyType(frequencyType).locale("en");
    }
}
