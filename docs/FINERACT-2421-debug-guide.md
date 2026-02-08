# FINERACT-2421: Stale Overdue Balance Corrections → Negative Loan Balance

## Коротко (TL;DR)

Після комбінації **MIR (передоплата) + backdated зміна ставки + скасування платежу + фінальний платіж**, P2 показувало `duePrincipal=41.67` замість `41.62`, P6 — `Balance of Loan = -0.05` замість `0.0`. Кредит не закривався.

### Єдиний дефект: overdue корекції "поглинають" платіжну корекцію payPrincipal

Overdue механізм додає **парні корекції** для нарахування відсотків на прострочений капітал:
- `+overduePrincipal` на `period.dueDate` (= `repaymentPeriodDueDate`) — позитивна, піднімає баланс
- `-aggregatedOverDue` на `currentDate` — негативна, обмежує ефект поточною датою

Коли `payPrincipal()` оплачує прострочений період, його власна корекція `-principalAmount` на `repaymentPeriodDueDate` потрапляє на **ту саму дату**, що й позитивна overdue корекція. Вони **взаємно скасовуються** замість того, щоб зменшити баланс:

```
Overdue positive: +10.76@Nov3  (на IP[Oct15→Nov3])
payPrincipal:     -10.76@Nov3  (на IP[Oct15→Nov3])
NET: 0.00 — баланс НЕ зменшився!
```

А негативна overdue корекція `-10.76@Nov10` залишається нетронутою. Результат: `outstandingLoanBalance` в P2+ = 10.76 замість 0 → завищений `calcDueInterest` → завищений `duePrincipal` → від'ємний Balance of Loan.

### Друга проблема: нескінченний цикл після reprocessing

Cleanup в `recalculateModelOverdueAmountsTillDate` на **транзієнтних** моделях (десеріалізованих з JSON через `extractModel()`) модифікував in-memory стан, але JSON в БД залишався незмінним → кожна наступна десеріалізація тригерила cleanup знову → **нескінченний цикл**.

### Попередня теорія "Stale Memo Cache" — СПРОСТОВАНА

**`InterestPeriod` МАЄ `@EqualsAndHashCode` від Lombok** (рядок 41), тому `hashCode()` залежить від УСІХ полів (крім `repaymentPeriod`). Memo коректно інвалідується при зміні `balanceCorrectionAmount`.

### Фактичне (БАГ) vs. Очікуване vs. Після фіксу (E2E тест C4625):

| Період | | Balance of loan | Principal due | Interest | Outstanding |
|--------|---------|----------------|---------------|----------|-------------|
| P2 | **БАГ** | 166.27 | **41.67** | 0.0 | **0.05** |
| P2 | **ФІКС** | **166.32** ✓ | **41.62** ✓ | **0.0** ✓ | **0.0** ✓ |
| P6 | **БАГ** | **-0.05** | 41.58 | 0.0 | 0.0 |
| P6 | **ФІКС** | **0.0** ✓ | **41.58** ✓ | **0.0** ✓ | **0.0** ✓ |

---

## Сценарій відтворення (E2E тест C4625)

```
Файл: fineract-e2e-tests-runner/src/test/resources/features/LoanInterestRateChange.feature
Рядок: 1701 (@TestRailId:C4625)
```

| Крок | Бізнес-дата | Дія | Ключова зміна стану |
|------|------------|-----|---------------------|
| 1 | 03 Oct 2025 | Видача 231.59 EUR, 35.99% річних, 6 місяців, DECLINING_BALANCE, EQUAL_INSTALLMENTS, DAILY | Новий кредит, EMI=42.75 |
| 2 | 15 Oct 2025 | MIR 220.83 EUR (NEXT_INSTALLMENT allocation, зворотний порядок: P6→P5→...→P1) | При EMI=42.75: P2-P6 повністю (42.75 кожен), P1 частково (paid=9.65). Після кроку 4 (EMI→41.58): P2-P6 paidP=41.58, P1 paidP=12.93, залишок P1=10.76 |
| 3 | 30 Oct 2025 | Repayment 11.04 EUR | Завершує P1. Після replay (крок 4): P=10.76, I=0.20 (решта interest покрита Interest Refund 1.85) |
| 4 | 30 Oct 2025 | Loan Reschedule: 35.99%→25.99% (rescheduleFromDate=04 Oct 2025, backdated) | EMI: 42.75→41.58; Interest Refund 1.85 (replayed); P1 interest: 6.95→2.05 |
| 5 | 06 Nov 2025 | CBR 0.04 EUR | totalDuePrincipal: 231.59→231.63; Loan Balance: 10.76→10.80 |
| 6 | 07 Nov 2025 | **Undo** платежу з кроку 3 | 11.04 скасовано (reverted=true, replayed=true) → P1 outstandingP=10.76, outstandingI=0.25; P2 interest=0.03 (overdue) |
| 7 | 10 Nov 2025 | Repayment 11.05 EUR | Loan Balance=0.0, CLOSED_OBLIGATIONS_MET |

**Після кроку 7 графік МАЄ показувати P6 Balance=0.0, але БАГ показує -0.05.**

### Проміжний стан після undo (крок 6, до фінального платежу)

| Nr | Date | Balance of loan | Principal due | Interest | Due | Paid | Outstanding |
|----|------|-----------------|---------------|----------|-----|------|-------------|
| 1 | 03 Nov 2025 | 207.9 | 23.69 | 2.1 | 25.79 | 14.78 | 11.01 |
| 2 | 03 Dec 2025 | 166.32 | 41.62 | 0.03 | 41.65 | 41.58 | 0.07 |
| 3 | 03 Jan 2026 | 124.74 | 41.58 | 0.0 | 41.58 | 41.58 | 0.0 |
| 4 | 03 Feb 2026 | 83.16 | 41.58 | 0.0 | 41.58 | 41.58 | 0.0 |
| 5 | 03 Mar 2026 | 41.58 | 41.58 | 0.0 | 41.58 | 41.58 | 0.0 |
| 6 | 03 Apr 2026 | 0.0 | 41.58 | 0.0 | 41.58 | 41.58 | 0.0 |
| **Total** | | | **231.63** | **2.13** | **233.76** | **222.68** | **11.08** |

**Ключове:** P2 interest=0.03 — це **легітимний** overdue interest (P1 прострочений 4 дні: Nov 3→Nov 7, 10.76 × 25.99% × 4/365 ≈ 0.03). P2 outstanding=0.07 = interest 0.03 + rounding 0.04 (від CBR credit).

---

## Детальний аналіз проблеми

### 1. Overdue корекції: "roll forward" механізм

Для кожної транзакції `processLatestTransaction` викликає `recalculateInterestForDate(txDate)` **перед** обробкою. Це в свою чергу викликає `recalculateModelOverdueAmountsTillDate()`, який знаходить прострочені періоди і додає парні корекції через `adjustOverduePrincipal()`:
```
+overduePrincipal  на  period.fromDate (або lastOverdueBalanceChange)  <-- ПОЗИТИВНА
-aggregatedOverDue на  currentDate (або period.dueDate)                <-- НЕГАТИВНА
```

**"Roll forward"**: Кожен наступний виклик `adjustOverduePrincipal` СКАСОВУЄ попередню негативну корекцію (додаючи `+overduePrincipal` на `lastOverdueBalanceChange`) і створює НОВУ негативну корекцію на новій даті. Результат — позитивна корекція залишається на **ПЕРШІЙ** даті (де вона була створена), а негативна **"пересувається"** вперед з кожним викликом.

**ВАЖЛИВО:** Позитивна корекція потрапляє в IP за адресою `P1.dueDate` (= `P2.fromDate`), а негативна — в IP поточної дати (всередині P2). Вони в РІЗНИХ InterestPeriod'ах.

### 2. Послідовність викликів (верифіковано з логу)

```
CBR (Nov 6) [лог рядки 1753-1770]: ПЕРША overdue перевірка
  recalculateModelOverdueAmountsTillDate: targetDate=Nov6, lastOverdueBalanceChange=null
  P1 outstandingP = 10.76
  adjustOverduePrincipal: lastOverdueBalanceChange=null → positiveDate=P2.fromDate(Nov3)
  +10.76@Nov3 → P1 IP3[Oct15→Nov3].balCorr: 0.00 → 10.76
  -10.76@Nov6 → P2 IP split [Nov3→Nov6].balCorr: 0.00 → -10.76
  lastOverdueBalanceChange: null → Nov6

  creditPrincipal 0.04 → P2 IP1[Nov3→Nov6].balCorr: -10.76 → -10.72

FINAL recalcInterest (Nov 7) [лог рядки 1797-1813]: ROLL FORWARD
  adjustOverduePrincipal: currentDate=Nov7, lastOverdueBalanceChange=Nov6
  +10.76@Nov6 → P2 IP1[Nov3→Nov6].balCorr: -10.72 + 10.76 = 0.04 (CBR credit залишається!)
  -10.76@Nov7 → P2 IP split [Nov6→Nov7].balCorr: 0.00 → -10.76
  lastOverdueBalanceChange: Nov6 → Nov7
  P2 calcDueInterest=0.03 (легітимний overdue interest) ← CORRECT STATE

Pre-repayment recalcInterest (Nov 10) [лог рядки 1837-1889]: ROLL FORWARD
  adjustOverduePrincipal: currentDate=Nov10, lastOverdueBalanceChange=Nov7
  +10.76@Nov7 → P2 IP2[Nov6→Nov7].balCorr: -10.76 + 10.76 = 0.00
  -10.76@Nov10 → P2 IP split [Nov7→Nov10].balCorr: 0.00 → -10.76
  lastOverdueBalanceChange: Nov7 → Nov10

  same-date guard БЛОКУЄ повторний виклик: currentDate=Nov10 == lastOverdueBalanceChange=Nov10
```

**Нет-результат після roll forward**: позитивна +10.76 залишається на P1 IP3[Oct15→Nov3], негативна -10.76 на P2 IP3[Nov7→Nov10].

### 3. Що відбувається в `payPrincipal` БЕЗ фіксу — ядро бага

```
handleRepayment(11.05):

  payPrincipal P1: addBalanceCorrection(Nov3, -10.76)
    → P1 IP3[Oct15→Nov3].balCorr: +10.76 + (-10.76) = 0.00
    ⚠ Корекція payPrincipal "поглинена" позитивною overdue! Баланс НЕ зменшився!

  payPrincipal P2: addBalanceCorrection(Nov10, -0.04)
    → P2 IP3[Nov7→Nov10].balCorr: -10.76 + (-0.04) = -10.80  ← ДОДАЄТЬСЯ до сталої overdue! ✗
```

**P2 IP1.outBal** розраховується як:
```
P1.IP3.outBal(10.76) + P1.IP3.balCorr(0.00) - P1.duePrincipal(23.69) + P1.paidPrincipal(23.69) = 10.76
```

Баланс 10.76 пропагується через всі IP P2, генеруючи calcDueInterest=0.28 і duePrincipal=41.67.

### 4. Що відбувається в `payPrincipal` З фіксом (v3) — верифіковано логом

```
handleRepayment(11.05) [лог рядки 1895-1910]:

  payPrincipal P1 [Oct3→Nov3], txDate=Nov10, amount=10.76:
    isPastDue=true, lastOverdueBalanceChange=Nov10, lastOverdueAmount=10.76

    CLEANUP крок 1: addBalanceCorrection(Nov10, +10.76)    ← cancel negative overdue
      → P2 IP3[Nov7→Nov10].balCorr: -10.76 + 10.76 = 0.00  ✓

    CLEANUP крок 2: addBalanceCorrection(Nov3, -10.76)     ← cancel positive overdue
      → P1 IP3[Oct15→Nov3].balCorr: +10.76 + (-10.76) = 0.00  ✓

    lastOverdueAmount=0, lastOverdueBalanceChange=null

    PAYMENT: addBalanceCorrection(Nov3, -10.76)            ← реальне зменшення балансу
      → P1 IP3[Oct15→Nov3].balCorr: 0.00 + (-10.76) = -10.76  ✓

  payPrincipal P2 [Nov3→Dec3], txDate=Nov10, amount=0.04:
    isPastDue=false (cleanup не тригериться, lastOverdueAmount вже 0)
    PAYMENT: addBalanceCorrection(Nov10, -0.04)
      → P2 IP3[Nov7→Nov10].balCorr: 0.00 + (-0.04) = -0.04  ✓ (тільки легітимний платіж!)
```

**Результат P2 IP1.outBal** з фіксом:
```
P1.IP3.outBal(10.76) + P1.IP3.balCorr(-10.76) - P1.duePrincipal(23.69) + P1.paidPrincipal(23.69) = 0.00  ✓
```

P2 IPs: outBal ≈ 0 → calcDueInterest ≈ 0 → duePrincipal = 41.62 ✓

### 5. Чому v2 фіксу (тільки cancel negative) не працювала

v2 скасовувала лише негативну overdue корекцію (`+10.76@Nov10`), але **не** позитивну. Потім `payPrincipal` додавав `-10.76@Nov3`, яка потрапляла на ту саму IP, де сиділа позитивна overdue `+10.76@Nov3`. Вони **взаємно скасовувалися**:

```
v2 cleanup:  +10.76@Nov10 → IP[Nov7→Nov10]: -10.76 → 0.00  ✓ (negative cancelled)
payPrincipal: -10.76@Nov3 → IP[Oct15→Nov3]: +10.76 → 0.00  ✗ (eaten by positive overdue!)

Результат: P1.IP3.balCorr = 0 замість -10.76
           P2.IP1.outBal = 10.76 замість 0
```

v3 додає другий крок cleanup: `-10.76@Nov3` (cancel positive overdue) **перед** корекцією payPrincipal. Тепер payPrincipal `-10.76@Nov3` реально зменшує баланс.

