@WorkingCapital
@WorkingCapitalLoanScheduleBusinessEventsFeature @WCCOBFeature
Feature: Working Capital Loan Schedule Business Events

  Scenario: Working Capital loan raises Period Payment Rate Changed business event when the rate is updated
    When Admin sets the business date to "01 January 2026"
    And Admin creates a client with random data
    And Admin creates a working capital loan with the following data:
      | LoanProduct | submittedOnDate | expectedDisbursementDate | principalAmount | totalPaymentVolume | periodPaymentRate | discount |
      | WCLP        | 01 January 2026 | 01 January 2026          | 100             | 100                | 1                 | 0        |
    Then Working capital loan creation was successful
    And Admin successfully approves the working capital loan on "01 January 2026" with "100" amount and expected disbursement date on "01 January 2026"
    And a Working Capital Loan Balance Changed business event is raised on approval
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "100" EUR transaction amount
    Then Working Capital loan status will be "ACTIVE"
    When Admin sets the business date to "15 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin update Working Capital period payment rate with "12.5" value
    Then a Working Capital Loan Period Payment Rate Changed business event is raised with "12.5" payment rate

  Scenario: Working Capital loan raises Delinquency Schedule Changed business event on a delinquency pause and Calculation Disabled and Enabled business events on disable and enable
    When Admin sets the business date to "01 January 2026"
    And Admin creates a client with random data
    And Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    And Admin creates a new Working Capital Loan Product with delinquency bucket
    And Admin creates a working capital loan using created product with the following data:
      | submittedOnDate | expectedDisbursementDate | principalAmount | totalPaymentVolume | periodPaymentRate | discount |
      | 01 January 2026 | 01 January 2026          | 10000           | 10000              | 1                 | 0.0      |
    And Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And a Working Capital Loan Balance Changed business event is raised on approval
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    Then Working Capital loan status will be "ACTIVE"
    And Admin runs inline COB job for Working Capital Loan by loanId
    When Admin sets the business date to "15 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin initiate a Working Capital loan delinquency pause with startDate "15 January 2026" and endDate "25 January 2026"
    Then a Working Capital Loan Delinquency Schedule Changed business event is raised
    When Admin sets the business date to "26 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin initiate a Working Capital loan delinquency disable with startDate "26 January 2026"
    Then a Working Capital Loan Delinquency Calculation Disabled business event is raised
    When Admin sets the business date to "28 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin initiate a Working Capital loan delinquency enable with startDate "28 January 2026"
    Then a Working Capital Loan Delinquency Calculation Enabled business event is raised

  Scenario: Working Capital loan raises Breach Schedule Changed business event on a breach pause and Calculation Disabled and Enabled business events on disable and enable
    When Admin sets the business date to "01 January 2026"
    And Admin creates a client with random data
    And Admin creates a new Working Capital Loan Product with breachId and overrides enabled
    And Admin creates a working capital loan using created product with the following data:
      | submittedOnDate | expectedDisbursementDate | principalAmount | totalPaymentVolume | periodPaymentRate | discount |
      | 01 January 2026 | 01 January 2026          | 9000            | 100000             | 18                | 0        |
    And Admin successfully approves the working capital loan on "01 January 2026" with "9000" amount and expected disbursement date on "01 January 2026"
    And a Working Capital Loan Balance Changed business event is raised on approval
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "9000" EUR transaction amount
    Then Working Capital loan status will be "ACTIVE"
    And Admin runs inline COB job for Working Capital Loan by loanId
    When Admin sets the business date to "15 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin initiate a Working Capital loan breach pause with startDate "15 January 2026" and endDate "25 January 2026"
    Then a Working Capital Loan Breach Schedule Changed business event is raised
    When Admin sets the business date to "26 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin initiate a Working Capital loan breach disable with startDate "26 January 2026"
    Then a Working Capital Loan Breach Calculation Disabled business event is raised
    When Admin sets the business date to "28 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin initiate a Working Capital loan breach enable with startDate "28 January 2026"
    Then a Working Capital Loan Breach Calculation Enabled business event is raised

  Scenario: Working Capital loan raises Delinquency Schedule Changed and Breach Schedule Changed business events from the COB run that opens the schedules
    When Admin sets the business date to "01 January 2026"
    And Admin creates a client with random data
    And Admin creates WC Delinquency Bucket with frequency 30 DAYS and minimumPayment 3 PERCENTAGE
    And Admin creates a new Working Capital Loan Product with delinquency bucket and custom breach config:
      | breachFrequency | breachFrequencyType | breachAmountCalculationType | breachAmount |
      | 60              | DAYS                | PERCENTAGE                  | 50           |
    And Admin creates a working capital loan with the following data:
      | LoanProduct      | submittedOnDate | expectedDisbursementDate | principalAmount | totalPaymentVolume | periodPaymentRate | discount |
      | WCLP_DELINQUENCY | 01 January 2026 | 01 January 2026          | 10000           | 10000              | 1                 | 0.0      |
    And Admin successfully approves the working capital loan on "01 January 2026" with "10000" amount and expected disbursement date on "01 January 2026"
    And a Working Capital Loan Balance Changed business event is raised on approval
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "10000" EUR transaction amount
    Then Working Capital loan status will be "ACTIVE"
    When Admin sets the business date to "02 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    Then a Working Capital Loan Delinquency Schedule Changed business event is raised
    And a Working Capital Loan Breach Schedule Changed business event is raised

  Scenario: Working Capital loan raises Breach Schedule Changed business event when a near breach reschedule action is created
    When Admin sets the business date to "01 January 2026"
    And Admin creates a client with random data
    And Admin creates a Working Capital Loan Product with breach and near breach config and overrides enabled:
      | breachFrequency | breachFrequencyType | breachAmountCalculationType | breachAmount | nearBreachFrequency | nearBreachFrequencyType | nearBreachThreshold | delinquencyGraceDays |
      | 3               | MONTHS              | FLAT                        | 900          | 60                  | DAYS                    | 33.33               |                      |
    And Admin creates a working capital loan using created product with the following data:
      | submittedOnDate | expectedDisbursementDate | principalAmount | totalPaymentVolume | periodPaymentRate | discount |
      | 01 January 2026 | 01 January 2026          | 9000            | 100000             | 18                | 0        |
    And Admin successfully approves the working capital loan on "01 January 2026" with "9000" amount and expected disbursement date on "01 January 2026"
    And a Working Capital Loan Balance Changed business event is raised on approval
    And Admin successfully disburse the Working Capital loan on "01 January 2026" with "9000" EUR transaction amount
    Then Working Capital loan status will be "ACTIVE"
    And Admin runs inline COB job for Working Capital Loan by loanId
    When Admin sets the business date to "10 January 2026"
    And Admin runs inline COB job for Working Capital Loan by loanId
    And Admin creates a near breach reschedule action with threshold "50" frequency 6 frequencyType "DAYS"
    Then a Working Capital Loan Breach Schedule Changed business event is raised
