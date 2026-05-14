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
package org.apache.fineract.portfolio.workingcapitalloan.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoan;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachSchedule;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanPeriodFrequencyType;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachScheduleRepository;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanTransactionRepository;
import org.apache.fineract.portfolio.workingcapitalloannearbreach.domain.WorkingCapitalNearBreach;
import org.apache.fineract.portfolio.workingcapitalloanproduct.domain.WorkingCapitalLoanProductRelatedDetails;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class WorkingCapitalLoanNearBreachEvaluationServiceImpl implements WorkingCapitalLoanNearBreachEvaluationService {

    private static final Set<LoanTransactionType> REDUCING_TRANSACTION_TYPES = Set.of(LoanTransactionType.REPAYMENT,
            LoanTransactionType.GOODWILL_CREDIT, LoanTransactionType.CREDIT_BALANCE_REFUND);

    private final WorkingCapitalLoanBreachScheduleRepository breachScheduleRepository;
    private final WorkingCapitalLoanTransactionRepository transactionRepository;

    @Override
    public void evaluateNearBreach(final WorkingCapitalLoan loan, final LocalDate businessDate) {
        final Optional<WorkingCapitalNearBreach> nearBreachConfigOpt = getNearBreachConfig(loan);
        if (nearBreachConfigOpt.isEmpty()) {
            return;
        }
        final WorkingCapitalNearBreach config = nearBreachConfigOpt.get();
        final List<WorkingCapitalLoanBreachSchedule> periods = breachScheduleRepository.findByLoanIdOrderByPeriodNumberAsc(loan.getId());
        final List<WorkingCapitalLoanBreachSchedule> updatedPeriods = new ArrayList<>();
        for (final WorkingCapitalLoanBreachSchedule period : periods) {
            if (period.getNearBreach() != null) {
                continue;
            }
            if (evaluatePeriod(loan.getId(), period, config, businessDate)) {
                updatedPeriods.add(period);
            }
        }
        if (!updatedPeriods.isEmpty()) {
            breachScheduleRepository.saveAllAndFlush(updatedPeriods);
        }
    }

    private boolean evaluatePeriod(final Long loanId, final WorkingCapitalLoanBreachSchedule period, final WorkingCapitalNearBreach config,
            final LocalDate businessDate) {
        if (period.getMinPaymentAmount().compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        final List<LocalDate> evalDates = listEvalDates(period.getFromDate(), period.getToDate(), config.getFrequency(),
                config.getFrequencyType());
        if (evalDates.isEmpty()) {
            return false;
        }
        final BigDecimal required = config.getThreshold().divide(BigDecimal.valueOf(100), MoneyHelper.getMathContext())
                .multiply(period.getMinPaymentAmount(), MoneyHelper.getMathContext());
        boolean anyPassed = false;
        LocalDate previousEvalDate = null;
        for (int n = 0; n < evalDates.size(); n++) {
            final LocalDate evalDate = evalDates.get(n);
            if (!businessDate.isAfter(evalDate)) {
                break;
            }
            anyPassed = true;
            final LocalDate windowFrom = (n == 0) ? period.getFromDate() : previousEvalDate.plusDays(1);
            final BigDecimal paidInWindow = transactionRepository.sumPaymentsInWindow(loanId, windowFrom, evalDate,
                    REDUCING_TRANSACTION_TYPES);
            if (paidInWindow.compareTo(required) < 0) {
                period.setNearBreach(true);
                log.debug("Near breach detected for period {} of WC loan {}: window=[{},{}] paid={} required={}", period.getPeriodNumber(),
                        loanId, windowFrom, evalDate, paidInWindow, required);
                return true;
            }
            previousEvalDate = evalDate;
        }
        if (anyPassed && businessDate.isAfter(period.getToDate())) {
            period.setNearBreach(false);
            log.debug("No near breach for period {} of WC loan {} after all evaluation points", period.getPeriodNumber(), loanId);
            return true;
        }
        return false;
    }

    private List<LocalDate> listEvalDates(final LocalDate fromDate, final LocalDate toDate, final Integer frequency,
            final WorkingCapitalLoanPeriodFrequencyType frequencyType) {
        final List<LocalDate> dates = new ArrayList<>();
        LocalDate next = addFrequency(fromDate, frequency, frequencyType);
        while (!next.isAfter(toDate)) {
            dates.add(next);
            next = addFrequency(next, frequency, frequencyType);
        }
        return dates;
    }

    private LocalDate addFrequency(final LocalDate date, final Integer frequency,
            final WorkingCapitalLoanPeriodFrequencyType frequencyType) {
        return switch (frequencyType) {
            case DAYS -> date.plusDays(frequency);
            case WEEKS -> date.plusWeeks(frequency);
            case MONTHS -> date.plusMonths(frequency);
            case YEARS -> date.plusYears(frequency);
        };
    }

    private Optional<WorkingCapitalNearBreach> getNearBreachConfig(final WorkingCapitalLoan loan) {
        final WorkingCapitalLoanProductRelatedDetails details = loan.getLoanProductRelatedDetails();
        if (details == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(details.getNearBreach());
    }
}