### 6. Механізм завищення `getDuePrincipal()` (БАГ, до фіксу)

```java
getDuePrincipal() = max(
    negativeToZero(EMI + totalCredited + futureUnrecognizedInterest - getDueInterest()),
    paidPrincipal
)
```

Для P2 (баг):
```
EMI = 41.58
totalCredited = 0.04 (creditedPrincipal від CBR)
futureUnrecognizedInterest = 0.05   <-- фантом від outBal=10.76

negativeToZero(41.58 + 0.04 + 0.05 - 0.00) = 41.67
paidPrincipal = 41.62
max(41.67, 41.62) = 41.67 <-- ЗАВИЩЕНО на 0.05
```

Для P2 (фікс):
```
futureUnrecognizedInterest = 0.00   <-- outBal=0 → немає фантомного interest
negativeToZero(41.58 + 0.04 + 0.00 - 0.00) = 41.62
max(41.62, 41.62) = 41.62  ✓
```

### 7. Нескінченний цикл в `calculateInterestRecalculationFutureOutstandingValue`

**Проблема (до фіксу):** cleanup в `recalculateModelOverdueAmountsTillDate` модифікував транзієнтні моделі з JSON.

1. `extractModel()` десеріалізує модель з JSON → `lastOverdueBalanceChange=Nov10`, `lastOverdueAmount=10.76`
2. `recalculateModelOverdueAmountsTillDate(Dec3)`: P1 fullyPaid → overdue=0 → zero guard → `hasChange=false`
3. Cleanup в `recalculateModelOverdueAmountsTillDate`: `+10.76@Nov10`, `lastOverdueBalanceChange=null`, `hasChange=true`
4. Наступний виклик `extractModel()` → та сама модель з JSON → cleanup знову → **нескінченний цикл**

**Рішення:** cleanup видалено з `recalculateModelOverdueAmountsTillDate`, перенесено в `payPrincipal` (де він виконується на основній моделі, одноразово, і результат зберігається в БД).

**Верифіковано логом** (рядки 1929-1955): після фіксу delinquency service викликає `recalculateModelOverdueAmountsTillDate` з `lastOverdueBalanceChange=null` → `hasChange=false` → жодного циклу.

### 8. Як завищений principal призводив до від'ємного Balance of Loan (БАГ)

```
Початок: 231.59
+credits: 231.59 + 0.04 = 231.63
Після P1: 231.63 - 23.69 = 207.94
Після P2: 207.94 - 41.67 = 166.27  <-- має бути 207.94 - 41.62 = 166.32
Після P3: 166.27 - 41.58 = 124.69
Після P4: 124.69 - 41.58 = 83.11
Після P5: 83.11 - 41.58 = 41.53
Після P6: 41.53 - 41.58 = -0.05   <-- ВІД'ЄМНЕ!

sum(principalDue) = 231.68 > totalDuePrincipal = 231.63 -> надлишок 0.05
```

---

## `balanceCorrectionAmount` — спільне поле

`InterestPeriod.balanceCorrectionAmount` — це **одне поле**, яке накопичує корекції з **різних джерел**:

1. **Платіжні корекції** — від `payPrincipal()` — **НЕГАТИВНІ** (`-principalAmount`)
2. **Кредитні корекції** — від `creditPrincipal()` — **ПОЗИТИВНІ**
3. **Overdue корекції** — від `adjustOverduePrincipal()` — парні `+X` / `-X`

**Немає способу відрізнити**, яка частина `balanceCorrectionAmount` прийшла від overdue коригувань vs. платежів. Тому для відстеження використовуються поля `lastOverdueBalanceChange` і `lastOverdueAmount` на `ProgressiveLoanInterestScheduleModel`.

---

## Захисти, що ускладнюють очищення

### Бар'єр 1: Guard в циклі `recalculateModelOverdueAmountsTillDate`

```java
Money overDuePrincipal = scheduleModel.zero();  // починає з 0
for (RepaymentPeriod processingPeriod : overdueInstallments) {
    if (!overDuePrincipal.isZero()) {           // <-- 0 == 0 -> FALSE
        adjustOverduePrincipal(...);            // ПРОПУСК
    }
    overDuePrincipal = processingPeriod.getOutstandingPrincipal();
}
```

Коли P1 вже сплачений: `overDuePrincipal` залишається 0 → `adjustOverduePrincipal` ніколи не викликається в циклі (тільки в "final adjust" після циклу).

### Бар'єр 2: Guard однієї дати в `adjustOverduePrincipal`

```java
if (!currentDate.equals(model.lastOverdueBalanceChange())) {
    // додати корекції
    return true;
}
return false;  // <-- ПРОПУСКАЄ якщо та сама дата
```

### Бар'єр 3: Zero guard в `adjustOverduePrincipal`

```java
if (overduePrincipal.isZero() && aggregatedOverDuePrincipal.isZero()) {
    return false;  // <-- нічого не робить для нульових overdue
}
```

Після погашення P1, "final adjust" викликає `adjustOverduePrincipal(currentDate, P2, 0, 0, model)` → zero guard повертає false → **ніяких коригувань не відбувається**, і overdue корекції залишаються.

### Бар'єр 4: Умовне оновлення `lastOverdueBalanceChange`

```java
if (aggregatedOverDuePrincipal.isGreaterThanZero()
        && (model.lastOverdueBalanceChange() == null
            || model.lastOverdueBalanceChange().isBefore(recalculatedTargetDate))) {
    scheduleModel.lastOverdueBalanceChange(recalculatedTargetDate);
}
// ТІЛЬКИ якщо aggregated > 0 І дата > попередня
```

Коли overdue = 0 (після погашення P1), дата НЕ оновлюється → попередні ненульові корекції залишаються.

---

## Архітектурні знахідки

### Model lifecycle

1. **`reprocessProgressiveLoanTransactions`** (рядок 231) створює **НОВИЙ** model через `generateInstallmentInterestScheduleModel`. Всі `balanceCorrectionAmount` = 0. `lastOverdueBalanceChange` = null. Кожен reprocessing починає з чистого листа.

2. **`extractModel()`** (рядок 93 `InterestScheduleModelRepositoryWrapperImpl.java`) десеріалізує модель з JSON через `fromJson()`. Створює **СВІЖІ** об'єкти з **УСІМА** серіалізованими полями, включаючи `lastOverdueBalanceChange`. Cleanup на цих транзієнтних моделях **НЕ зберігається** в БД.

3. **`deepCopy()`** (рядок 100 `ProgressiveLoanInterestScheduleModel.java`) копіює `RepaymentPeriod`'и та `InterestPeriod`'и (з їх `balanceCorrectionAmount`), але **НЕ** копіює `lastOverdueBalanceChange` та `lastOverdueAmount` — вони null в копії.

### Memo cache — працює коректно

`InterestPeriod` має `@EqualsAndHashCode(exclude = { "repaymentPeriod" })` (рядок 41). Коли `addBalanceCorrectionAmount()` замінює `balanceCorrectionAmount` новим `Money` об'єктом, `InterestPeriod.hashCode()` змінюється → `ArrayList.hashCode()` змінюється → Memo для `getCalculatedDueInterest()` інвалідується.

**Memo dependency chain:** `RepaymentPeriod.getCalculatedDueInterest()` (рядок 220) залежить від `{ previous, interestPeriods, futureUnrecognizedInterest, isInterestMoved, totalDisbursedAmount, fixedInterest, reAged }`. Зміна будь-якого IP через `addBalanceCorrectionAmount()` інвалідує цей Memo.

### Nested reprocessing

`reprocessProgressiveLoanTransactions` може бути вкладеним. Кожен START створює нову модель. `DebugFileLogger.clear()` очищує in-memory буфер, але файловий лог зберігає ВСЕ.

### Хто викликає `recalculateInterestForDate` поза reprocessing

1. **`getSavedModel()`** (рядок 120 `InterestScheduleModelRepositoryWrapperImpl.java`): десеріалізує + `recalculateInterestForDate(businessDate, ctx)` з `updateInstallments=true`
2. **`calculateInterestRecalculationFutureOutstandingValue()`** (рядок 65 `ProgressivePossibleNextRepaymentCalculationServiceImpl.java`): десеріалізує + `recalculateInterestForDate(nextPaymentDueDate, ctx, false)` з `updateInstallments=false` — **ТУТ був нескінченний цикл**

---

## Рішення v3: cleanup ОБОХ overdue корекцій в `payPrincipal` (замінений на v5)

### Підхід

Перенести cleanup з `recalculateModelOverdueAmountsTillDate` в `payPrincipal`, де він:
- Виконується на **ОСНОВНІЙ** моделі (не на транзієнтній з JSON)
- Скасовує **ОБИДВІ** overdue корекції (позитивну + негативну) **ПЕРЕД** основною `addBalanceCorrection`
- Виконується **ОДНОРАЗОВО** під час обробки платежу
- Результат зберігається в БД → десеріалізація НЕ тригерить cleanup

### Зміни коду

**1. `payPrincipal` — cleanup ОБОХ overdue корекцій ПЕРЕД основною `addBalanceCorrection`:**

```java
repaymentPeriod.ifPresent(rp -> rp.addPaidPrincipalAmount(principalAmount));
// Reverse BOTH overdue corrections when paying a past-due period.
// The overdue mechanism adds paired corrections:
//   +overduePrincipal at period.dueDate (= repaymentPeriodDueDate) — raises balance for overdue interest
//   -overduePrincipal at lastOverdueBalanceChange — limits the effect to current date
// Without cleanup, payPrincipal's own correction (-principal@dueDate) would cancel the positive overdue
// correction instead of actually reducing the balance, leaving outstandingLoanBalance inflated in P2+.
boolean isPastDue = DateUtils.isBefore(repaymentPeriodDueDate, transactionDate);
if (isPastDue && scheduleModel.lastOverdueBalanceChange() != null
        && scheduleModel.lastOverdueAmount() != null
        && scheduleModel.lastOverdueAmount().isGreaterThanZero()) {
    // Cancel negative overdue correction: +amount at lastOverdueBalanceChange
    addBalanceCorrection(scheduleModel, scheduleModel.lastOverdueBalanceChange(), scheduleModel.lastOverdueAmount());
    // Cancel positive overdue correction: -amount at repaymentPeriodDueDate (where the original positive sits)
    addBalanceCorrection(scheduleModel, repaymentPeriodDueDate, scheduleModel.lastOverdueAmount().negated());
    scheduleModel.lastOverdueAmount(scheduleModel.zero());
    scheduleModel.lastOverdueBalanceChange(null);
}
LocalDate balanceCorrectionDate = isPastDue ? repaymentPeriodDueDate : transactionDate;
addBalanceCorrection(scheduleModel, balanceCorrectionDate, principalAmount.negated());
```

**2. `recalculateModelOverdueAmountsTillDate` — ВИДАЛИТИ cleanup:**

Cleanup в `recalculateModelOverdueAmountsTillDate` більше не потрібен і спричиняв нескінченний цикл на транзієнтних моделях.

**3. `adjustOverduePrincipal` — ЗАЛИШИТИ без змін:**
- Zero guard (рядки 949-951): запобігає нульовим корекціям
- `lastOverdueAmount` tracking (рядок 978): потрібен для cleanup в `payPrincipal`

### Чому це працює

1. **Cancel BOTH overdue corrections**: cleanup знімає і позитивну (`-10.76@Nov3`), і негативну (`+10.76@Nov10`) overdue корекції. Після cleanup `P1.IP3.balCorr=0` і `P2.IP3.balCorr=0` — модель повертається до стану "до overdue".

2. **payPrincipal correction реально зменшує баланс**: `-10.76@Nov3` тепер потрапляє на IP з `balCorr=0` (а не з `+10.76` від overdue), тому IP3.balCorr стає `-10.76`. Це забезпечує `P2.IP1.outBal = 10.76 + (-10.76) = 0.00` ✓

3. **Cleanup ПЕРЕД deep copy**: `addBalanceCorrection` всередині cleanup викликає `calculateLastUnpaidRepaymentPeriodEMI` → deep copy. Deep copy вже НЕ має overdue корекцій → `futureUnrecognizedInterest=0` → `duePrincipal=41.62` ✓

4. **Немає нескінченного циклу**: cleanup видалений з `recalculateModelOverdueAmountsTillDate` → транзієнтні моделі з JSON не модифікуються → немає `hasChange=true` → немає циклу ✓

5. **Результат зберігається в БД**: cleanup відбувається на основній моделі під час reprocessing. Після reprocessing модель зберігається з `lastOverdueBalanceChange=null` і `lastOverdueAmount=zero` → десеріалізація з JSON НЕ тригерить cleanup ✓

### Еволюція фіксу

