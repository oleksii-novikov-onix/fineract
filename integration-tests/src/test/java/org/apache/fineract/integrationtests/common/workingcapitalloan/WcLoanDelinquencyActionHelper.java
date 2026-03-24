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
package org.apache.fineract.integrationtests.common.workingcapitalloan;

import static org.apache.fineract.client.feign.util.FeignCalls.ok;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.CommandProcessingResult;
import org.apache.fineract.client.models.WcLoanDelinquencyActionData;
import org.apache.fineract.integrationtests.common.FineractFeignClientHelper;

@Slf4j
public final class WcLoanDelinquencyActionHelper {

    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private WcLoanDelinquencyActionHelper() {}

    public static CommandProcessingResult createDelinquencyAction(final Long loanId, final String action, final LocalDate startDate,
            final LocalDate endDate) {
        final String body = buildActionJson(action, startDate, endDate);
        log.info("Creating delinquency action for loan {} body={}", loanId, body);
        return ok(() -> FineractFeignClientHelper.getFineractFeignClient().workingCapitalLoanDelinquencyActions()
                .createDelinquencyAction(loanId, body));
    }

    public static List<WcLoanDelinquencyActionData> retrieveDelinquencyActions(final Long loanId) {
        return ok(() -> FineractFeignClientHelper.getFineractFeignClient().workingCapitalLoanDelinquencyActions()
                .retrieveDelinquencyActions(loanId));
    }

    public static void activateLoan(final Long loanId, final LocalDate disbursementDate) {
        final String dateStr = disbursementDate.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        log.info("Activating WC loan {} with disbursement date {}", loanId, dateStr);
        ok(() -> {
            FineractFeignClientHelper.getFineractFeignClient().internalWorkingCapitalLoans().activateLoan(loanId, dateStr);
            return null;
        });
    }

    public static void generateNextDelinquencyPeriod(final Long loanId, final LocalDate businessDate) {
        final String dateStr = businessDate.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        log.info("Generating next delinquency period for WC loan {} with business date {}", loanId, dateStr);
        ok(() -> {
            FineractFeignClientHelper.getFineractFeignClient().internalWorkingCapitalLoans().generateNextDelinquencyPeriod(loanId, dateStr);
            return null;
        });
    }

    private static String buildActionJson(final String action, final LocalDate startDate, final LocalDate endDate) {
        final JsonObject json = new JsonObject();
        json.addProperty("action", action);
        json.addProperty("startDate", startDate.format(DateTimeFormatter.ofPattern(DATE_FORMAT)));
        json.addProperty("endDate", endDate.format(DateTimeFormatter.ofPattern(DATE_FORMAT)));
        json.addProperty("dateFormat", DATE_FORMAT);
        json.addProperty("locale", "en");
        return json.toString();
    }
}
