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
package org.apache.fineract.portfolio.workingcapitalloan.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkingCapitalLoanTransactionRepository extends JpaRepository<WorkingCapitalLoanTransaction, Long> {

    List<WorkingCapitalLoanTransaction> findByWcLoan_IdOrderByTransactionDateAscIdAsc(Long wcLoanId);

    Page<WorkingCapitalLoanTransaction> findByWcLoan_IdOrderByTransactionDateAscIdAsc(Long wcLoanId, Pageable pageable);

    Optional<WorkingCapitalLoanTransaction> findByIdAndWcLoan_Id(Long id, Long wcLoanId);

    Optional<WorkingCapitalLoanTransaction> findByWcLoan_IdAndExternalId(Long wcLoanId, ExternalId externalId);

    boolean existsByExternalId(ExternalId externalId);

    @Query("""
            SELECT COALESCE(SUM(t.transactionAmount), 0)
            FROM WorkingCapitalLoanTransaction t
            WHERE t.wcLoan.id = :loanId
            AND t.transactionDate BETWEEN :fromDate AND :toDate
            AND t.reversed = false
            AND t.transactionType IN :reducingTypes
            """)
    BigDecimal sumPaymentsInWindow(@Param("loanId") Long loanId, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
            @Param("reducingTypes") Collection<LoanTransactionType> reducingTypes);
}