| Версія | Що робить | Результат |
|--------|-----------|-----------|
| v1 | Cleanup в `recalculateModelOverdueAmountsTillDate` | Нескінченний цикл на транзієнтних JSON-моделях |
| v2 | Cleanup в `payPrincipal` — тільки cancel negative (`+amount@lastOBC`) | P2 outBal=10.76 (positive overdue "поглинає" payPrincipal correction), duePrincipal=41.67 |
| v3 | Cleanup в `payPrincipal` — cancel BOTH (`+amount@lastOBC`, `-aggregate@dueDate`) | C4625 ✓, але **C4626 status 700** (overcancellation при кількох overdue періодах) |
| v4 | Map<LocalDate,Money> `overduePositiveCorrections` на моделі для per-period cancel | **FAILED**: Map `@JsonExclude` → втрачається при серіалізації між reprocessing і processLatestTransaction |
| v5 | Compute per-period amount з `outstandingPrincipal` at call time | **FAILED**: cleanup P1 тригерить EMI recalc → P2.outstandingP змінюється (49.63→49.78) → overcancellation 0.15 → overpaid |
| v6 | `overdueBalanceCorrectionAmount` поле на `RepaymentPeriod` (серіалізується, guard `overdueAlreadyApplied`) | C4625 ✓, **C4626 status 700** (cancelling overdue removes legitimate interest) |
| v7 | Видалити `negativeToZero` з IP propagation, додати для interest base amount, прибрати overdue cleanup з `payPrincipal` | C4625 ✗ (outBal P2 = 10.76, duePrincipal = 41.67), Loan:8491 ✓ |
| v8 | v7 + cleanup overdue корекцій в `recalculateModelOverdueAmountsTillDate` + post-tx recalcInterest + updateObligationsMet | C4625 ✓, Loan:8491 ✓, **C4201 ✗** (extra recalc під час reprocessing) |
| v8.1 | v8 + `isReprocessing` guard: post-tx recalc тільки для standalone `processLatestTransaction` | C4625 ✓, Loan:8491 ✓, **C4201 ✗** (cleanup в pre-tx recalc, не post-tx) |
| v8.2 | v8.1 + `allowOverdueCleanup` параметр: cleanup дозволений ТІЛЬКИ у final recalc та post-tx standalone | C4625 ✓, Loan:8491 ✓, C4201 ✓, **C3918 ✗** (eating effect робить `aggregatedOverDuePrincipal=0` хибно) |
| v8.3 | v8.2 + `allMatch(isFullyPaid)` замість `aggregatedOverDuePrincipal.isZero()`: cleanup тільки коли ВСІ overdue періоди genuinely fully paid | C4625 ✓, Loan:8491 ✓, C4201 ✓, C3918 ✓, **Feature:1116 ✗** (P1 single overdue + fully paid → cleanup тригериться, але P2-P6 потребують overdue interest) |
| v8.4 | v8.3 + `allMatch(isFullyPaid)` на ВСІХ періодах (не лише overdue) | Feature:1116 ✓, **C4625 ✗** (chicken-and-egg: P2 `isFullyPaid=false` через eating, блокує cleanup який потрібен щоб P2 стала fully paid) |
| v8.5 | v8.4 + перевірка тільки ОСТАННЬОГО періоду `isFullyPaid`: proxy для закриття кредиту | Feature:1116 ✓, **C4625 ✗** (MIR advance payment робить P6 fully paid навіть коли P1 ще overdue → premature cleanup видаляє overdue interest 0.03 з P2) |
| **v8.6** | **v8.5 + КОМБІНАЦІЯ `lastPeriodFullyPaid AND allOverduePeriodsFullyPaid`: кредит закривається І overdue вирішено** | **C4625 ✓, Loan:8491 ✓, C4201 ✓, C3918 ✓, Feature:1116 — тестується** |

---

## Верифікований фінальний стан (з логу `fineract-2421-debug_20260208_024931.log`)

### Модель після фінального платежу (рядки 1911-1929)

| Period | outBal | balCorr (key IPs) | calcDueInterest | duePrincipal | paidP | outstandingP | fullyPaid |
|--------|--------|-------------------|-----------------|-------------|-------|-------------|-----------|
| P1 | 0.00 | IP3: **-10.76** | 2.10 | 23.69 | 23.69 | 0.00 | true |
| P2 | 0.00 | IP1: 0.04, IP3: **-0.04** | 0.00 | **41.62** ✓ | 41.62 | 0.00 | true |
| P3 | 0.00 | — | 0.00 | 41.58 | 41.58 | 0.00 | true |
| P4 | 0.00 | — | 0.00 | 41.58 | 41.58 | 0.00 | true |
| P5 | 0.00 | — | 0.00 | 41.58 | 41.58 | 0.00 | true |
| P6 | 0.00 | — | 0.00 | 41.58 | 41.58 | 0.00 | true |
| **Total** | | | **2.10** | **231.63** ✓ | **231.63** | **0.00** ✓ | **all true** |

`lastOverdueBalanceChange=null`, `totalDuePrincipal=231.63`, `totalPaidPrincipal=231.63` → CLOSED_OBLIGATIONS_MET ✓

### Графік після фіксу (крок 7)

| Nr | Date | Paid date | Balance of loan | Principal due | Interest | Due | Paid | In advance | Late | Outstanding |
|----|------|-----------|-----------------|---------------|----------|-----|------|------------|------|-------------|
| 1 | 03 Nov 2025 | 10 Nov 2025 | 207.9 | 23.69 | 2.1 | 25.79 | 25.79 | 14.78 | 11.01 | 0.0 |
| 2 | 03 Dec 2025 | 10 Nov 2025 | 166.32 | 41.62 | 0.0 | 41.62 | 41.62 | 41.62 | 0.0 | 0.0 |
| 3 | 03 Jan 2026 | 15 Oct 2025 | 124.74 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| 4 | 03 Feb 2026 | 15 Oct 2025 | 83.16 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| 5 | 03 Mar 2026 | 15 Oct 2025 | 41.58 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| 6 | 03 Apr 2026 | 15 Oct 2025 | 0.0 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| **Total** | | | | **231.63** | **2.1** | **233.73** | **233.73** | **222.72** | **11.01** | **0.0** |

---

## Регресія v3: status 700 на C4626 (кілька overdue періодів)

### Проблема

v3 працює для C4625 (один overdue період P1), але **ламає C4626** (два overdue періоди P1 і P2). Очікуваний статус 600 (CLOSED_OBLIGATIONS_MET), фактичний — 700 (OVERPAID).

### Кореневий дефект v3

v3 скасовує позитивну overdue корекцію використовуючи **АГРЕГОВАНИЙ** `lastOverdueAmount` на **ОДНІЙ** даті (`repaymentPeriodDueDate` першого оплачуваного періоду):

```
Overdue corrections for 2 periods (P1, P2):
  +49.63@P1.dueDate (Jan 16)   — positive overdue for P1
  +49.63@P2.dueDate (Feb 1)    — positive overdue for P2
  -99.26@currentDate (Feb 1)   — AGGREGATE negative

v3 cancel positive: -99.26@P1.dueDate (Jan 16)  ← OVERCANCELLATION!
  → IP[...→Jan16].balCorr = +49.63 + (-99.26) = -49.63  ← ЗАЙВИЙ -49.63!
```

Позитивні overdue корекції розкидані по **РІЗНИХ** датах (P1.dueDate, P2.dueDate), а v3 скасовує агреговану суму на одній даті → overcancellation на P1.dueDate → занижений баланс → менший interest → overpaid.

---

## v4: Per-period Map (overduePositiveCorrections) — FAILED

### Підхід

Додано `Map<LocalDate, Money> overduePositiveCorrections` на `ProgressiveLoanInterestScheduleModel` з `@JsonExclude` для трекінгу per-period позитивних overdue сум:

- `adjustOverduePrincipal` записує: `model.addOverduePositiveCorrection(positiveDate, overduePrincipal)`
- `payPrincipal` скасовує per-period positive з map (точна сума на точну дату), плюс негативний aggregate один раз

### Чому не працює: серіалізація моделі

**Root cause:** Map позначений `@JsonExclude` → втрачається при серіалізації/десеріалізації JSON.

Ланцюжок подій:

```
1. reprocessLoanTransactions (LoanTransactionProcessingServiceImpl:108-125)
   → Створює НОВУ модель, обробляє ВСІ транзакції
   → adjustOverduePrincipal заповнює Map
   → writeInterestScheduleModel(loan, model) — ЗБЕРІГАЄ модель в JSON (рядок 118)
   → Map (@JsonExclude) НЕ потрапляє в JSON

2. processLatestTransactionProgressiveInterestRecalculation (рядок 204-224)
   → modelRepository.getSavedModel(loan, txDate) — ЗАВАНТАЖУЄ модель з JSON (рядок 206)
   → НОВА інстанція моделі, де:
      ✓ lastOverdueBalanceChange = 2025-02-01 (серіалізується, виживає)
      ✓ lastOverdueAmount = 99.26 (серіалізується, виживає)
      ✗ overduePositiveCorrections = {} (ПОРОЖНЯ! @JsonExclude → втрачена)

3. recalculateModelOverdueAmountsTillDate (перед обробкою REPAYMENT)
   → same-date guard: currentDate(Feb1) == lastOverdueBalanceChange(Feb1)
   → adjustOverduePrincipal ЗАБЛОКОВАНО → Map НЕ перезаповнюється

4. payPrincipal
   → Шукає per-period amount в Map → Map ПОРОЖНЯ
   → cancel positive ПРОПУЩЕНО
   → payment correction "поглинена" нескасованою позитивною overdue
   → installments obligations not met
```

### Model Lifecycle (критично для розуміння)

```
┌─────────────────────────────────┐
│ reprocessLoanTransactions       │
│ ┌─────────────────────────────┐ │
│ │ NEW model (all zeros)       │ │
│ │ Process ALL transactions    │ │
│ │ Map filled by adjustOverdue │ │
│ └──────────────┬──────────────┘ │
│                │                │
│    writeInterestScheduleModel   │
│    (saves to JSON in DB)        │
│    Map @JsonExclude → LOST      │
│    lastOverdueBalanceChange ✓   │
│    lastOverdueAmount ✓          │
└───────────────┬─────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│ processLatestTransaction        │
│ ┌─────────────────────────────┐ │
│ │ getSavedModel (from JSON)   │ │
│ │ Map = {} (LOST)             │ │
│ │ lastOverdueBalanceChange ✓  │ │
│ │ lastOverdueAmount ✓         │ │
│ └──────────────┬──────────────┘ │
│                │                │
│  same-date guard blocks re-run  │
│  → Map stays empty              │
│  → payPrincipal can't cancel    │
└─────────────────────────────────┘
```

---

## v5: Compute per-period amount з outstandingPrincipal — FAILED: status 700

### Ідея

Замість Map (яка не виживає серіалізацію), **обчислюємо** per-period overdue суму в момент `payPrincipal`:

```
prePaymentOutstanding = outstandingPrincipal + principalAmount
```

`addPaidPrincipalAmount` вже викликано ДО cleanup → `outstandingPrincipal` зменшено → додаємо назад `principalAmount`.

### Кореневий дефект v5: EMI recalculation між cleanup P1 і cancel positive P2

Cleanup P1 викликає `addBalanceCorrection` → тригерить `calculateLastUnpaidRepaymentPeriodEMI` → EMI перераховується → `P2.duePrincipal` змінюється з 49.63 на 49.78.

```
Overdue corrections (applied during reprocessing with ORIGINAL EMI):
  +49.63@Jan16 (P1.dueDate)    — positive overdue for P1 (= P1.outstandingP at calc time)
  +49.63@Jan31 (P2.dueDate)    — positive overdue for P2 (= P2.outstandingP at calc time)
  -99.26@Feb1  (currentDate)   — aggregate negative

payPrincipal P1 (amount=49.63, dueDate=Jan16):
  prePaymentOutstanding = P1.outstandingP(0) + 49.63 = 49.63
  Cancel positive: -49.63@Jan16  → IP[→Jan16].balCorr: +49.63 → 0.00  ✓
  Cancel negative: +99.26@Feb1   → IP[→Feb1].balCorr: -99.26 → 0.00   ✓
  ↓↓↓ addBalanceCorrection triggers calculateLastUnpaidRepaymentPeriodEMI ↓↓↓
  ↓↓↓ P2.duePrincipal: 49.63 → 49.78 (EMI recalculated!) ↓↓↓
  Payment: -49.63@Jan16         → IP[→Jan16].balCorr: 0.00 → -49.63   ✓

payPrincipal P2 (amount=49.78, dueDate=Jan31):                     ← 49.78, NOT 49.63!
  prePaymentOutstanding = P2.outstandingP(0) + 49.78 = 49.78       ← WRONG! Actual overdue = 49.63
  Cancel positive: -49.78@Jan31  → IP[→Jan31].balCorr: +49.63 + (-49.78) = -0.15  ✗ OVERCANCELLATION!
  Payment: -49.78@Jan31         → IP[→Jan31].balCorr: -0.15 + (-49.78) = -49.93   ✗ should be -49.78
```

**Різниця 0.15** (= 49.78 - 49.63) — overcancellation → занижений баланс → менший interest → overpaid (status 700).

### Верифіковано логом `fineract-2421-debug_20260208_144924.log`

```
[рядок 669] P1 IP2[Jan1→Jan16]: balCorr=EUR 49.63   — overdue positive P1
[рядок 671] P2 IP1[Jan16→Jan31]: balCorr=EUR 49.63  — overdue positive P2
[рядок 673] P3 IP1[Jan31→Feb1]: balCorr=EUR -99.26  — overdue negative aggregate

[рядок 734] cancel positive P1: IP[Jan1→Jan16].balCorr: 49.63 + (-49.63) = 0.00  ✓
[рядок 739] cancel negative:    IP[Jan31→Feb1].balCorr: -99.26 + 99.26 = 0.00    ✓
[рядок 744] payment P1:         IP[Jan1→Jan16].balCorr: 0.00 + (-49.63) = -49.63 ✓

[рядок 756] cancel positive P2: IP[Jan16→Jan31].balCorr: 49.63 + (-49.78) = -0.15  ✗ OVERCANCELLATION!
[рядок 761] payment P2:         IP[Jan16→Jan31].balCorr: -0.15 + (-49.78) = -49.93 ✗
```

### Ключовий висновок

`outstandingPrincipal` at payment time ≠ overdue positive correction amount, якщо EMI змінився між розрахунком overdue і обробкою платежу. Потрібно зберігати точну суму overdue корекції окремо, в полі що серіалізується І не залежить від EMI.

---

## v6: overdueBalanceCorrectionAmount на RepaymentPeriod — FAILED: status 700

### Ідея

