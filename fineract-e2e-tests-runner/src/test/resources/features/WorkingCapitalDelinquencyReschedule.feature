@WCCOBFeature
Feature: Working Capital Delinquency Reschedule Action

  Scenario: Reschedule changes minimumPayment only
    When Admin sets the business date to "01 January 2026"
    When Admin creates a client with random data
    When Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    When Admin creates a new Working Capital Loan Product with delinquency bucket
    When Admin creates a working capital loan with the following data:
      | LoanProduct      | submittedOnDate  | expectedDisbursementDate | principalAmount | totalPayment | periodPaymentRate | discount |
      | WCLP_DELINQUENCY | 01 January 2026  | 01 January 2026          | 10000           | 10000        | 1                 | 0.0      |
    When Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    When Admin runs inline COB job for Working Capital Loan
    When Admin sets the business date to "01 June 2026"
    When Admin runs inline COB job for Working Capital Loan
    Then WC loan delinquency range schedule has the following periods:
      | periodNumber | fromDate        | toDate          | expectedAmount | paidAmount | outstandingAmount | minPaymentCriteriaMet |
      | 1            | 01 January 2026 | 30 January 2026 | 300            | 0          | 300               | false                 |
      | 2            | 31 January 2026 | 01 March 2026   | 300            | 0          | 300               | false                 |
      | 3            | 02 March 2026   | 31 March 2026   | 300            | 0          | 300               | false                 |
      | 4            | 01 April 2026   | 30 April 2026   | 300            | 0          | 300               | false                 |
      | 5            | 01 May 2026     | 30 May 2026     | 300            | 0          | 300               | false                 |
      | 6            | 31 May 2026     | 29 June 2026    | 300            | 0          | 300               |                       |
    When Admin creates WC delinquency reschedule action with minimumPayment 1 and frequency 30 DAYS
    When Admin sets the business date to "15 August 2026"
    When Admin runs inline COB job for Working Capital Loan
    Then WC loan delinquency range schedule has the following periods:
      | periodNumber | fromDate         | toDate              | expectedAmount | paidAmount | outstandingAmount | minPaymentCriteriaMet |
      | 1            | 01 January 2026  | 30 January 2026     | 300            | 0          | 300               | false                 |
      | 2            | 31 January 2026  | 01 March 2026       | 300            | 0          | 300               | false                 |
      | 3            | 02 March 2026    | 31 March 2026       | 300            | 0          | 300               | false                 |
      | 4            | 01 April 2026    | 30 April 2026       | 300            | 0          | 300               | false                 |
      | 5            | 01 May 2026      | 30 May 2026         | 300            | 0          | 300               | false                 |
      | 6            | 31 May 2026      | 29 June 2026        | 100            | 0          | 100               | false                 |
      | 7            | 30 June 2026     | 29 July 2026        | 100            | 0          | 100               | false                 |
      | 8            | 30 July 2026     | 28 August 2026      | 100            | 0          | 100               |                       |

  Scenario: Reschedule changes frequency only
    When Admin sets the business date to "01 January 2026"
    When Admin creates a client with random data
    When Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    When Admin creates a new Working Capital Loan Product with delinquency bucket
    When Admin creates a working capital loan with the following data:
      | LoanProduct      | submittedOnDate  | expectedDisbursementDate | principalAmount | totalPayment | periodPaymentRate | discount |
      | WCLP_DELINQUENCY | 01 January 2026  | 01 January 2026          | 10000           | 10000        | 1                 | 0.0      |
    When Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    When Admin runs inline COB job for Working Capital Loan
    When Admin sets the business date to "01 June 2026"
    When Admin runs inline COB job for Working Capital Loan
    When Admin creates WC delinquency reschedule action with minimumPayment 3 and frequency 15 DAYS
    When Admin sets the business date to "15 August 2026"
    When Admin runs inline COB job for Working Capital Loan
    Then WC loan delinquency range schedule has the following periods:
      | periodNumber | fromDate         | toDate              | expectedAmount | paidAmount | outstandingAmount | minPaymentCriteriaMet |
      | 1            | 01 January 2026  | 30 January 2026     | 300            | 0          | 300               | false                 |
      | 2            | 31 January 2026  | 01 March 2026       | 300            | 0          | 300               | false                 |
      | 3            | 02 March 2026    | 31 March 2026       | 300            | 0          | 300               | false                 |
      | 4            | 01 April 2026    | 30 April 2026       | 300            | 0          | 300               | false                 |
      | 5            | 01 May 2026      | 30 May 2026         | 300            | 0          | 300               | false                 |
      | 6            | 31 May 2026      | 29 June 2026        | 300            | 0          | 300               | false                 |
      | 7            | 30 June 2026     | 14 July 2026        | 300            | 0          | 300               | false                 |
      | 8            | 15 July 2026     | 29 July 2026        | 300            | 0          | 300               | false                 |
      | 9            | 30 July 2026     | 13 August 2026      | 300            | 0          | 300               | false                 |
      | 10           | 14 August 2026   | 28 August 2026      | 300            | 0          | 300               |                       |

  Scenario: Reschedule changes minimumPayment and frequency
    When Admin sets the business date to "01 January 2026"
    When Admin creates a client with random data
    When Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    When Admin creates a new Working Capital Loan Product with delinquency bucket
    When Admin creates a working capital loan with the following data:
      | LoanProduct      | submittedOnDate  | expectedDisbursementDate | principalAmount | totalPayment | periodPaymentRate | discount |
      | WCLP_DELINQUENCY | 01 January 2026  | 01 January 2026          | 10000           | 10000        | 1                 | 0.0      |
    When Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    When Admin runs inline COB job for Working Capital Loan
    When Admin sets the business date to "01 June 2026"
    When Admin runs inline COB job for Working Capital Loan
    When Admin creates WC delinquency reschedule action with minimumPayment 2 and frequency 15 DAYS
    When Admin sets the business date to "15 August 2026"
    When Admin runs inline COB job for Working Capital Loan
    Then WC loan delinquency range schedule has the following periods:
      | periodNumber | fromDate         | toDate              | expectedAmount | paidAmount | outstandingAmount | minPaymentCriteriaMet |
      | 1            | 01 January 2026  | 30 January 2026     | 300            | 0          | 300               | false                 |
      | 2            | 31 January 2026  | 01 March 2026       | 300            | 0          | 300               | false                 |
      | 3            | 02 March 2026    | 31 March 2026       | 300            | 0          | 300               | false                 |
      | 4            | 01 April 2026    | 30 April 2026       | 300            | 0          | 300               | false                 |
      | 5            | 01 May 2026      | 30 May 2026         | 300            | 0          | 300               | false                 |
      | 6            | 31 May 2026      | 29 June 2026        | 200            | 0          | 200               | false                 |
      | 7            | 30 June 2026     | 14 July 2026        | 200            | 0          | 200               | false                 |
      | 8            | 15 July 2026     | 29 July 2026        | 200            | 0          | 200               | false                 |
      | 9            | 30 July 2026     | 13 August 2026      | 200            | 0          | 200               | false                 |
      | 10           | 14 August 2026   | 28 August 2026      | 200            | 0          | 200               |                       |

  Scenario: Multiple reschedules - last one wins
    When Admin sets the business date to "01 January 2026"
    When Admin creates a client with random data
    When Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    When Admin creates a new Working Capital Loan Product with delinquency bucket
    When Admin creates a working capital loan with the following data:
      | LoanProduct      | submittedOnDate  | expectedDisbursementDate | principalAmount | totalPayment | periodPaymentRate | discount |
      | WCLP_DELINQUENCY | 01 January 2026  | 01 January 2026          | 10000           | 10000        | 1                 | 0.0      |
    When Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    When Admin runs inline COB job for Working Capital Loan
    When Admin creates WC delinquency reschedule action with minimumPayment 2 and frequency 30 DAYS
    When Admin creates WC delinquency reschedule action with minimumPayment 5 and frequency 30 DAYS
    Then WC loan delinquency range schedule has the following periods:
      | periodNumber | fromDate        | toDate          | expectedAmount | paidAmount | outstandingAmount | minPaymentCriteriaMet |
      | 1            | 01 January 2026 | 30 January 2026 | 500            | 0          | 500               |                       |
    Then WC loan delinquency actions contain 2 actions

  Scenario: Reschedule on non-active loan and validation errors are rejected
    When Admin sets the business date to "01 January 2026"
    When Admin creates a client with random data
    When Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    When Admin creates a new Working Capital Loan Product with delinquency bucket
    When Admin creates a working capital loan with the following data:
      | LoanProduct      | submittedOnDate  | expectedDisbursementDate | principalAmount | totalPayment | periodPaymentRate | discount |
      | WCLP_DELINQUENCY | 01 January 2026  | 01 January 2026          | 10000           | 10000        | 1                 | 0.0      |
    Then Admin fails to create WC delinquency reschedule action with minimumPayment 1 and frequency 30 DAYS
    When Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    When Admin runs inline COB job for Working Capital Loan
    Then Admin fails to create WC delinquency reschedule action with minimumPayment 0 and frequency 30 DAYS
    Then Admin fails to create WC delinquency reschedule action with minimumPayment 1 and frequency 0 DAYS
    Then Admin fails to create WC delinquency reschedule action with minimumPayment 1 and frequency 30 INVALID
