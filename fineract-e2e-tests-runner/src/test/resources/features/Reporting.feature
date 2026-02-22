@ReportingFeature
Feature: Reporting

  Scenario: Verify Transaction Summary Reports contain all buydown fee transaction types
    When Admin sets the business date to "01 January 2024"
    And Admin creates a new office
    And Admin creates a client with random data in the last created office
    And Admin creates a fully customized loan with the following data:
      | LoanProduct                                              | submitted on date | with Principal | ANNUAL interest rate % | interest type     | interest calculation period | amortization type  | loanTermFrequency | loanTermFrequencyType | repaymentEvery | repaymentFrequencyType | numberOfRepayments | graceOnPrincipalPayment | graceOnInterestPayment | interest free period | Payment strategy            |
      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | 01 January 2024   | 100            | 7                      | DECLINING_BALANCE | DAILY                      | EQUAL_INSTALLMENTS | 3                 | MONTHS                | 1              | MONTHS                 | 3                  | 0                       | 0                      | 0                    | ADVANCED_PAYMENT_ALLOCATION |
    And Admin successfully approves the loan on "01 January 2024" with "100" amount and expected disbursement date on "01 January 2024"
    And Admin successfully disburse the loan on "01 January 2024" with "100" EUR transaction amount
    # Buy Down Fee (type 40)
    When Admin adds buy down fee with "AUTOPAY" payment type to the loan on "01 January 2024" with "50" EUR transaction amount
    # Accumulate amortization
    When Admin sets the business date to "31 January 2024"
    When Admin runs inline COB job for Loan
    When Admin sets the business date to "01 February 2024"
    When Admin runs inline COB job for Loan
    # Buy Down Fee Adjustment (type 41) - non-backdated
    And Admin adds buy down fee adjustment with "AUTOPAY" payment type to the loan on "01 February 2024" with "10" EUR transaction amount
    # Buy Down Fee Adjustment (type 41) - backdated to trigger amortization adjustment
    # Added BEFORE COB so the amortization step sees it and creates type 43
    When Admin sets the business date to "02 February 2024"
    And Admin adds buy down fee adjustment of buy down fee transaction made on "01 January 2024" with "AUTOPAY" payment type to the loan on "10 January 2024" with "25" EUR transaction amount
    When Admin runs inline COB job for Loan
    # Verify Buy Down Fee (type 40) + Disbursement on day 1
    # Buy Down Fee (type 40): all portion fields are NULL (deferred income), allocations = 0
    Then Transaction Summary Report for date "01 January 2024" has the following data:
      | TransactionDate | Product                                                  | TransactionType_Name | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee         | AUTOPAY          |            | 0        | Fees                     |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee         | AUTOPAY          |            | 0        | Interest                 |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee         | AUTOPAY          |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee         | AUTOPAY          |            | 0        | Principal                |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee         | AUTOPAY          |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Disbursement         |                  |            | 0        | Fees                     |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Disbursement         |                  |            | 0        | Interest                 |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Disbursement         |                  |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Disbursement         |                  |            | 0        | Principal                |                      | 100.0              |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Disbursement         |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
    # Verify Buy Down Fee Amortization (type 42) created by COB at business date Feb 1 (COB date = Jan 31)
    # Amortization = 50 * 31/91 - 50 * 30/91 = 17.03 - 16.48 = 0.55 (incremental for 1 day)
    Then Transaction Summary Report for date "31 January 2024" has the following data:
      | TransactionDate | Product                                                  | TransactionType_Name      | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Apply Charges             |                  |            | 0        | Interest                 |                      |                    |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization |                  |            | 0        | Fees                     |                      | 0.0                |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization |                  |            | 0        | Interest                 |                      | 0.55               |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization |                  |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization |                  |            | 0        | Principal                |                      | 0.0                |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
    # Verify Buy Down Fee Adjustment (type 41) + Buy Down Fee Amortization Adjustment (type 43) + Accrual
    # Adjustment (type 41): all portion fields are NULL (deferred income reversal), allocations = 0
    # Amortization Adjustment (type 43): interest_portion = 6.63, negative group → -6.63
    Then Transaction Summary Report for date "01 February 2024" has the following data:
      | TransactionDate | Product                                                  | TransactionType_Name                 | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Apply Charges                        |                  |            | 0        | Interest                 |                      |                    |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Fees                     |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Interest                 |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Principal                |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Fees                     |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Interest                 |                      | -6.63              |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Principal                |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
    # Verify Asset Owner report shows the same buydown fee types with asset owner columns
    Then Transaction Summary Report with Asset Owner for date "01 February 2024" has the following data:
      | TransactionDate | Product                                                  | TransactionType_Name                 | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount | Asset_owner_id | From_asset_owner_id |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Apply Charges                        |                  |            | 0        | Interest                 |                      |                    |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Fees                     |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Interest                 |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Penalty                  |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Principal                |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Fees                     |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Interest                 |                      | -6.63              |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Penalty                  |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Principal                |                      | 0.0                |                |                     |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES | Buy Down Fee Amortization Adjustment |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |                |                     |

  Scenario: Verify Transaction Summary Reports with buyDownFeeIncomeType = FEE
    When Admin sets the business date to "01 January 2024"
    And Admin creates a new office
    And Admin creates a client with random data in the last created office
    And Admin creates a fully customized loan with the following data:
      | LoanProduct                                                          | submitted on date | with Principal | ANNUAL interest rate % | interest type     | interest calculation period | amortization type  | loanTermFrequency | loanTermFrequencyType | repaymentEvery | repaymentFrequencyType | numberOfRepayments | graceOnPrincipalPayment | graceOnInterestPayment | interest free period | Payment strategy            |
      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | 01 January 2024   | 100            | 7                      | DECLINING_BALANCE | DAILY                      | EQUAL_INSTALLMENTS | 3                 | MONTHS                | 1              | MONTHS                 | 3                  | 0                       | 0                      | 0                    | ADVANCED_PAYMENT_ALLOCATION |
    And Admin successfully approves the loan on "01 January 2024" with "100" amount and expected disbursement date on "01 January 2024"
    And Admin successfully disburse the loan on "01 January 2024" with "100" EUR transaction amount
    # Buy Down Fee (type 40)
    When Admin adds buy down fee with "AUTOPAY" payment type to the loan on "01 January 2024" with "50" EUR transaction amount
    # Accumulate amortization
    When Admin sets the business date to "31 January 2024"
    When Admin runs inline COB job for Loan
    When Admin sets the business date to "01 February 2024"
    When Admin runs inline COB job for Loan
    # Buy Down Fee Adjustment (type 41) - non-backdated
    And Admin adds buy down fee adjustment with "AUTOPAY" payment type to the loan on "01 February 2024" with "10" EUR transaction amount
    # Buy Down Fee Adjustment (type 41) - backdated to trigger amortization adjustment
    # Added BEFORE COB so the amortization step sees it and creates type 43
    When Admin sets the business date to "02 February 2024"
    And Admin adds buy down fee adjustment of buy down fee transaction made on "01 January 2024" with "AUTOPAY" payment type to the loan on "10 January 2024" with "25" EUR transaction amount
    When Admin runs inline COB job for Loan
    # Verify Buy Down Fee (type 40) + Disbursement on day 1
    # Buy Down Fee (type 40): all portion fields are NULL (deferred income), allocations = 0
    Then Transaction Summary Report for date "01 January 2024" has the following data:
      | TransactionDate | Product                                                              | TransactionType_Name | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee         | AUTOPAY          |            | 0        | Fees                     |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee         | AUTOPAY          |            | 0        | Interest                 |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee         | AUTOPAY          |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee         | AUTOPAY          |            | 0        | Principal                |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee         | AUTOPAY          |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Disbursement         |                  |            | 0        | Fees                     |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Disbursement         |                  |            | 0        | Interest                 |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Disbursement         |                  |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Disbursement         |                  |            | 0        | Principal                |                      | 100.0              |
      | 2024-01-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Disbursement         |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
    # Verify Buy Down Fee Amortization (type 42) created by COB at business date Feb 1 (COB date = Jan 31)
    # Amortization = 50 * 31/91 - 50 * 30/91 = 17.03 - 16.48 = 0.55 (incremental for 1 day)
    # buyDownFeeIncomeType = FEE → fee_charges_portion = 0.55, interest_portion = NULL
    Then Transaction Summary Report for date "31 January 2024" has the following data:
      | TransactionDate | Product                                                              | TransactionType_Name      | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Apply Charges             |                  |            | 0        | Interest                 |                      |                    |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization |                  |            | 0        | Fees                     |                      | 0.55               |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization |                  |            | 0        | Interest                 |                      | 0.0                |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization |                  |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization |                  |            | 0        | Principal                |                      | 0.0                |
      | 2024-01-31      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
    # Verify Buy Down Fee Adjustment (type 41) + Buy Down Fee Amortization Adjustment (type 43) + Accrual
    # Adjustment (type 41): all portion fields are NULL (deferred income reversal), allocations = 0
    # Amortization Adjustment (type 43): fee_charges_portion = 6.63, negative group → -6.63
    Then Transaction Summary Report for date "01 February 2024" has the following data:
      | TransactionDate | Product                                                              | TransactionType_Name                 | PaymentType_Name | chargetype | Reversed | Allocation_Type          | Chargeoff_ReasonCode | Transaction_Amount |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Apply Charges                        |                  |            | 0        | Interest                 |                      |                    |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Fees                     |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Interest                 |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Principal                |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Adjustment              | AUTOPAY          |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization Adjustment |                  |            | 0        | Fees                     |                      | -6.63              |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization Adjustment |                  |            | 0        | Interest                 |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization Adjustment |                  |            | 0        | Penalty                  |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization Adjustment |                  |            | 0        | Principal                |                      | 0.0                |
      | 2024-02-01      | LP2_PROGRESSIVE_ADVANCED_PAYMENT_ALLOCATION_BUYDOWN_FEES_FEE_INCOME | Buy Down Fee Amortization Adjustment |                  |            | 0        | Unallocated Credit (UNC) |                      | 0.0                |