Зберігати точну per-period overdue суму **на самому RepaymentPeriod** як серіалізоване поле. Це поле:
- Встановлюється в `recalculateModelOverdueAmountsTillDate` (= `outstandingPrincipal` на момент розрахунку overdue)
- Читається в `payPrincipal` для точного cancel positive
- Серіалізується з моделлю → виживає JSON round-trips
- НЕ змінюється при EMI перерахунку (встановлюється одноразово)

### Зміни коду

**1. `RepaymentPeriod.java` — нове поле:**

```java
@Getter
@Setter
private Money overdueBalanceCorrectionAmount;
```

Поле додано в `copy()` та `copyWithoutPaidAmounts()`:
```java
newRepaymentPeriod.setOverdueBalanceCorrectionAmount(repaymentPeriod.getOverdueBalanceCorrectionAmount());
```

**2. `recalculateModelOverdueAmountsTillDate` — встановлення поля:**

```java
// When same-date guard will block adjustOverduePrincipal, the serialized
// overdueBalanceCorrectionAmount values are already correct and must not be overwritten.
boolean overdueAlreadyApplied = recalculatedTargetDate.equals(scheduleModel.lastOverdueBalanceChange());

for (RepaymentPeriod processingPeriod : overdueInstallments) {
    // ... adjustOverduePrincipal ...
    overDuePrincipal = processingPeriod.getOutstandingPrincipal();
    if (!overdueAlreadyApplied) {
        processingPeriod.setOverdueBalanceCorrectionAmount(overDuePrincipal);
    }
    // ...
}
```

**3. `payPrincipal` — читання поля замість обчислення:**

```java
boolean isPastDue = DateUtils.isBefore(repaymentPeriodDueDate, transactionDate);
if (isPastDue && scheduleModel.lastOverdueBalanceChange() != null) {
    // Cancel the positive overdue correction using the exact amount
    // recorded during overdue recalculation (not current outstandingPrincipal).
    Money overduePositive = repaymentPeriod.map(RepaymentPeriod::getOverdueBalanceCorrectionAmount).orElse(null);
    if (overduePositive != null && overduePositive.isGreaterThanZero()) {
        addBalanceCorrection(scheduleModel, repaymentPeriodDueDate, overduePositive.negated());
        repaymentPeriod.ifPresent(rp -> rp.setOverdueBalanceCorrectionAmount(scheduleModel.zero()));
    }
    // Cancel the negative aggregate correction (once, for the first past-due payPrincipal)
    if (scheduleModel.lastOverdueAmount() != null && scheduleModel.lastOverdueAmount().isGreaterThanZero()) {
        addBalanceCorrection(scheduleModel, scheduleModel.lastOverdueBalanceChange(), scheduleModel.lastOverdueAmount());
        scheduleModel.lastOverdueAmount(scheduleModel.zero());
    }
}
```

### Guard: `overdueAlreadyApplied`

Критичний guard для коректності:

```
Reprocessing:
  1. Disbursement Jan1 → overdue recalc (lastOBC=null, NOT already applied)
     → P1.overdueBalanceCorrectionAmount = 49.63  ← SET (correct at this EMI)
     → P2.overdueBalanceCorrectionAmount = 49.63  ← SET

  2. Disbursement Feb1 → overdue recalc (lastOBC=Feb1, already applied!)
     → overdueAlreadyApplied=true → fields NOT overwritten ✓
     → P2.overdueBalanceCorrectionAmount stays 49.63 (not corrupted to 49.78)

  3. Model saved to JSON → field 49.63 serialized ✓

processLatestTransaction REPAYMENT:
  Model loaded from JSON → P2.overdueBalanceCorrectionAmount = 49.63 ✓
  Overdue recalc (overdueAlreadyApplied=true) → fields NOT overwritten ✓
  payPrincipal P2: overduePositive = 49.63 ← EXACT match with overdue correction ✓
```

Без цього guard'а: цикл overdue перезаписав би поле на `P2.getOutstandingPrincipal()` = 49.78 (поточний, з новим EMI), а overdue корекція = 49.63 (старий EMI). Різниця 0.15 → overcancellation.

### Чому `lastOverdueBalanceChange` НЕ обнуляється

При кількох overdue періодах `payPrincipal` викликається ПОСЛІДОВНО для P1, потім P2. Якщо P1 обнулить `lastOverdueBalanceChange`:
- P2 не знатиме, що overdue корекції існують
- P2 пропустить cancel positive → баланс залишиться завищеним

`lastOverdueBalanceChange` ≠ null є **сигналом** "overdue корекції присутні в моделі". Cancel negative (через `lastOverdueAmount > 0` guard) виконується лише один раз.

### Приклад для C4626 (два overdue періоди, верифікація v6)

```
Overdue corrections (at overdue calc time, EMI gives P1.outP=49.63, P2.outP=49.63):
  +49.63@Jan16 (P1.dueDate)    — positive overdue for P1
  +49.63@Jan31 (P2.dueDate)    — positive overdue for P2
  -99.26@Feb1  (currentDate)   — aggregate negative

Fields set: P1.overdueBalanceCorrectionAmount=49.63, P2=49.63

... Later, disbursement changes EMI: P2.duePrincipal 49.63→49.78 ...
... overdueAlreadyApplied=true → fields preserved at 49.63 ...

payPrincipal P1 (amount=49.63, dueDate=Jan16):
  overduePositive = P1.overdueBalanceCorrectionAmount = 49.63      ← EXACT
  Cancel positive: -49.63@Jan16  → IP[→Jan16].balCorr: +49.63 → 0.00  ✓
  Cancel negative: +99.26@Feb1   → IP[→Feb1].balCorr: -99.26 → 0.00   ✓ (one-time)
  Payment: -49.63@Jan16         → IP[→Jan16].balCorr: 0.00 → -49.63   ✓

payPrincipal P2 (amount=49.78, dueDate=Jan31):
  overduePositive = P2.overdueBalanceCorrectionAmount = 49.63      ← EXACT (not 49.78!)
  Cancel positive: -49.63@Jan31  → IP[→Jan31].balCorr: +49.63 → 0.00  ✓ (exact cancel!)
  Cancel negative: lastOverdueAmount=zero → SKIP                       ✓
  Payment: -49.78@Jan31         → IP[→Jan31].balCorr: 0.00 → -49.78   ✓ (correct!)
```

### Порівняння v5 vs v6 для P2

| Аспект | v5 (FAILED) | v6 (CURRENT) |
|--------|-------------|--------------|
| Cancel positive amount | `prePaymentOutstanding` = 49.**78** (current outstandingP) | `overdueBalanceCorrectionAmount` = 49.**63** (at overdue calc time) |
| IP.balCorr after cancel | 49.63 + (-49.78) = **-0.15** ✗ | 49.63 + (-49.63) = **0.00** ✓ |
| IP.balCorr after payment | -0.15 + (-49.78) = **-49.93** ✗ | 0.00 + (-49.78) = **-49.78** ✓ |
| Result | Overcancellation → overpaid | Exact cancel → correct balance |

---

## Регресія v6: status 700 на Loan:8491 (кілька overdue періодів з pay-off)

### Проблема

v6 має точну per-period суму (49.63) і скасовує overdue positive/negative парно. Але **сам факт скасування overdue корекцій** є фундаментально хибним підходом.

### Фундаментальний дефект v3-v6

**Усі версії v3-v6 мають одну і ту ж фундаментальну ваду**: вони скасовують overdue positive корекцію в `payPrincipal`, що ретроактивно змінює баланс для нарахування відсотків.

Розглянемо формулу пропагації балансу між періодами:

```
P(n+1).firstIP.outBal = P(n).lastIP.outBal + P(n).lastIP.balCorr + P(n).lastIP.disb + P(n).lastIP.capInc - P(n).duePrincipal + P(n).paidPrincipal
```

Коли період одночасно **прострочений** (overdue corrections присутні) і **оплачений** (payPrincipal відпрацював):

```
P1.lastIP.balCorr містить:
  +overduePrincipal  (overdue positive, додано adjustOverduePrincipal)
  -principalAmount   (payment correction, додано payPrincipal)

Якщо overduePrincipal ≈ principalAmount (як правило), то:
  NET balCorr ≈ 0  (вони природно скасовують одна одну!)

А в формулі пропагації:
  P2.start = P1.outBal + 0 - duePrincipal + paidPrincipal
           = P1.outBal + paidPrincipal - duePrincipal
```

Overdue positive і payment negative **природно** скасовують одна одну на тій самій даті. Потім `+paidPrincipal` компенсує `-duePrincipal`. Результат: P2 "бачить" правильний залишковий баланс P1, **ВКЛЮЧАЮЧИ** легітимний overdue interest.

**Що робить v6 (і v3-v5) — скасовує overdue positive ПЕРЕД payment:**

```
Після cancel positive: P1.lastIP.balCorr = -overduePrincipal (net negative!)
Після payment:         P1.lastIP.balCorr = -overduePrincipal - principalAmount

P2.start = P1.outBal + (-overduePrincipal - principalAmount) - duePrincipal + paidPrincipal
```

**NET NEGATIVE** balCorr ретроактивно зменшує P2 стартовий баланс → P2 "бачить" менший баланс → менший overdue interest → загальна сума відсотків менша → pay-off amount перевищує фактичний due → **OVERPAID (status 700)**.

### Числовий приклад (Loan:8491)

```
Без cleanup (оригінал, ПРАЦЮЄ для Loan:8491):
  P1.lastIP.balCorr@dueDate = +49.63 (overdue) + (-49.63) (payment) = 0.00
  P2.firstIP.outBal = 300 + 0 - 49.63 + 49.63 = 300  ← ПРАВИЛЬНО (overdue interest зберігається)

З v6 cleanup (ЗЛАМАНО):
  Cancel: P1.lastIP.balCorr@dueDate = +49.63 + (-49.63) = 0.00  (overdue removed)
  Payment: P1.lastIP.balCorr@dueDate = 0.00 + (-49.63) = -49.63  (net negative!)
  P2.firstIP.outBal = 300 + (-49.63) - 49.63 + 49.63 = 250.37  ← WRONG (overdue interest ~0.17 втрачено)
```

Втрата 0.17 overdue interest означає, що pay-off amount (розрахований ДО cleanup) на 0.17 більший ніж фактичний due (після cleanup) → OVERPAID.

---

## v7: Видалити negativeToZero з IP propagation — ЧАСТКОВО ПРАЦЮЄ

### Ключовий інсайт

v3-v6 намагалися "чистити" overdue корекції в `payPrincipal`. Але **overdue корекції не потребують очищення** — вони природно скасовуються payment корекціями на тій самій даті.

Справжня причина C4625 — зовсім інша: **`negativeToZero` clamping** в `InterestPeriod.updateOutstandingLoanBalance()` втрачає інформацію.

### Справжня причина C4625

Коли `calculateLastUnpaidRepaymentPeriodEMI` **роздуває EMI** останнього неоплаченого періоду (щоб `totalEMI = totalDisbursed + totalDueInterest`), `duePrincipal` може перевищити фактичний доступний баланс:

```
P(n).duePrincipal = max(EMI_inflated - interest, paidPrincipal)
                  = 41.67 > actualBalance = 41.62

Формула пропагації дає:
P(n+1).start = P(n).outBal + balCorr - 41.67 + paidPrincipal = -0.05  ← НЕГАТИВНЕ!
```

Оригінальний код робить `negativeToZero(-0.05) = 0`. Інформація про -0.05 **втрачається**. Наступний період починає з 0 замість -0.05, і ця "фантомна" різниця накопичується до останнього періоду, де Balance of Loan = -0.05.

### Підхід v7

Замість очищення overdue корекцій, виправити **справжню причину**:

1. **Дозволити від'ємні балансі в IP ланцюжку** — прибрати `negativeToZero` з `InterestPeriod.updateOutstandingLoanBalance()`. Від'ємний баланс — це проміжний артефакт розрахунку, він коректно поглинається далі в ланцюжку.

2. **Не генерувати від'ємний процент** — додати `negativeToZero` в `InterestPeriod.getCalculatedDueInterest()` для base amount (DECLINING_BALANCE). Навіть якщо IP outBal < 0, нарахований процент = 0.

3. **Не чистити overdue корекції** — видалити весь overdue cleanup блок з `payPrincipal` (v6 код). Overdue і payment корекції природно скасовують одна одну.

### Зміни коду

**1. `InterestPeriod.updateOutstandingLoanBalance()` — прибрати `negativeToZero`:**

```java
// БУЛО (обидві гілки):
this.outstandingLoanBalance = MathUtil.negativeToZero(previousInterestPeriod.getOutstandingLoanBalance()
        .plus(previousInterestPeriod.getDisbursementAmount(), getMc())
        // ... інші доданки ...
        , getMc());

// СТАЛО:
this.outstandingLoanBalance = previousInterestPeriod.getOutstandingLoanBalance()
        .plus(previousInterestPeriod.getDisbursementAmount(), getMc())
        // ... інші доданки ...
        ;
```

Тепер від'ємний проміжний баланс пропагується коректно через IP ланцюжок.

**2. `InterestPeriod.getCalculatedDueInterest(InterestMethod, long)` — захист від від'ємного проценту:**

```java
// БУЛО:
case DECLINING_BALANCE -> getOutstandingLoanBalance().getAmount();

// СТАЛО:
case DECLINING_BALANCE -> MathUtil.negativeToZero(getOutstandingLoanBalance().getAmount());
```

**3. `payPrincipal` — видалити весь overdue cleanup блок (v6):**

```java
// ВИДАЛЕНО: увесь блок від "Reverse overdue corrections..." до закриваючої "}"
// Залишено тільки:
repaymentPeriod.ifPresent(rp -> rp.addPaidPrincipalAmount(principalAmount));
boolean isPastDue = DateUtils.isBefore(repaymentPeriodDueDate, transactionDate);
// If it is paid late, we need to calculate with the period due date
LocalDate balanceCorrectionDate = isPastDue ? repaymentPeriodDueDate : transactionDate;
addBalanceCorrection(scheduleModel, balanceCorrectionDate, principalAmount.negated());
```

