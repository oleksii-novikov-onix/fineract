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

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.workingcapitalloan.data.WcLoanDelinquencyActionData;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WcLoanDelinquencyAction;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WcLoanDelinquencyActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WcLoanDelinquencyActionReadServiceImpl implements WcLoanDelinquencyActionReadService {

    private final WcLoanDelinquencyActionRepository actionRepository;

    @Transactional(readOnly = true)
    @Override
    public List<WcLoanDelinquencyActionData> retrieveDelinquencyActions(final Long wcLoanId) {
        return actionRepository.findByWcLoanIdOrderById(wcLoanId).stream().map(this::toData).toList();
    }

    private WcLoanDelinquencyActionData toData(final WcLoanDelinquencyAction action) {
        return new WcLoanDelinquencyActionData(action.getId(), action.getAction(), action.getStartDate(), action.getEndDate());
    }

}
