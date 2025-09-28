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
package org.apache.fineract.portfolio.loanaccount.api;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class ReAgePreviewQueryParams {

    @QueryParam("frequencyNumber")
    @Parameter(description = "The frequency number for the re-aging schedule", required = true)
    private Integer frequencyNumber;

    @QueryParam("frequencyType")
    @Parameter(description = "The frequency type (DAYS, WEEKS, MONTHS, YEARS)", required = true)
    private String frequencyType;

    @QueryParam("startDate")
    @Parameter(description = "The start date for the re-aging schedule", required = true)
    private String startDate;

    @QueryParam("numberOfInstallments")
    @Parameter(description = "The number of installments for the re-aged loan", required = true)
    private Integer numberOfInstallments;

    @QueryParam("dateFormat")
    @Parameter(description = "The date format used for the startDate parameter")
    private String dateFormat;

    @QueryParam("locale")
    @Parameter(description = "The locale to use for formatting")
    private String locale;
}