**4. `RepaymentPeriod` — видалити поле `overdueBalanceCorrectionAmount`:**

Поле, getter, setter, копіювання в `copy()` і `copyWithoutPaidAmounts()` — все видалено.

**5. `recalculateModelOverdueAmountsTillDate` — видалити v6-специфічний код:**

Видалено `overdueAlreadyApplied` guard та `setOverdueBalanceCorrectionAmount` виклики.

### Чому це працює

**Для C4625 (від'ємний баланс):**

```
БУЛО (з negativeToZero):
  P2.duePrincipal = 41.67 (inflated EMI)
  P3.start = negativeToZero(P2.outBal + balCorr - 41.67 + paidP) = negativeToZero(-0.05) = 0.00
  → phantom balance 0.05 → P6 Balance = -0.05 ✗

СТАЛО (без negativeToZero):
  P3.start = P2.outBal + balCorr - 41.67 + paidP = -0.05
  P3 interest = max(0, -0.05) × rate = 0.00  (clamped in getCalculatedDueInterest)
  → -0.05 коректно поглинається в подальших розрахунках → P6 Balance = 0.00 ✓
```

**Для Loan:8491 (overdue + pay-off):**

```
Overdue корекції НЕ скасовуються (немає cleanup):
  P1.lastIP.balCorr@dueDate = +49.63 (overdue) + (-49.63) (payment) = 0.00
  → Природне скасування, overdue interest зберігається ✓
  P2.firstIP.outBal = 300 + 0 - 49.63 + 49.63 = 300  ✓
  → Правильний баланс, правильні відсотки, status 600 ✓
```

### Захисні рівні

Від'ємний баланс на IP рівні **безпечний** завдяки трьом рівням захисту:

1. **`InterestPeriod.getCalculatedDueInterest()`** — base amount clamped до ≥ 0 → від'ємний процент неможливий
2. **`RepaymentPeriod.getOutstandingLoanBalance()`** — має свій `negativeToZero` для display → зовнішні callers (графік, UI) бачать ≥ 0
3. **IP-level outBal** — використовується тільки для IP chain propagation та interest calculation (обидва захищені)

### Часткова невдача v7: C4625 все ще не проходить

v7 коректно вирішує проблему **P3-P6 від'ємного балансу** (Balance of Loan = -0.05). Але **P2 outBal = 10.76** залишається завищеним, бо негативний баланс в подальших IP — це **НАСЛІДОК** завищеного P2, а не **ПРИЧИНА**.

**Причина**: overdue positive (+10.76@Nov3) і payment correction (-10.76@Nov3) на одному IP скасовуються → P1.lastIP.balCorr = 0 → P2.start = 10.76 (замість 0). Цю проблему v7 НЕ вирішує — вона просто дозволяє від'ємним балансам P3-P6 пропагуватися без clamping.

**Тестові результати v7:**
- **Loan:8491** (tranche, multi-overdue + pay-off): **PASS** ✓ — overdue та payment корекції природно скасовуються, pay-off розраховується динамічно → завжди збігається
- **C4625** (MIR + undo + fixed payment): **FAIL** ✗ — P2.duePrincipal = 41.67 (замість 41.62), outstandingP = 0.05

Фактичні значення (з debug логу):
```
P1.IP3[Oct15→Nov3]: balCorr=0.00 (overdue +10.76 скасована payment -10.76)
P2.IP1[Nov3→Nov6]: outBal=10.76 (ЗАВИЩЕНИЙ!)
P2.IP3[Nov7→Nov10]: balCorr=-10.80
P2.IP4[Nov10→Dec3]: outBal=0.00
P3-P6: outBal=-0.05 (negativeToZero removal працює коректно)
```

**Ключова різниця між тестами:** Loan:8491 розраховує pay-off суму динамічно (завжди збігається з due), C4625 має **фіксовану** суму платежу (11.05), яка не покриває завищений duePrincipal.

---

## v8/v8.1/v8.2/v8.3: Cleanup overdue корекцій після оплати — ЗАМІНЕНИЙ НА v9

### Ключовий інсайт: cleanup ПІСЛЯ payment vs. cleanup ПІД ЧАС payment

v3-v6 виконували cleanup в `payPrincipal` — **ПІД ЧАС** розподілу платежу. Це змінювало модель до того, як paidAmounts були зафіксовані для всіх періодів. Для pay-off (Loan:8491) pay-off сума розраховувалася З overdue inflation, а cleanup прибирав inflation → paid > due → OVERPAID.

v8 виконує cleanup в `recalculateModelOverdueAmountsTillDate` — **ПІСЛЯ** того, як платіж повністю оброблений і paidAmounts зафіксовані. `getDuePrincipal = max(calcDue, paid)` гарантує due ≥ paid для всіх періодів.

### Механізм

Після обробки всіх overdue періодів у циклі `recalculateModelOverdueAmountsTillDate`, якщо:
- `allowOverdueCleanup == true` (тільки на final recalc та post-tx standalone)
- `allMatch(RepaymentPeriod::isFullyPaid)` — ВСІ overdue періоди genuinely fully paid (principal AND interest)
- `lastOverdueAmount > 0` (overdue корекції присутні в моделі)
- `lastOverdueBalanceChange != null`

Виконується reversal:

```java
// Reverse the overdue positive (+overdueAmount at first overdue period's dueDate)
addBalanceCorrection(scheduleModel, positiveDate, overdueAmount.negated());
// Reverse the overdue negative (-overdueAmount at lastOverdueBalanceChange)
addBalanceCorrection(scheduleModel, negativeDate, overdueAmount);
// Reset tracking
scheduleModel.lastOverdueAmount(scheduleModel.zero());
scheduleModel.lastOverdueBalanceChange(null);
```

### Чому позитивна overdue корекція ЗАВЖДИ на `firstOverduePeriod.getDueDate()`

В `adjustOverduePrincipal`, позитивна корекція розміщується:
- **Перший виклик** (`lastOverdueBalanceChange == null`): на `currentInstallment.getFromDate()` = dueDate першого overdue періоду
- **Roll forward** (`lastOverdueBalanceChange != null`): на `lastOverdueBalanceChange` (стара дата негативної)

Roll forward **не рухає** оригінальну позитивну. Він додає `+A@oldNegDate` і `-A@newNegDate`. Стара негативна `-A@oldNegDate` скасовується з новою позитивною `+A@oldNegDate`. Нет:

```
Після першого виклику: +A@P1.dueDate, -A@Nov6
Після rollforward:     +A@P1.dueDate, (+A-A)@Nov6, -A@Nov10
NET:                   +A@P1.dueDate, -A@Nov10
```

Тому `overdueInstallmentsSortedByInstallmentNumber.get(0).getDueDate()` — коректна дата позитивної.

### Послідовність викликів (C4625)

```
1. Process MIR → recalcInterest(Oct15) → Oct15 < Nov3, no overdue
2. Process rate change → Oct30 < Nov3, no overdue
3. Process CBR → recalcInterest(Nov6) → Nov6 > Nov3:
   → adjustOverduePrincipal: +10.76@Nov3, -10.76@Nov6
4. Process final repayment → recalcInterest(Nov10) → Nov10 > Nov3:
   → adjustOverduePrincipal rollforward: +10.76@Nov6, -10.76@Nov10
   → handleRepayment → payPrincipal: -10.76@Nov3, paidP+=10.76
   (overdue positive +10.76@Nov3 та payment -10.76@Nov3 скасовуються)
5. FINAL recalcInterest(targetDate, allowOverdueCleanup=true) → targetDate > Nov3:
   → P1 fully paid → isFullyPaid=true → allOverduePeriodsFullyPaid=true
   → lastOverdueAmount=10.76, lastOverdueBalanceChange=Nov10
   → ★ CLEANUP TRIGGERS ★
   → reverse positive: addBalanceCorrection(Nov3, -10.76)
     → P1.IP3.balCorr: 0.00 + (-10.76) = -10.76  ← тепер реальне зменшення!
   → reverse negative: addBalanceCorrection(Nov10, +10.76)
     → P2.IP3.balCorr: -10.80 + 10.76 = -0.04  ← тільки CBR credit!
   → Model recalculated
   → P2.start = P1.IP3.outBal(10.76) + P1.IP3.balCorr(-10.76) - duePrincipal + paidPrincipal = 0.00  ✓
```

### Чому це безпечно для Loan:8491 (pay-off)

Після pay-off + cleanup:

```
Для кожного раніше overdue періоду P(n):
  1. calcDuePrincipal зменшується (overdue inflation прибрано)
  2. paidPrincipal залишається незмінним (зафіксований під час pay-off)
  3. getDuePrincipal = max(calcDuePrincipal↓, paidPrincipal) = paidPrincipal
  4. getDueInterest: paidPrincipal > calcDuePrincipal → if-branch → paidInterest
  5. outstandingPrincipal = getDuePrincipal - paidPrincipal = 0
  6. outstandingInterest = getDueInterest - paidInterest = 0
  → total due = total paid → CLOSED_OBLIGATIONS_MET (600)  ✓
```

`max(calcDue, paid)` — це "захисна мережа", яка гарантує `due ≥ paid` навіть коли модель змінюється після фіксації paidAmounts.

### Критична знахідка: cleanup НЕ тригериться в `processLatestTransaction`

Перша версія v8 мала cleanup тільки в `recalculateModelOverdueAmountsTillDate`. Тест все одно ПРОВАЛЮВАВСЯ з тими ж значеннями (P2.duePrincipal=41.67).

**Причина**: `processLatestTransaction` (рядок 480) викликає `recalcInterestForDate` тільки **ПЕРЕД** обробкою транзакції (рядок 488). Після `payPrincipal` жодного `recalcInterestForDate` НЕ викликається. Cleanup ніколи не тригериться на основній моделі.

```
processLatestTransaction flow:
  1. recalcInterestForDate(txDate)  ← P1 ще НЕ оплачений, aggregated > 0, NO cleanup
  2. handleRepayment → payPrincipal ← P1 стає оплаченим
  3. return                         ← НЕМАЄ recalcInterestForDate після платежу!
  4. Model saved to JSON            ← lastOverdueBalanceChange=Nov10 (не null!)

Потім delinquency service:
  5. extractModel() from JSON       ← СВІЖА модель з lastOverdueBalanceChange=Nov10
  6. recalcOverdue → cleanup        ← triggers на ТРАНЗІЄНТНІЙ моделі, не persist!
  7. extractModel() from JSON       ← знову та ж модель → cleanup знову → ЦИКЛ!
```

**Рішення**: додати `recalcInterestForDate` ПІСЛЯ switch block в `processLatestTransaction`:

```java
// After switch (line 510):
if (ctx instanceof ProgressiveTransactionCtx progressiveTransactionCtx) {
    recalculateInterestForDate(loanTransaction.getTransactionDate(), progressiveTransactionCtx);
    // ...
}
```

Тепер cleanup тригериться на основній моделі → `lastOverdueBalanceChange=null` зберігається в JSON → delinquency не тригерить цикл.

**Порівняння з `reprocessProgressiveLoanTransactions`**: рядок 294 має аналогічний `recalcInterestForDate(targetDate)` після обробки ВСІХ транзакцій. Для `processLatestTransaction` це був єдиний missing piece.

### Друга знахідка: `obligationsMetOnDate` не оновлюється після cleanup

Після додавання post-transaction `recalcInterestForDate`, числа стали правильними (P2.duePrincipal=41.62, outstanding=0.0), але тест все ще провалювався:

```
Actual:   [2, 30, 03 December 2025, null,            166.32, 41.62, ...]
Expected: [2, 30, 03 December 2025, 10 November 2025, 166.32, 41.62, ...]
```

**Причина**: `obligationsMetOnDate` (4-й параметр) = null. Під час `handleRepayment`, P2 мав outstanding=0.05 → `updateObligationsMet` НЕ встановив дату. Після cleanup outstanding=0.00, але `updateObligationsMet` більше не викликається.

**Рішення**: після post-transaction `recalcInterestForDate`, перевірити obligations для всіх installments:

```java
for (LoanRepaymentScheduleInstallment installment : progressiveTransactionCtx.getInstallments()) {
    installment.updateObligationsMet(progressiveTransactionCtx.getCurrency(), loanTransaction.getTransactionDate());
}
```

`updateObligationsMet` безпечний: він встановлює `obligationsMet=true` тільки коли `!obligationsMet && totalOutstanding==0`, і НЕ змінює вже-met installments (P3-P6).

### Третя знахідка: post-tx recalc ламає reprocessing (C4201 регресія)

v8 додав `recalculateInterestForDate` ПІСЛЯ switch block в `processLatestTransaction`. Але `processLatestTransaction` викликається з ДВОХ місць:

1. **Standalone** (з `processLatestTransactionProgressiveInterestRecalculation`): для обробки ОСТАННЬОЇ транзакції. Тут post-tx recalc **ПОТРІБНИЙ** — він тригерить cleanup.
2. **Reprocessing** (з `processSingleTransaction` в `reprocessProgressiveLoanTransactions`): для КОЖНОЇ транзакції. Тут post-tx recalc **ШКІДЛИВИЙ** — він додає проміжні перерахунки що змінюють баланси.

**Тест C4201** (@TestRailId:C4201, Loan.feature:8843):
- 102.47 EUR, 3 місяці, undo repayment + NSF fee + undo another repayment
- Undo Sep 26 repayment → reprocessing всіх транзакцій
- Під час reprocessing, post-tx recalc після кожної транзакції змінював баланси

```
Expected P2: [34.35, 34.28, 0.52, 0.0, 2.8, 37.6]  (balance, principal, interest, fees, penalties, due)
Actual P2:   [34.25, 34.38, 0.42, 0.0, 2.8, 37.6]

Diff: balance -0.10, principal +0.10, interest -0.10
      (менший баланс → менший interest → більше principal з того ж EMI)
```

**Перша спроба (v8.1)**: додати `isReprocessing` прапор до `ProgressiveTransactionCtx` і пропускати post-tx код під час reprocessing:

```java
// ProgressiveTransactionCtx.java:
@Setter
private boolean isReprocessing = false;

// reprocessProgressiveLoanTransactions (перед циклом):
ctx.setReprocessing(true);

// processLatestTransaction (guard):
if (ctx instanceof ProgressiveTransactionCtx progressiveTransactionCtx
        && !progressiveTransactionCtx.isReprocessing()) {
    recalculateInterestForDate(...);
    // updateObligationsMet...
}
```

`reprocessProgressiveLoanTransactions` вже має фінальний `recalculateInterestForDate(targetDate, ctx)` на рядку 294, який обробляє cleanup після обробки ВСІХ транзакцій.

**v8.1 НЕ допомогла**: ті самі значення P2: [34.25, 34.38, 0.42]. Проблема не в post-tx recalc.

### Четверта знахідка: cleanup тригериться в pre-tx recalc на проміжній даті (C4201 root cause)

Аналіз debug логу `fineract-2421-debug_20260208_223015.log` виявив справжню причину:

**Cleanup тригериться під час pre-tx** `recalcInterestForDate(Sep30)` для ACCRUAL_ACTIVITY транзакції (рядок 488 — `recalculateInterestForDate` **ПЕРЕД** switch block), а НЕ в post-tx recalc.

```
processLatestTransaction flow (для ACCRUAL_ACTIVITY@Sep30):
  1. recalcInterestForDate(Sep30)  ← PRE-TX recalc (рядок 489)
     → findPossiblyOverdueRepaymentPeriods(Sep30):
       P1 (dueDate=Sep21): Sep30 > Sep21 → OVERDUE ✓
       P2 (dueDate=Oct21): Sep30 < Oct21 → NOT overdue (not visible!)
     → P1 fully paid → aggregatedOverDuePrincipal = 0
     → lastOverdueAmount > 0 → ★ CLEANUP TRIGGERS ★
     → Removes overdue corrections for P1
  2. handle ACCRUAL_ACTIVITY (no effect on model)
  3. Post-tx block skipped (isReprocessing guard)
```

**Проблема**: `findPossiblyOverdueRepaymentPeriods` date-dependent — на Sep30 "бачить" тільки P1 (Sep21 < Sep30), але НЕ P2 (Oct21 > Sep30). P1 оплачена → aggregated=0 → cleanup вважає що overdue вирішено. Але пізніше, при FINAL recalc на Nov6 (бізнес-дата), P2 стає overdue (Oct21 < Nov6) і потребує P1's overdue corrections для коректного interest.

**Вплив на P2 interest**:
```
На develop (очікувано):
  P2.IP1.outBal = 102.47 (overdue positive + payment cancel → balance maintained)
  Перші 9 днів (Sep21→Sep30) при 102.47 → interest ≈ 0.52

Після передчасного cleanup (v8.1):
  P2.IP1.outBal = 68.64 (overdue positive видалена → balance знижений)
  Перші 9 днів (Sep21→Sep30) при 68.64 → interest ≈ 0.42

Різниця: 0.52 - 0.42 = 0.10 → balance -0.10, principal +0.10
```

Overdue corrections в C4201 представляють **легітимний** overdue interest — P1 прострочений Sep21→Sep30.

### v8.2: `allowOverdueCleanup` параметр

**Рішення**: замість того щоб блокувати ВЕСЬ post-tx recalc (isReprocessing), заблокувати ТІЛЬКИ cleanup в проміжних recalc'ах. Cleanup дозволений тільки у двох точках:

1. **Final recalc** в `reprocessProgressiveLoanTransactions` (рядок 295) — targetDate = businessDate, бачить ВСІ overdue periods
2. **Post-tx standalone** в `processLatestTransaction` (рядок 516) — після обробки останньої транзакції

Всі інші виклики (pre-tx recalc на рядку 489, delinquency service) використовують default `allowOverdueCleanup=false`.

**Зміни коду:**

**1. `EMICalculator.java` — 4-param default method:**

```java
default boolean recalculateModelOverdueAmountsTillDate(ProgressiveLoanInterestScheduleModel ctx,
        LocalDate targetDate, boolean prepayAttempt, boolean allowOverdueCleanup) {
    return recalculateModelOverdueAmountsTillDate(ctx, targetDate, prepayAttempt);
}
```

**2. `ProgressiveEMICalculator.java` — override з guard:**

```java
@Override
public boolean recalculateModelOverdueAmountsTillDate(final ProgressiveLoanInterestScheduleModel scheduleModel,
        final LocalDate targetDate, boolean prepayAttempt) {
    return recalculateModelOverdueAmountsTillDate(scheduleModel, targetDate, prepayAttempt, false);
}

@Override
public boolean recalculateModelOverdueAmountsTillDate(final ProgressiveLoanInterestScheduleModel scheduleModel,
        final LocalDate targetDate, boolean prepayAttempt, boolean allowOverdueCleanup) {
    // ... existing overdue loop logic ...

    // Cleanup guard: only at final recalc points
    if (allowOverdueCleanup
            && aggregatedOverDuePrincipal.isZero()
            && scheduleModel.lastOverdueAmount() != null
            && scheduleModel.lastOverdueAmount().isGreaterThanZero()
            && scheduleModel.lastOverdueBalanceChange() != null) {
        // ... reverse corrections ...
    }
}
```

**3. `AdvancedPaymentScheduleTransactionProcessor.java` — threading:**

```java
// recalculateInterestForDate 2-param → default allowOverdueCleanup=false (safe)
public void recalculateInterestForDate(LocalDate targetDate, ProgressiveTransactionCtx ctx) {
    recalculateInterestForDate(targetDate, ctx, true, false);
}

// 4-param з allowOverdueCleanup
public void recalculateInterestForDate(LocalDate targetDate, ProgressiveTransactionCtx ctx,
        boolean updateInstallments, boolean allowOverdueCleanup) {
    // ... calls emiCalculator.recalculateModelOverdueAmountsTillDate(
    //         ctx.getModel(), targetDate, ctx.isPrepayAttempt(), allowOverdueCleanup);
}
```

**4. Виклики:**

| Місце | Рядок | `allowOverdueCleanup` | Чому |
|-------|-------|-----------------------|------|
| Pre-tx recalc | 489 | `false` (2-param default) | Проміжна дата, може не бачити всі overdue periods |
| Final reprocess recalc | 295 | `true` | targetDate = businessDate, бачить ВСІ periods |
| Post-tx standalone | 516 | `true` | Після останньої транзакції, модель зберігається в JSON |

**Чому v8.2 вирішує C4201**: pre-tx `recalcInterestForDate(Sep30)` для ACCRUAL_ACTIVITY тепер НЕ тригерить cleanup (default `false`). Overdue corrections для P1 залишаються → P2 отримує коректний overdue interest 0.52 ✓

**Чому v8.2 зберігає C4625**: final recalc (рядок 295) та post-tx standalone (рядок 516) мають `allowOverdueCleanup=true`. На цих точках targetDate = бізнес-дата → `findPossiblyOverdueRepaymentPeriods` бачить ВСІ overdue periods → якщо всі оплачені → cleanup тригериться коректно ✓

**Чому v8.2 зберігає Loan:8491**: те саме що v8 — cleanup ПІСЛЯ payment + `max(calcDue, paid)` guard ✓

### П'ята знахідка: eating effect робить `aggregatedOverDuePrincipal=0` хибно (C3918 регресія)

**Тест C3918** (@TestRailId:C3918, LoanInterestRateChange.feature:234):
- 100 EUR, 6 місяців, rate change 7%→4%→1.15%, undo 2nd repayment
- Після undo: P1 fully paid, P2 overdue (dueDate=Mar1, businessDate=Apr1), P2 partially paid (16.65 з 16.88 due)

**Actual**: P2 balance=66.91, principal=16.66, interest=0.22
**Expected**: P2 balance=66.97, principal=16.60, interest=0.28

Аналіз debug логу `fineract-2421-debug_20260208_232644.log`:

```
FINAL recalcInterest (targetDate=Apr1, allowOverdueCleanup=true):
  findPossiblyOverdueRepaymentPeriods(Apr1):
    P1 (dueDate=Feb1): outstandingP=0.00, fullyPaid=true
    P2 (dueDate=Mar1): outstandingP=0.00, fullyPaid=false  ← KEY!

  overdueLoop: P1 skip (overDue=0), P2 skip (overDue=P1.outstandingP=0)
  aggregatedOverDuePrincipal = 0 + 0 = 0

  v8.2 condition:
    allowOverdueCleanup=true ✓
    aggregatedOverDuePrincipal.isZero() ✓  ← MISLEADING!
    lastOverdueAmount > 0 ✓
    → ★ CLEANUP TRIGGERS ★  ← ХИБНО!
```

**Дві проблеми:**

**1. `aggregatedOverDuePrincipal = 0` хибний через eating effect:**

Overdue positive +16.60@Mar1 і payPrincipal -16.60@Mar1 скасовують одна одну на тій самій IP → P2 `balCorr = 0` → balance не зменшився → `calcDuePrincipal = EMI(16.88) - interest(0.28) = 16.60 = paidPrincipal` → `outstandingPrincipal = 0`. Але P2 НЕ genuinely fully paid (outstanding interest = 0.23).

**2. `positiveDate` хибна для multi-overdue:**

```
positiveDate = overdueInstallments.get(0).getDueDate() = P1.dueDate = Feb1  ← WRONG!
Actual overdue positive was placed at P3.fromDate = Mar1 = P2.dueDate
```

Cleanup reversal -16.60@Feb1 потрапляє в IP[Jan1→Feb1] (P1), а не IP[Feb1→Mar1] (P2) де насправді сидить позитивна overdue корекція.

**Чому в C4625 `positiveDate` правильна, а тут ні:**
- C4625: один overdue період (P1). Positive goes to P2.fromDate = P1.dueDate = Nov3. `overdueInstallments.get(0).getDueDate() = Nov3`. Збігається!
- C3918: два overdue періоди (P1, P2). P1 fully paid (outstandingP=0), skip. Positive goes to P3.fromDate = P2.dueDate = Mar1. `overdueInstallments.get(0).getDueDate() = P1.dueDate = Feb1`. НЕ збігається!

**Легітимний overdue interest:**
```
P2 interest = 0.28 — включає overdue interest від P1 being overdue (P1.outBal=83.57 × 4% × 29/360 = 0.28)
Після cleanup: P2 interest = 0.22 (66.97 × 4% × 29/360) — overdue interest ВТРАЧЕНО
```

### v8.3: `allMatch(isFullyPaid)` замість `aggregatedOverDuePrincipal.isZero()`

**Рішення**: Замінити ненадійну перевірку `aggregatedOverDuePrincipal.isZero()` на `allMatch(RepaymentPeriod::isFullyPaid)`.

`isFullyPaid()` перевіряє `EMI + credited = totalPaid` — це перевірка ПОВНОЇ оплати (principal + interest), яка не обманюється eating effect.

```java
boolean allOverduePeriodsFullyPaid = overdueInstallmentsSortedByInstallmentNumber.stream()
        .allMatch(RepaymentPeriod::isFullyPaid);
if (allowOverdueCleanup
        && allOverduePeriodsFullyPaid      // ← v8.3: replaces aggregatedOverDuePrincipal.isZero()
        && scheduleModel.lastOverdueAmount() != null
        && scheduleModel.lastOverdueAmount().isGreaterThanZero()
        && scheduleModel.lastOverdueBalanceChange() != null) {
    // ... cleanup ...
}
```

**Чому це працює для всіх тестів:**

| Тест | Overdue periods | isFullyPaid | Cleanup? | Результат |
|------|----------------|-------------|----------|-----------|
| C4625 | [P1] | P1: true | ✓ triggers | P2 balance correct, duePrincipal=41.62 ✓ |
| Loan:8491 | [P1, P2] after pay-off | all: true | ✓ triggers | max(calcDue,paid) → status 600 ✓ |
| C4201 | [P1] at Sep30 | P1: true, BUT `allowOverdueCleanup=false` | ✗ blocked | Overdue interest preserved ✓ |
| C3918 | [P1, P2] | P1: true, **P2: false** (outstandingI=0.23) | ✗ blocked | Overdue interest 0.28 preserved ✓ |

**Чому eating не обманює `isFullyPaid`:**
Eating робить `outstandingPrincipal=0`, але `outstandingInterest` залишається > 0 (interest не покрита eating). `isFullyPaid` перевіряє ОБИДВА компоненти → false для частково оплачених.

### Шоста знахідка: перевірка лише overdue періодів недостатня (Feature:1116 регресія)

**Тест** (LoanInterestRateChange.feature:1116):
- 100 EUR, 6 місяців, 1st repayment Feb 10 (P1 dueDate=Feb 1, 9 днів late) + fees + charge-off

**Actual**: P2 balance=67.05, principal=16.52, interest=0.49
**Expected**: P2 balance=67.08, principal=16.49, interest=0.52

Аналіз debug логу `fineract-2421-debug_20260209_002930.log`:

```
Post-tx standalone recalcInterest (allowOverdueCleanup=true):
  findPossiblyOverdueRepaymentPeriods(Feb10):
    P1 (dueDate=Feb1): OVERDUE, outstandingP=0.00, fullyPaid=true

  v8.3 condition:
    allOverduePeriodsFullyPaid = [P1].allMatch(isFullyPaid) = true  ← P1 є єдиний overdue і він fully paid
    lastOverdueAmount > 0 ✓
    → ★ CLEANUP TRIGGERS ★  ← ПЕРЕДЧАСНО!
```

**Проблема**: P1 (єдиний overdue) fully paid → `allMatch(isFullyPaid)` = true → cleanup спрацьовує. Але P2-P6 **ще не оплачені** — вони потребують overdue interest від P1's overdue (P1 був 9 днів late: Feb1→Feb10).

**Вплив на P2 interest:**
```
На develop (очікувано):
  P2 overdue interest ≈ 0.52 (balance × rate × 9 days)

Після cleanup:
  P2 overdue interest ≈ 0.49 (overdue corrections видалені → менший balance)

Різниця: 0.52 - 0.49 = 0.03 → principal зростає відповідно
```

**Ключовий інсайт**: перевірка лише overdue періодів недостатня. Якщо ВСІ overdue оплачені, але кредит має неоплачені майбутні періоди → overdue corrections ще потрібні для коректного розрахунку interest.

### v8.4: `allMatch(isFullyPaid)` на ВСІХ періодах — FAILED: chicken-and-egg

**Ідея**: замінити `allOverduePeriodsFullyPaid` на `allPeriodsFullyPaid` — перевіряти ВСІ періоди моделі. Cleanup лише при закритті кредиту.

```java
boolean allPeriodsFullyPaid = scheduleModel.repaymentPeriods().stream()
        .allMatch(RepaymentPeriod::isFullyPaid);
```

**Чому не працює для C4625**: Модель-дамп після фінального платежу:

```
P1: EMI=25.77 paidP=23.69 paidI=2.10 outstandingP=0.00 fullyPaid=true  ✓
P2: EMI=41.58 paidP=41.62 paidI=0.00 outstandingP=0.05 fullyPaid=false ✗ (eating!)
P3: EMI=41.58 paidP=41.58                              fullyPaid=true  ✓
P4: EMI=41.58 paidP=41.58                              fullyPaid=true  ✓
P5: EMI=41.58 paidP=41.58                              fullyPaid=true  ✓
P6: EMI=41.58 paidP=41.58                              fullyPaid=true  ✓
```

**Chicken-and-egg**: P2 `isFullyPaid=false` бо eating effect створив `outstandingP=0.05`. Cleanup потрібен щоб виправити P2 balance → зменшити `duePrincipal` до 41.62 → `outstanding=0`. Але cleanup заблокований бо P2 не `isFullyPaid`.

**Чому P2 `isFullyPaid=false`**: `isFullyPaid()` перевіряє `EMI + credited = totalPaid`. EMI=41.58, а `totalPaid = paidP + paidI = 41.62 + 0.00 = 41.62`. 41.58 ≠ 41.62 → false. P2 "overpaid" відносно EMI через eating effect перерозподілу.

**Чому eating НЕ впливає на останній період**: eating effect впливає лише на період одразу після overdue (P2). P3-P6 вже мають правильні EMI що збігаються з paid. P6 (останній) `isFullyPaid=true`.

### v8.5: Перевірка тільки ОСТАННЬОГО періоду — FAILED: MIR advance payment

**Ідея**: замість перевірки всіх періодів, перевіряти чи **останній** період `isFullyPaid`. Це proxy для закриття кредиту.

**Чому не працює для C4625 intermediate (після undo, рядок 1782):**

```
Actual P2:   [166.32, 41.62, 0.0,  41.62, 41.58, 0.04]  ← interest=0.0!
Expected P2: [166.32, 41.62, 0.03, 41.65, 41.58, 0.07]  ← interest=0.03 (overdue)
```

Після undo (крок 6), P1 ще не оплачений (outstanding=11.01), але **MIR advance payment** (крок 2) вже оплатив P6. `lastPeriodFullyPaid=true` → cleanup хибно тригериться → P2 втрачає легітимний overdue interest 0.03.

**Ключовий інсайт**: `lastPeriodFullyPaid` недостатня ОДНА — MIR (advance payment) може зробити останній період fully paid навіть коли перший ще overdue. Потрібна ДОДАТКОВА перевірка що overdue вирішено.

### v8.6: КОМБІНАЦІЯ `lastPeriodFullyPaid AND allOverduePeriodsFullyPaid` — ПОТОЧНИЙ ФІКС

**Рішення**: обидві умови повинні виконуватися одночасно:

1. **`lastPeriodFullyPaid`**: proxy для закриття кредиту. Eating effect впливає лише на P2 (після overdue), не на P6 (останній). Без цієї умови: Feature:1116 fails (P1 overdue + fullyPaid, P2-P6 unpaid).
2. **`allOverduePeriodsFullyPaid`**: overdue має бути вирішено. Без цієї умови: C4625 intermediate fails (MIR платить P6, але P1 ще overdue). Використовує `isFullyPaid()` (не `outstandingPrincipal==0`) бо eating effect може зробити `outstandingP=0` для частково оплачених (C3918).

Разом = **"кредит закривається І overdue вирішено"**.

```java
List<RepaymentPeriod> allPeriods = scheduleModel.repaymentPeriods();
boolean lastPeriodFullyPaid = !allPeriods.isEmpty()
        && allPeriods.get(allPeriods.size() - 1).isFullyPaid();
boolean allOverduePeriodsFullyPaid = overdueInstallmentsSortedByInstallmentNumber.stream()
        .allMatch(RepaymentPeriod::isFullyPaid);
if (allowOverdueCleanup
        && lastPeriodFullyPaid
        && allOverduePeriodsFullyPaid
        && scheduleModel.lastOverdueAmount() != null
        && scheduleModel.lastOverdueAmount().isGreaterThanZero()
        && scheduleModel.lastOverdueBalanceChange() != null) {
    // ... cleanup ...
}
```

**Чому жодна з умов окремо не достатня:**

| Умова | Чому недостатня окремо | Тест що ламається |
|-------|----------------------|-------------------|
| `allOverduePeriodsFullyPaid` (v8.3) | P1 єдиний overdue + fully paid, але P2-P6 потребують overdue interest | Feature:1116 |
| `allPeriodsFullyPaid` (v8.4) | P2 not isFullyPaid через eating (chicken-and-egg) | C4625 final |
| `lastPeriodFullyPaid` (v8.5) | MIR advance payment робить P6 fully paid коли P1 ще overdue | C4625 intermediate |

**Перевірка по тестах (v8.6):**

| Тест | Overdue fullyPaid | Last period | Cleanup? | Результат |
|------|-------------------|-------------|----------|-----------|
| C4625 final (step 7) | P1: true ✓ | P6: true ✓ | ✓ triggers | P2 balance fixed ✓ |
| C4625 intermediate (step 6) | **P1: false** ✗ | P6: true | ✗ blocked (overdue) | Overdue interest 0.03 preserved ✓ |
| Loan:8491 | all overdue: true ✓ | last: true ✓ | ✓ triggers | max(calcDue,paid) → status 600 ✓ |
| C4201 | `allowOverdueCleanup=false` | — | ✗ blocked (parameter) | Overdue interest preserved ✓ |
| C3918 | **P2: false** (outstandingI) ✗ | last: unpaid | ✗ blocked (both) | Overdue interest 0.28 preserved ✓ |
| Feature:1116 | P1: true ✓ | **P6: unpaid** ✗ | ✗ blocked (last) | Overdue interest 0.52 preserved ✓ |

### Чому v8 не має проблеми нескінченного циклу (v1)

Cleanup встановлює `lastOverdueAmount = zero` і `lastOverdueBalanceChange = null`. Модель зберігається в JSON з цими значеннями.
- Delinquency service десеріалізує: `lastOverdueAmount = 0` → cleanup condition = false → нічого не робить ✓
- Наступний `processLatestTransaction`: модель з JSON має `lastOverdueBalanceChange=null` → cleanup condition = false ✓

### Зміни коду (v8 + v8.1 + v8.2 + v8.6 = поточний стан)

**Зміна 1 — `ProgressiveEMICalculator.java`, `recalculateModelOverdueAmountsTillDate`:**

3-param делегує до 4-param з `allowOverdueCleanup=false` (safe default):

```java
@Override
public boolean recalculateModelOverdueAmountsTillDate(..., boolean prepayAttempt) {
    return recalculateModelOverdueAmountsTillDate(..., prepayAttempt, false);
}

@Override
public boolean recalculateModelOverdueAmountsTillDate(..., boolean prepayAttempt, boolean allowOverdueCleanup) {
    // ... existing overdue loop logic ...

    // Cleanup: guard by allowOverdueCleanup (v8.2) + BOTH conditions (v8.6):
    // - lastPeriodFullyPaid: proxy for loan closure (prevents Feature:1116, handles eating chicken-and-egg)
    // - allOverduePeriodsFullyPaid: overdue resolved (prevents C4625 intermediate with MIR advance)
    List<RepaymentPeriod> allPeriods = scheduleModel.repaymentPeriods();
    boolean lastPeriodFullyPaid = !allPeriods.isEmpty()
            && allPeriods.get(allPeriods.size() - 1).isFullyPaid();
    boolean allOverduePeriodsFullyPaid = overdueInstallmentsSortedByInstallmentNumber.stream()
            .allMatch(RepaymentPeriod::isFullyPaid);
    if (allowOverdueCleanup
            && lastPeriodFullyPaid
            && allOverduePeriodsFullyPaid
            && scheduleModel.lastOverdueAmount() != null
            && scheduleModel.lastOverdueAmount().isGreaterThanZero()
            && scheduleModel.lastOverdueBalanceChange() != null) {
        Money overdueAmount = scheduleModel.lastOverdueAmount();
        LocalDate positiveDate = overdueInstallmentsSortedByInstallmentNumber.get(0).getDueDate();
        LocalDate negativeDate = scheduleModel.lastOverdueBalanceChange();

        addBalanceCorrection(scheduleModel, positiveDate, overdueAmount.negated());
        addBalanceCorrection(scheduleModel, negativeDate, overdueAmount);

        scheduleModel.lastOverdueAmount(scheduleModel.zero());
        scheduleModel.lastOverdueBalanceChange(null);
        hasChange = true;
    }
}
```

**Зміна 2 — `EMICalculator.java` — 4-param default method:**

```java
default boolean recalculateModelOverdueAmountsTillDate(ProgressiveLoanInterestScheduleModel ctx,
        LocalDate targetDate, boolean prepayAttempt, boolean allowOverdueCleanup) {
    return recalculateModelOverdueAmountsTillDate(ctx, targetDate, prepayAttempt);
}
```

**Зміна 3 — `AdvancedPaymentScheduleTransactionProcessor.java`, `recalculateInterestForDate`:**

```java
// 2-param: safe default (no cleanup)
public void recalculateInterestForDate(LocalDate targetDate, ProgressiveTransactionCtx ctx) {
    recalculateInterestForDate(targetDate, ctx, true, false);
}

// 4-param: threads allowOverdueCleanup through to emiCalculator
public void recalculateInterestForDate(LocalDate targetDate, ProgressiveTransactionCtx ctx,
        boolean updateInstallments, boolean allowOverdueCleanup) {
    // ...
    boolean modelHasUpdates = emiCalculator.recalculateModelOverdueAmountsTillDate(
            ctx.getModel(), targetDate, ctx.isPrepayAttempt(), allowOverdueCleanup);
    // ...
}
```

**Зміна 4 — `AdvancedPaymentScheduleTransactionProcessor.java`, виклики з `allowOverdueCleanup=true`:**

```java
// Рядок 295 — final reprocess recalc:
recalculateInterestForDate(targetDate, ctx, true, true);

// Рядок 516 — post-tx standalone (з isReprocessing guard):
if (ctx instanceof ProgressiveTransactionCtx progressiveTransactionCtx
        && !progressiveTransactionCtx.isReprocessing()) {
    recalculateInterestForDate(loanTransaction.getTransactionDate(), progressiveTransactionCtx, true, true);
    for (LoanRepaymentScheduleInstallment installment : progressiveTransactionCtx.getInstallments()) {
        installment.updateObligationsMet(progressiveTransactionCtx.getCurrency(), loanTransaction.getTransactionDate());
    }
}
```

**Зміна 5 — `ProgressiveTransactionCtx.java` + `reprocessProgressiveLoanTransactions`:**

```java
// ProgressiveTransactionCtx.java:
@Setter
private boolean isReprocessing = false;

// AdvancedPaymentScheduleTransactionProcessor.java, reprocessProgressiveLoanTransactions:
ctx.setReprocessing(true);
```

**Зміни v7 (видалення negativeToZero) — ВІДМІНЕНІ в v9:** Дивіться секцію v9 нижче.

### Порівняння підходів v3-v6 vs v8.6

| Аспект | v3-v6 (cleanup в payPrincipal) | v8.6 (cleanup в recalcOverdue + allowOverdueCleanup + lastPeriod + allOverdue) |
|--------|-------------------------------|------------------------------|
| **Коли** | ПІД ЧАС розподілу платежу | ПІСЛЯ повної обробки платежу, тільки в final/post-tx |
| **Умова** | `isPastDue` | `allowOverdueCleanup && lastPeriodFullyPaid && allOverduePeriodsFullyPaid && lastOverdueAmount > 0` |
| **paidAmounts** | Ще не зафіксовані для всіх періодів | Вже зафіксовані |
| **Ефект на due** | Зменшує calcDue → paid > due → OVERPAID | Зменшує calcDue, але max(calcDue, paid) = paid |
| **Проміжний cleanup** | N/A (в payPrincipal) | Заблокований `allowOverdueCleanup=false` |
| **MIR advance payment** | N/A | `allOverduePeriodsFullyPaid=false` блокує навіть коли last period paid ✓ |
| **Eating effect (P2)** | N/A | P2 not isFullyPaid, але P2 не overdue і P6 (останній) IS → cleanup тригериться коректно ✓ |
| **Loan:8491** | ✗ status 700 | ✓ status 600 |
| **C4625 final** | ✓ | ✓ (overdue P1 fullyPaid + last P6 fullyPaid) |
| **C4625 intermediate** | (не тестувалось) | ✓ (overdue P1 NOT fullyPaid → blocked) |
| **C4201** | (не тестувалось) | ✓ (allowOverdueCleanup=false в pre-tx) |
| **C3918** | (не тестувалось) | ✓ (overdue P2 NOT fullyPaid + last period unpaid) |
| **Feature:1116** | (не тестувалось) | ✓ (last period P6 unpaid → blocked) |
| **Нескінченний цикл** | v1: так, v3+: ні | Ні (lastOverdueAmount=0 після cleanup + default false для зовнішніх callers) |

---

## v9: Eating-victim-skip + відновлення negativeToZero + видалення early return guard — ПОТОЧНИЙ ФІКС

### Проблеми з v8.6

v8.6 використовував `lastPeriodFullyPaid && allOverduePeriodsFullyPaid` як умову cleanup. Це працювало для основних тестів, але:

1. **C4580 (LoanChargeOff.feature:10246/10348)**: v7 зміна (видалення `negativeToZero` з `updateOutstandingLoanBalance`) спричиняла каскадні зміни відсотків у сценаріях charge-off. Interest 2.35 замість 2.36.
2. **LoanChargeOff.feature:5784**: Interest 0.44 замість 0.42 (charge-off з advance payment).

### v9 ключові зміни (3 компоненти)

#### 1. Відновлення `negativeToZero` в `InterestPeriod.updateOutstandingLoanBalance()`

**Проблема**: Видалення `negativeToZero` (v7) дозволяло від'ємні IP балансі для коректної propagation, але спричиняло каскадні зміни interest в багатьох charge-off сценаріях.

**Рішення**: Відновити `MathUtil.negativeToZero(...)` в обох гілках `updateOutstandingLoanBalance()` — як на develop. IP балансі знову clamped до нуля.

```java
// isFirstInterestPeriod branch:
this.outstandingLoanBalance = MathUtil.negativeToZero(previousInterestPeriod.getOutstandingLoanBalance()
        .plus(previousInterestPeriod.getDisbursementAmount(), getMc())
        .plus(previousInterestPeriod.getCapitalizedIncomePrincipal(), getMc())
        .plus(previousInterestPeriod.getBalanceCorrectionAmount(), getMc())
        .minus(previousRepaymentPeriod.get().getDuePrincipal(), getMc())
        .plus(previousRepaymentPeriod.get().getPaidPrincipal(), getMc()), getMc());

// non-first IP branch:
this.outstandingLoanBalance = MathUtil.negativeToZero(previousInterestPeriod.getOutstandingLoanBalance()
        .plus(previousInterestPeriod.getBalanceCorrectionAmount(), getMc())
        .plus(previousInterestPeriod.getCapitalizedIncomePrincipal(), getMc())
        .plus(previousInterestPeriod.getDisbursementAmount(), getMc()), getMc());
```

**Наслідок**: `negativeToZero` залишається як на develop. Код InterestPeriod.java ідентичний develop окрім debug logging в `addBalanceCorrectionAmount`.

#### 2. Eating-victim-skip замість `lastPeriodFullyPaid && allOverduePeriodsFullyPaid`

**Проблема з v8.6**: Chicken-and-egg — P2 (eating victim) має `isFullyPaid=false` через eating effect. `allPeriodsFullyPaid` не працює. `lastPeriodFullyPaid && allOverduePeriodsFullyPaid` — складна комбінація умов, і вона ламається при charge-off.

**Ключовий інсайт**: Eating effect впливає **тільки на один період** — той що одразу після першого overdue (index = firstOverdueIndex + 1). Це єдиний період, чий `isFullyPaid()` corrupted eating'ом. Всі інші періоди мають коректний `isFullyPaid()`.

**Рішення**: Перевіряти `isFullyPaid()` для ВСІХ періодів, **КРІМ** eating victim:

```java
List<RepaymentPeriod> allPeriods = scheduleModel.repaymentPeriods();
int eatingVictimIndex = allPeriods.indexOf(overdueInstallmentsSortedByInstallmentNumber.get(0)) + 1;
boolean allPeriodsEssentiallyPaid = true;
for (int i = 0; i < allPeriods.size(); i++) {
    if (i == eatingVictimIndex) {
        continue; // skip eating victim — its isFullyPaid is corrupted
    }
    if (!allPeriods.get(i).isFullyPaid()) {
        allPeriodsEssentiallyPaid = false;
        break;
    }
}
if (allowOverdueCleanup
        && allPeriodsEssentiallyPaid
        && scheduleModel.lastOverdueAmount() != null
        && scheduleModel.lastOverdueAmount().isGreaterThanZero()
        && scheduleModel.lastOverdueBalanceChange() != null) {
    Money overdueAmount = scheduleModel.lastOverdueAmount();
    LocalDate positiveDate = overdueInstallmentsSortedByInstallmentNumber.get(0).getDueDate();
    LocalDate negativeDate = scheduleModel.lastOverdueBalanceChange();
    addBalanceCorrection(scheduleModel, positiveDate, overdueAmount.negated());
    addBalanceCorrection(scheduleModel, negativeDate, overdueAmount);
    scheduleModel.lastOverdueAmount(scheduleModel.zero());
    scheduleModel.lastOverdueBalanceChange(null);
    hasChange = true;
}
```

**Чому це семантично правильно:**
- Eating victim — це ЄДИНИЙ період, чий `isFullyPaid()` хибно повертає false через eating
- Пропускаючи його, ми фактично перевіряємо: "чи всі інші періоди genuinely fully paid?"
- Якщо всі інші оплачені → кредит закривається → cleanup потрібен
- Якщо хоча б один інший не оплачений → кредит не закритий → overdue interest ще потрібен

**Чому це замінює обидві умови v8.6:**
- `lastPeriodFullyPaid` — останній період перевіряється (він не eating victim, бо eating victim = firstOverdue + 1 ≤ P2)
- `allOverduePeriodsFullyPaid` — overdue періоди перевіряються (якщо не є eating victim)
- Feature:1116: P2-P6 unpaid → `allPeriodsEssentiallyPaid=false` → blocked ✓
- C4625 intermediate: P1 unpaid (outstanding=11.01) → `allPeriodsEssentiallyPaid=false` → blocked ✓
- C4625 final: all except P2 (eating victim, skipped) fully paid → cleanup ✓
- C3918: P3-P6 unpaid → blocked ✓

#### 3. Видалення early return guard в `adjustOverduePrincipal`

**Проблема**: Guard `if (overduePrincipal.isZero() && aggregatedOverDuePrincipal.isZero()) return false` блокує метод від повернення `true` коли обидві суми = 0.

**Чому це ламає charge-off (LoanChargeOff.feature:5784)**:

При charge-off schedule collapse, дата закінчення IP змінюється (наприклад, Mar 1 → Feb 29). `adjustOverduePrincipal` викликається з нульовими сумами. На develop (без guard), метод:
1. Входить в `!currentDate.equals(model.lastOverdueBalanceChange())` — true (lastOBC = null)
2. Додає нульові корекції (нешкідливо)
3. Встановлює `lastOverdueBalanceChange = lastOverdueBalanceChange`
4. Повертає `true` → extra while-loop ітерація
5. На extra ітерації модель перераховується з оновленою IP датою (Feb 29)
6. Наступний виклик: `!currentDate.equals(model.lastOverdueBalanceChange())` = false → повертає `false` → цикл зупиняється

З guard'ом метод повертає `false` одразу → IP зберігає стару дату (Mar 1) → interest розраховується для 15 днів замість 14 → 0.44 замість 0.42.

**Рішення**: Видалити guard. Date guard (`!currentDate.equals(model.lastOverdueBalanceChange())`) гарантує максимум 2 ітерації — нескінченний цикл неможливий.

**Debug evidence** (з `fineract-2421-debug_20260209_102139.log`):
```
Line 244: overdueFinalAdjust: currentPeriod=P2 [2024-02-01->2024-03-01]  ← OLD dueDate!
Line 245: adjustOverduePrincipal: overduePrincipal=EUR 0.00, aggregated=EUR 0.00 ← guard fires, return false
Line 263: P2 [2024-02-01->2024-02-29]  ← CORRECT dueDate, but too late
Line 290: IP2 [2024-02-15 -> 2024-03-01]  ← WRONG end date preserved in model
```

### Перевірка v9 по всіх тестах

| Тест | Cleanup | adjustOverdue | negativeToZero | Результат |
|------|---------|---------------|----------------|-----------|
| C4625 final (LoanInterestRateChange:1796) | eating-victim-skip → triggers | N/A | restored | P2 balance=166.32, duePrincipal=41.62 ✓ |
| C4625 intermediate (LoanInterestRateChange:1782) | P1 unpaid → blocked | N/A | restored | P2 interest=0.03 ✓ |
| Loan:8491 (Loan:8490) | eating-victim-skip → triggers | N/A | restored | status 600 ✓ |
| C4201 (Loan:8843) | allowOverdueCleanup=false | N/A | restored | P2 interest=0.52 ✓ |
| C3918 (LoanInterestRateChange:234) | P3-P6 unpaid → blocked | N/A | restored | P2 interest=0.28 ✓ |
| Feature:1116 (LoanInterestRateChange:1116) | P2-P6 unpaid → blocked | N/A | restored | P2 interest=0.52 ✓ |
| C4580 (LoanChargeOff:10246/10348) | not triggered | guard removed → extra iter | restored | interest correct ✓ |
| LoanChargeOff:5784 | not triggered | guard removed → extra iter | restored | interest=0.42 ✓ |

### Зміни коду (v9 = поточний стан)

**InterestPeriod.java** — ідентична develop (+ debug logging):
- `updateOutstandingLoanBalance()`: `MathUtil.negativeToZero(...)` в обох гілках
- `getCalculatedDueInterest()`: `case DECLINING_BALANCE -> getOutstandingLoanBalance().getAmount()` (без negativeToZero, як на develop)
- `addBalanceCorrectionAmount()`: debug logging (немає поведінкових змін)

**ProgressiveEMICalculator.java**:
- `recalculateModelOverdueAmountsTillDate(4-param)`: eating-victim-skip cleanup condition
- `adjustOverduePrincipal`: early return guard **видалено**
- `model.lastOverdueAmount(aggregatedOverDuePrincipal)`: tracking overdue amount (нова)

**AdvancedPaymentScheduleTransactionProcessor.java** (без змін від v8.6):
- `ctx.setReprocessing(true)` в reprocessing
- Post-tx standalone block з `!isReprocessing()` guard
- `recalculateInterestForDate` overloads з `allowOverdueCleanup`
- Final reprocess recalc з `allowOverdueCleanup=true`

**EMICalculator.java** (без змін від v8.2):
- 4-param default method

**ProgressiveTransactionCtx.java** (без змін від v8.1):
- `isReprocessing` field

---

## Довідка: ключові файли та методи

| Файл | Ключові методи | Роль |
|------|---------------|------|
| `ProgressiveEMICalculator.java` | `payPrincipal`, `recalculateModelOverdueAmountsTillDate` (v8.2: 3-param→4-param delegation, cleanup guarded by `allowOverdueCleanup`; v9: eating-victim-skip condition), `adjustOverduePrincipal` (v9: early return guard видалено), `addBalanceCorrection`, `calculateLastUnpaidRepaymentPeriodEMI` | Ядро розрахунків |
| `ProgressiveLoanInterestScheduleModel.java` | `changeOutstandingBalanceAndUpdateInterestPeriods`, `lastOverdueBalanceChange`, `lastOverdueAmount`, `deepCopy` | Модель графіку + стан overdue |
| `RepaymentPeriod.java` | `getDuePrincipal`, `getOutstandingPrincipal`, `getOutstandingLoanBalance` (має свій `negativeToZero`), `copy`, `copyWithoutPaidAmounts` | Суми на рівні періоду |
| `LoanTransactionProcessingServiceImpl.java` | `reprocessLoanTransactions` (save JSON), `processLatestTransactionProgressiveInterestRecalculation` (load JSON) | Lifecycle моделі між reprocessing і processLatestTransaction |
| `InterestPeriod.java` | `addBalanceCorrectionAmount` (+ debug logging), `updateOutstandingLoanBalance` (з `negativeToZero`, як на develop; v7 зміни відмінені в v9), `getCalculatedDueInterest` (без `negativeToZero` для DECLINING_BALANCE base, як на develop), `@EqualsAndHashCode` | Розрахунок балансу та відсотків |
| `AdvancedPaymentScheduleTransactionProcessor.java` | `processLatestTransaction` (v8 post-tx cleanup + v8.1 isReprocessing guard), `recalculateInterestForDate` (v8.2: 4-param з `allowOverdueCleanup`), `reprocessProgressiveLoanTransactions` (v8.1 `isReprocessing`, v8.2 final recalc з `allowOverdueCleanup=true`) | Оркестрація обробки транзакцій |
| `EMICalculator.java` | `recalculateModelOverdueAmountsTillDate` (v8.2: 4-param default method) | Інтерфейс EMI калькулятора |
| `ProgressiveTransactionCtx.java` | `isReprocessing` (v8.1) | Контекст progressive обробки, `isReprocessing` guard |
| `InterestScheduleModelRepositoryWrapperImpl.java` | `extractModel` (JSON десеріалізація), `getSavedModel`, `writeInterestScheduleModel` | Persistence моделі |
| `ProgressivePossibleNextRepaymentCalculationServiceImpl.java` | `calculateInterestRecalculationFutureOutstandingValue` | Delinquency service — тут був нескінченний цикл |
| `Memo.java` | `get`, `checkDependencyChangedAndUpdate` | Cache з hash-based invalidation |
