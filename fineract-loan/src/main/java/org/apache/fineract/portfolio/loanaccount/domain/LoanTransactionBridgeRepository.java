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
package org.apache.fineract.portfolio.loanaccount.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionAccountingBridge;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymenttype.domain.PaymentType;
import org.springframework.stereotype.Repository;

@Repository
public class LoanTransactionBridgeRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public List<LoanTransactionAccountingBridge> findTransactionsForAccountingBridge(Loan loan, List<Long> existingTransactionIds,
            List<Long> existingReversedTransactionIds) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<LoanTransactionAccountingBridge> query = cb.createQuery(LoanTransactionAccountingBridge.class);

        Root<LoanTransaction> lt = query.from(LoanTransaction.class);
        Join<LoanTransaction, Loan> loanJoin = lt.join("loan", JoinType.INNER);
        Join<LoanTransaction, Office> officeJoin = lt.join("office", JoinType.INNER);
        Join<LoanTransaction, PaymentDetail> pdJoin = lt.join("paymentDetail", JoinType.LEFT);
        Join<PaymentDetail, PaymentType> ptJoin = pdJoin.join("paymentType", JoinType.LEFT);
        Join<LoanTransaction, LoanTransactionToRepaymentScheduleMapping> ltrsmJoin = lt.join("loanTransactionToRepaymentScheduleMappings",
                JoinType.LEFT);

        Subquery<Long> subquery = query.subquery(Long.class);
        Root<LoanCreditAllocationRule> lcr = subquery.from(LoanCreditAllocationRule.class);
        subquery.select(cb.literal(1L)).where(cb.equal(lcr.get("loan"), loanJoin));

        Expression<Boolean> hasCreditAllocationRules = cb.exists(subquery);

        Expression<BigDecimal> principalPaidSum = cb.coalesce(cb.sum(ltrsmJoin.get("principalPortion")), cb.literal(BigDecimal.ZERO));
        Expression<BigDecimal> feePaidSum = cb.coalesce(cb.sum(ltrsmJoin.get("feeChargesPortion")), cb.literal(BigDecimal.ZERO));
        Expression<BigDecimal> penaltyPaidSum = cb.coalesce(cb.sum(ltrsmJoin.get("penaltyChargesPortion")), cb.literal(BigDecimal.ZERO));

        query.select(cb.construct(LoanTransactionAccountingBridge.class, lt.get("id"), officeJoin.get("id"), lt.get("typeOf"),
                lt.get("reversed"), lt.get("dateOf"), lt.get("amount"), loanJoin.get("netDisbursalAmount"), hasCreditAllocationRules,
                lt.get("principalPortion"), lt.get("interestPortion"), lt.get("feeChargesPortion"), lt.get("penaltyChargesPortion"),
                lt.get("overPaymentPortion"), lt.get("chargeRefundChargeType"), ptJoin.get("id"), principalPaidSum, feePaidSum,
                penaltyPaidSum));

        Predicate loanPredicate = cb.equal(lt.get("loan"), loan);
        Predicate transactionPredicate;

        if (existingTransactionIds == null || existingTransactionIds.isEmpty()) {
            transactionPredicate = cb.equal(lt.get("reversed"), false);
        } else if (existingReversedTransactionIds == null || existingReversedTransactionIds.isEmpty()) {
            transactionPredicate = cb.or(cb.and(cb.equal(lt.get("reversed"), true), lt.get("id").in(existingTransactionIds)),
                    cb.not(lt.get("id").in(existingTransactionIds)));
        } else {
            transactionPredicate = cb.or(
                    cb.and(cb.equal(lt.get("reversed"), true), lt.get("id").in(existingTransactionIds),
                            cb.not(lt.get("id").in(existingReversedTransactionIds))),
                    cb.and(cb.equal(lt.get("reversed"), false), cb.not(lt.get("id").in(existingTransactionIds))));
        }

        query.where(loanPredicate, transactionPredicate);

        query.groupBy(lt.get("id"));

        return entityManager.createQuery(query).getResultList();
    }
}
