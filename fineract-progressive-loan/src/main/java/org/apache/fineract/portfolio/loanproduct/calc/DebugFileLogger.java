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
package org.apache.fineract.portfolio.loanproduct.calc;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanproduct.calc.data.InterestPeriod;
import org.apache.fineract.portfolio.loanproduct.calc.data.ProgressiveLoanInterestScheduleModel;
import org.apache.fineract.portfolio.loanproduct.calc.data.RepaymentPeriod;

/**
 * Temporary debug logger for FINERACT-2421. Writes directly to a file to avoid mixing with application logs. DELETE
 * THIS CLASS after debugging is complete.
 */
public final class DebugFileLogger {

    private static final String LOG_DIR = "C:\\Users\\aleks\\Data\\projects\\mifos\\fineract2\\";
    private static volatile String currentLogFile;

    // Deduplication for calculateLastUnpaidEMI
    private static volatile String lastEmiLogSignature;

    private static final String OWN_CLASS_NAME = DebugFileLogger.class.getName();

    private DebugFileLogger() {
    }

    private static String getLogFile() {
        return currentLogFile;
    }

    private static String resolveCallerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stack.length; i++) {
            if (!stack[i].getClassName().equals(OWN_CLASS_NAME)) {
                return stack[i].getFileName() + ":" + stack[i].getLineNumber();
            }
        }
        return "unknown";
    }

    public static boolean isEnabled() {
        return currentLogFile != null;
    }

    public static void log(String format, Object... args) {
        String logFile = getLogFile();
        if (logFile == null) {
            return;
        }
        String callerInfo = resolveCallerInfo();
        try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
            pw.printf("[%s] [%s] ", LocalDateTime.now().toLocalTime().withNano(0), callerInfo);
            pw.printf(format, args);
            pw.println();
        } catch (Exception e) {
            // silently ignore
        }
    }

    public static void separator(String label) {
        log("======== %s ========", label);
    }

    public static void dumpModel(String context, ProgressiveLoanInterestScheduleModel model) {
        log("--- MODEL DUMP: %s ---", context);
        log("  lastOverdueBalanceChange=%s totalDuePrincipal=%s totalPaidPrincipal=%s",
                model.lastOverdueBalanceChange(), model.getTotalDuePrincipal(), model.getTotalPaidPrincipal());
        int idx = 1;
        for (RepaymentPeriod rp : model.repaymentPeriods()) {
            log("  P%d [%s -> %s]: EMI=%s origEMI=%s calcDueInterest=%s dueInterest=%s duePrincipal=%s paidP=%s paidI=%s outstandingP=%s outstandingBal=%s fullyPaid=%s",
                    idx, rp.getFromDate(), rp.getDueDate(), rp.getEmi(), rp.getOriginalEmi(), rp.getCalculatedDueInterest(),
                    rp.getDueInterest(), rp.getDuePrincipal(), rp.getPaidPrincipal(), rp.getPaidInterest(), rp.getOutstandingPrincipal(),
                    rp.getOutstandingLoanBalance(), rp.isFullyPaid());
            int ipIdx = 1;
            for (InterestPeriod ip : rp.getInterestPeriods()) {
                log("    IP%d [%s -> %s]: balCorr=%s outBal=%s disb=%s calcDueInt=%s rateFactor=%s rateFactorTillDue=%s",
                        ipIdx, ip.getFromDate(), ip.getDueDate(),
                        ip.getBalanceCorrectionAmount(),
                        ip.getOutstandingLoanBalance(), ip.getDisbursementAmount(),
                        ip.getCalculatedDueInterest(), ip.getRateFactor(), ip.getRateFactorTillPeriodDueDate());
                ipIdx++;
            }
            idx++;
        }
        log("--- END MODEL DUMP ---");
    }

    public static void dumpPeriod(String context, int periodNum, RepaymentPeriod rp) {
        log("  %s P%d: EMI=%s calcDueInt=%s dueInt=%s duePrin=%s paidP=%s paidI=%s outP=%s outBal=%s credited=%s fullyPaid=%s",
                context, periodNum,
                rp.getEmi(), rp.getCalculatedDueInterest(), rp.getDueInterest(), rp.getDuePrincipal(), rp.getPaidPrincipal(),
                rp.getPaidInterest(), rp.getOutstandingPrincipal(), rp.getOutstandingLoanBalance(), rp.getTotalCreditedAmount(),
                rp.isFullyPaid());
    }

    // --- Overdue recalculation logging ---

    public static void logOverdueRecalcEntry(LocalDate targetDate, ProgressiveLoanInterestScheduleModel model,
            List<RepaymentPeriod> overduePeriods) {
        log(">> recalculateModelOverdueAmountsTillDate: targetDate=%s, lastOverdueBalanceChange=%s, overduePeriods=%d",
                targetDate, model.lastOverdueBalanceChange(), overduePeriods.size());
        for (int i = 0; i < overduePeriods.size(); i++) {
            RepaymentPeriod rp = overduePeriods.get(i);
            log("   overdue[%d]: P%d [%s->%s] outstandingP=%s fullyPaid=%s",
                    i, periodNum(model, rp.getFromDate()), rp.getFromDate(), rp.getDueDate(),
                    rp.getOutstandingPrincipal(), rp.isFullyPaid());
        }
    }

    public static void logOverdueLoopIteration(int loopIdx, RepaymentPeriod processingPeriod,
            Money overDuePrincipal, Money aggregatedOverDuePrincipal, boolean skippedBecauseZero,
            ProgressiveLoanInterestScheduleModel model) {
        int pNum = periodNum(model, processingPeriod.getFromDate());
        log("   overdueLoop[%d]: processingPeriod=P%d [%s->%s], overDuePrincipal=%s, aggregated=%s, skippedBecauseZero=%s",
                loopIdx, pNum, processingPeriod.getFromDate(), processingPeriod.getDueDate(),
                overDuePrincipal, aggregatedOverDuePrincipal, skippedBecauseZero);
    }

    public static void logOverdueFinalAdjust(RepaymentPeriod currentPeriod, Money overDuePrincipal,
            Money aggregatedOverDuePrincipal, boolean willExecute, ProgressiveLoanInterestScheduleModel model) {
        int pNum = periodNum(model, currentPeriod.getFromDate());
        log("   overdueFinalAdjust: currentPeriod=P%d [%s->%s], overDuePrincipal=%s, aggregated=%s, willExecute=%s",
                pNum, currentPeriod.getFromDate(), currentPeriod.getDueDate(),
                overDuePrincipal, aggregatedOverDuePrincipal, willExecute);
    }

    public static void logOverdueRecalcResult(boolean hasChange, ProgressiveLoanInterestScheduleModel model) {
        log("<< recalculateModelOverdueAmountsTillDate: hasChange=%s, lastOverdueBalanceChange=%s",
                hasChange, model.lastOverdueBalanceChange());
    }

    // --- adjustOverduePrincipal logging ---

    public static void logAdjustOverdueEntry(LocalDate currentDate, RepaymentPeriod installment,
            Money overduePrincipal, Money aggregated, ProgressiveLoanInterestScheduleModel model) {
        int pNum = periodNum(model, installment.getFromDate());
        log("   >> adjustOverduePrincipal: currentDate=%s, installment=P%d [%s->%s], overduePrincipal=%s, aggregated=%s, lastOverdueBalanceChange=%s",
                currentDate, pNum, installment.getFromDate(), installment.getDueDate(),
                overduePrincipal, aggregated, model.lastOverdueBalanceChange());
    }

    public static void logAdjustOverdueSameDateGuard(LocalDate currentDate) {
        log("   << adjustOverduePrincipal: BLOCKED by same-date guard (currentDate=%s == lastOverdueBalanceChange)", currentDate);
    }

    public static void logAdjustOverdueCorrections(LocalDate positiveDate, Money positiveAmount,
            LocalDate negativeDate, Money negativeAmount, LocalDate newLastOverdueBalanceChange) {
        log("      adjustOverduePrincipal corrections: +%s at %s, %s at %s, newLastOverdueBalanceChange=%s",
                positiveAmount, positiveDate, negativeAmount, negativeDate, newLastOverdueBalanceChange);
    }

    // --- addBalanceCorrection logging ---

    public static void logBalanceCorrectionEntry(LocalDate date, Money amount, ProgressiveLoanInterestScheduleModel model) {
        if (amount != null && amount.isZero()) {
            return; // skip zero-amount noise
        }
        log("   >> addBalanceCorrection: date=%s, amount=%s", date, amount);
    }

    public static void logBalanceCorrectionIPHit(LocalDate date, Money amount, int periodNum,
            InterestPeriod ip, Money balCorrBefore) {
        log("      balanceCorrection applied: date=%s amount=%s -> P%d IP[%s->%s], balCorrBefore=%s, balCorrAfter=%s",
                date, amount, periodNum, ip.getFromDate(), ip.getDueDate(),
                balCorrBefore, ip.getBalanceCorrectionAmount());
    }

    // --- payPrincipal logging ---

    public static void logPayPrincipalEntry(LocalDate fromDate, LocalDate dueDate, LocalDate txDate,
            Money amount, ProgressiveLoanInterestScheduleModel model) {
        int pNum = periodNum(model, fromDate);
        log("   >> payPrincipal: P%d [%s->%s], txDate=%s, amount=%s", pNum, fromDate, dueDate, txDate, amount);
    }

    // --- creditPrincipal logging ---

    public static void logCreditPrincipalEntry(LocalDate txDate, Money amount) {
        log("   >> creditPrincipal: txDate=%s, amount=%s", txDate, amount);
    }

    // --- calculateLastUnpaidRepaymentPeriodEMI logging (with dedup) ---

    public static void logLastUnpaidEMI(ProgressiveLoanInterestScheduleModel model, LocalDate tillDate,
            RepaymentPeriod lastUnpaidPeriod, Money totalDueInterest, Money totalEMI,
            Money totalDisbursed, Money diff, Money adjustedEmi) {
        String signature = String.format("%s|%s|%s|%s|%s|%s",
                tillDate, lastUnpaidPeriod.getFromDate(), totalDueInterest, totalEMI, diff, adjustedEmi);
        if (signature.equals(lastEmiLogSignature)) {
            return; // skip duplicate
        }
        lastEmiLogSignature = signature;
        int pNum = periodNum(model, lastUnpaidPeriod.getFromDate());
        log("   calculateLastUnpaidEMI: tillDate=%s, lastUnpaidPeriod=P%d [%s->%s]",
                tillDate, pNum, lastUnpaidPeriod.getFromDate(), lastUnpaidPeriod.getDueDate());
        log("     totalDueInterest=%s, totalEMI=%s, totalDisbursed=%s, diff=%s",
                totalDueInterest, totalEMI, totalDisbursed, diff);
        log("     oldEMI=%s -> adjustedEMI=%s", lastUnpaidPeriod.getEmi(), adjustedEmi);
    }

    // --- InterestPeriod.addBalanceCorrectionAmount logging ---

    public static void logIPBalanceCorrectionAdd(InterestPeriod ip, Money additionalAmount, Money before) {
        if (additionalAmount != null && additionalAmount.isZero()) {
            return; // skip zero-amount noise
        }
        log("      IP[%s->%s].addBalanceCorrectionAmount: before=%s + %s = %s",
                ip.getFromDate(), ip.getDueDate(), before, additionalAmount, ip.getBalanceCorrectionAmount());
    }

    // --- lastOverdueBalanceChange tracking ---

    public static void logLastOverdueBalanceChangeSet(LocalDate oldValue, LocalDate newValue, String caller) {
        if ((oldValue == null && newValue != null) || (oldValue != null && !oldValue.equals(newValue))) {
            log("   !! lastOverdueBalanceChange CHANGED: %s -> %s (by %s)", oldValue, newValue, caller);
        }
    }

    // --- Reprocessing flow logging (AdvancedPaymentScheduleTransactionProcessor) ---

    public static void logReprocessStart(LocalDate targetDate, int transactionCount) {
        separator("REPROCESSING START");
        log("reprocessProgressiveLoanTransactions: targetDate=%s, operations=%d", targetDate, transactionCount);
    }

    public static void logReprocessTransactionStart(String txType, LocalDate txDate, Object amount, boolean reversed) {
        log("  >> processSingleTransaction: type=%s, date=%s, amount=%s, reversed=%s", txType, txDate, amount, reversed);
    }

    public static void logReprocessFinalRecalc(LocalDate targetDate) {
        log("  >> FINAL recalculateInterestForDate: targetDate=%s", targetDate);
    }

    public static void logReprocessEnd() {
        separator("REPROCESSING END");
    }

    // --- recalculateInterestForDate logging ---

    public static void logRecalcInterestForDate(LocalDate targetDate, boolean modelHasUpdates, String caller) {
        log("  recalculateInterestForDate[%s]: targetDate=%s, modelHasUpdates=%s", caller, targetDate, modelHasUpdates);
    }

    // --- processLatestTransaction logging ---

    public static void logProcessLatestTransaction(String txType, LocalDate txDate, Object amount) {
        log("  >> processLatestTransaction: type=%s, date=%s, amount=%s", txType, txDate, amount);
    }

    public static void resetEmiDedup() {
        lastEmiLogSignature = null;
    }

    // --- Init/Clear ---

    public static void clear() {
        lastEmiLogSignature = null;
        if (currentLogFile == null) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            currentLogFile = LOG_DIR + "fineract-2421-debug_" + timestamp + ".log";
            try (PrintWriter pw = new PrintWriter(new FileWriter(currentLogFile, false))) {
                pw.printf("=== FINERACT-2421 Debug Trace started at %s ===%n", LocalDateTime.now());
            } catch (Exception e) {
                // silently ignore
            }
        } else {
            separator("=== NEW REPROCESSING CYCLE ===");
        }
    }

    /**
     * Helper: find period number (1-based) by fromDate
     */
    public static int periodNum(ProgressiveLoanInterestScheduleModel model, LocalDate fromDate) {
        List<RepaymentPeriod> periods = model.repaymentPeriods();
        for (int i = 0; i < periods.size(); i++) {
            if (periods.get(i).getFromDate().equals(fromDate)) {
                return i + 1;
            }
        }
        return -1;
    }
}
