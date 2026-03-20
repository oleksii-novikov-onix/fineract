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
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.DelinquencyBucketResponse;
import org.apache.fineract.client.models.DelinquencyRangeRequest;
import org.apache.fineract.client.models.PostDelinquencyBucketResponse;
import org.apache.fineract.client.models.PostDelinquencyRangeResponse;
import org.apache.fineract.client.models.PutDelinquencyBucketResponse;
import org.apache.fineract.client.models.WcLoanDelinquencyRangeScheduleData;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.loans.LoanTestLifecycleExtension;
import org.apache.fineract.integrationtests.common.products.DelinquencyRangesHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloan.WcLoanDelinquencyRangeScheduleHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloan.WorkingCapitalLoanApplicationHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloan.WorkingCapitalLoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.workingcapitalloanproduct.WorkingCapitalLoanProductHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloanproduct.WorkingCapitalLoanProductTestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Slf4j
@ExtendWith(LoanTestLifecycleExtension.class)
public class WcLoanDelinquencyRangeScheduleIntegrationTest {

    @Test
    public void testDelinquencyBucketWithMinPaymentValue() {
        // given - Create delinquency ranges
        final List<Long> rangeIds = createDelinquencyRanges();

        // when - Create a WC delinquency bucket with minimumPayment percentage
        final BigDecimal initialMinPayment = new BigDecimal("3");
        final PostDelinquencyBucketResponse createResponse = WcLoanDelinquencyRangeScheduleHelper.createWcDelinquencyBucket(rangeIds, 30, 0,
                initialMinPayment, 1);

        // then - Verify creation was successful
        assertNotNull(createResponse);
        assertNotNull(createResponse.getResourceId());
        log.info("Created WC delinquency bucket with id: {}", createResponse.getResourceId());

        // Retrieve the bucket and verify minimumPaymentValue is persisted
        final DelinquencyBucketResponse bucket = WcLoanDelinquencyRangeScheduleHelper.getBucket(createResponse.getResourceId());
        assertNotNull(bucket);
        assertNotNull(bucket.getBucketType(), "bucketType should not be null");
        assertEquals("WORKING_CAPITAL", bucket.getBucketType().getId(), "Bucket type should be WORKING_CAPITAL");
        assertNotNull(bucket.getMinimumPaymentPeriodAndRule(), "minimumPaymentPeriodAndRule should not be null");
        assertEquals(30, bucket.getMinimumPaymentPeriodAndRule().getFrequency(), "Frequency should be 30");
        assertEquals("DAYS", bucket.getMinimumPaymentPeriodAndRule().getFrequencyType().getId(), "Frequency type should be DAYS");
        assertEquals(0, new BigDecimal("3").compareTo(bucket.getMinimumPaymentPeriodAndRule().getMinimumPayment()),
                "Minimum payment should be 3");
        assertEquals("PERCENTAGE", bucket.getMinimumPaymentPeriodAndRule().getMinimumPaymentType().getId(),
                "Minimum payment type should be PERCENTAGE");

        // when - Update the bucket with a different minimumPayment
        final BigDecimal updatedMinPayment = new BigDecimal("5");
        final PutDelinquencyBucketResponse updateResponse = WcLoanDelinquencyRangeScheduleHelper
                .updateWcDelinquencyBucket(createResponse.getResourceId(), rangeIds, 30, 0, updatedMinPayment, 1);

        // then - Verify update was successful
        assertNotNull(updateResponse);
        log.info("Updated WC delinquency bucket with id: {}", updateResponse.getResourceId());

        // Retrieve the bucket again and verify the updated minimumPaymentValue
        final DelinquencyBucketResponse updatedBucket = WcLoanDelinquencyRangeScheduleHelper.getBucket(createResponse.getResourceId());
        assertNotNull(updatedBucket.getMinimumPaymentPeriodAndRule(), "minimumPaymentPeriodAndRule should not be null after update");
        assertEquals(0, new BigDecimal("5").compareTo(updatedBucket.getMinimumPaymentPeriodAndRule().getMinimumPayment()),
                "minimumPayment should be updated to 5");

        // Cleanup - Delete the bucket
        WcLoanDelinquencyRangeScheduleHelper.deleteBucket(createResponse.getResourceId());
        log.info("Deleted WC delinquency bucket with id: {}", createResponse.getResourceId());
    }

    @Test
    public void testRangeScheduleEndpointReturnsEmptyForUndisbursedLoan() {
        // given - Create a WC delinquency bucket with minimum payment configuration
        final List<Long> rangeIds = createDelinquencyRanges();
        final BigDecimal minPayment = new BigDecimal("3");
        final PostDelinquencyBucketResponse bucketResponse = WcLoanDelinquencyRangeScheduleHelper.createWcDelinquencyBucket(rangeIds, 30, 0,
                minPayment, 1);
        assertNotNull(bucketResponse);

        // Create a WC product linked to this delinquency bucket
        final WorkingCapitalLoanProductHelper productHelper = new WorkingCapitalLoanProductHelper();
        final String uniqueName = "WCL Product " + UUID.randomUUID().toString().substring(0, 8);
        final String uniqueShortName = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        final Long productId = productHelper.createWorkingCapitalLoanProduct(new WorkingCapitalLoanProductTestBuilder().withName(uniqueName)
                .withShortName(uniqueShortName).withDelinquencyBucketId(bucketResponse.getResourceId()).build()).getResourceId();
        assertNotNull(productId);

        // Create a client and submit a WC loan
        final Long clientId = ClientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        final WorkingCapitalLoanApplicationHelper applicationHelper = new WorkingCapitalLoanApplicationHelper();
        final Long loanId = applicationHelper.submit(new WorkingCapitalLoanApplicationTestBuilder() //
                .withClientId(clientId) //
                .withProductId(productId) //
                .withPrincipal(BigDecimal.valueOf(10000)) //
                .withPeriodPaymentRate(BigDecimal.ONE) //
                .withTotalPayment(BigDecimal.valueOf(11000)) //
                .buildSubmitJson());
        assertNotNull(loanId);
        log.info("Created WC loan with id: {}", loanId);

        // when - Retrieve the range schedule for this undisbursed loan
        final List<WcLoanDelinquencyRangeScheduleData> schedule = WcLoanDelinquencyRangeScheduleHelper.getDelinquencyRangeSchedule(loanId);

        // then - Should return an empty list (no schedule generated before disbursement + COB)
        assertNotNull(schedule);
        assertTrue(schedule.isEmpty(), "Range schedule should be empty for an undisbursed loan");
    }

    private List<Long> createDelinquencyRanges() {
        final PostDelinquencyRangeResponse range1 = DelinquencyRangesHelper.createRange(new DelinquencyRangeRequest()
                .classification(Utils.randomStringGenerator("DLQ_R_", 10)).minimumAgeDays(1).maximumAgeDays(30).locale("en"));
        final PostDelinquencyRangeResponse range2 = DelinquencyRangesHelper.createRange(new DelinquencyRangeRequest()
                .classification(Utils.randomStringGenerator("DLQ_R_", 10)).minimumAgeDays(31).maximumAgeDays(60).locale("en"));
        return List.of(range1.getResourceId(), range2.getResourceId());
    }
}
