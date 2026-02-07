# FINERACT-2421: Stale Overdue Balance Corrections + Memo Cache = Negative Loan Balance

## Коротко (TL;DR)

Після комбінації **MIR (передоплата) + backdated зміна ставки + скасування платежу + фінальний платіж**, P2 показує `duePrincipal=41.67` замість `41.62`, P6 — `Balance of Loan = -0.05` замість `0.0`. Кредит не закривається.

### Два дефекти, що працюють разом:

**Дефект 1: Stale Memo Cache на `getCalculatedDueInterest()`**

`InterestPeriod` не перевизначає `hashCode()`, тому `addBalanceCorrectionAmount()` на існуючому IP НЕ інвалідує Memo для `getCalculatedDueInterest()`. Після фінального `payPrincipal()` model dump показує (з логу):

```
P2: calcDueInterest=EUR 0.10  ← STALE (IP sum: 0.023+0.008+0.023+0 = 0.054 → round 0.05)
P3: calcDueInterest=EUR 0.10  ← STALE (IP1 outBal=0.00, calcDueInt=0)
P4: calcDueInterest=EUR 0.10  ← STALE (IP1 outBal=0.00, calcDueInt=0)
P5: calcDueInterest=EUR 0.10  ← STALE (IP1 outBal=0.00, calcDueInt=0)
P6: calcDueInterest=EUR 0.10  ← STALE (IP1 outBal=0.00, calcDueInt=0)
```

P3-P6 мають нульові баланси в InterestPeriods, але calcDueInterest=0.10 — **неможливе** значення, що доводить стале кешування.

**Дефект 2: Overdue корекції, що залишаються після погашення**

`payPrincipal()` скасовує ПОЗИТИВНУ overdue корекцію в P1 IP (`+10.76@Nov3 → 0.00`), але **НЕГАТИВНІ** корекції в P2 IP залишаються: IP1[Nov3→Nov6].balCorr=0.04, IP2[Nov6→Nov7].balCorr=0.00, IP3[Nov7→Nov10].balCorr=**-10.80**. P2 InterestPeriods зберігають `outBal=10.80` у IP1-IP3, що через stale Memo генерує `futureUnrecognizedInterest=0.05`, завищуючи `getDuePrincipal()`.

### Наслідок (з логу, рядок 1925):

```
P2: EMI=41.58 + credited=0.04 + futureUnrecognizedInterest=0.05 - dueInterest=0.00 = 41.67
    paidP=41.62  →  outstandingP=0.05  ←  BUG
```

### Фактичне vs. Очікуване (E2E тест C4625):

| Період | | Balance of loan | Principal due | Interest | Outstanding |
|--------|---------|----------------|---------------|----------|-------------|
| P2 | **Факт** | 166.27 | **41.67** | 0.0 | **0.05** |
| P2 | Очікув. | 166.32 | 41.62 | 0.0 | 0.0 |
| P6 | **Факт** | **-0.05** | 41.58 | 0.0 | 0.0 |
| P6 | Очікув. | 0.0 | 41.58 | 0.0 | 0.0 |
| Total | **Факт** | | **231.68** | 2.10 | **0.05** |
| Total | Очікув. | | 231.63 | 2.10 | 0.0 |

Надлишок: 231.68 - 231.63 = **0.05 EUR** — фантомний interest від stale Memo + незнятих overdue корекцій.

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
| 7 | 10 Nov 2025 | Repayment 11.05 EUR (клієнт вносить) | Система створює 2 транзакції: Rep 1.22 на 07 Nov (P=0.97, I=0.25) + Rep 9.83 на 10 Nov (P=9.83, I=0.0) → Loan Balance=0.0, CLOSED_OBLIGATIONS_MET |

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

## Фактичні баланси під час виконання (runtime trace з логу)

Стан моделі після кожної транзакції під час фінального reprocessing (targetDate=2025-11-07, operations=6):

| Крок | Лог рядок | P1 outstandingP | P2 outstandingP | P2 calcDueInterest | Коментар |
|------|-----------|----------------:|----------------:|-------------------:|----------|
| Disburse | 1319 | 35.80 | 36.88 | 0.00 | EMI=42.75 при 35.99% rate |
| Rate→25.99% | 1339 | 36.56 | 37.36 | - | EMI→41.58, P1 interest→5.02 |
| MIR 220.83 | 1478 | 10.76 | 0.00 | 0.00 | P1 paidP=12.93, P2-P6 paidP=41.58 |
| Interest Refund 1.85 | 1726 | 10.76 | 0.00 | 0.00 | P1 paidI=1.85, EMI=25.77 |
| CBR 0.04 | 1790 | 10.76 | **0.04** | **0.02** | totalDuePrincipal→231.63; P3-P6 calcDueInterest=0.02 (вже stale!) |
| FINAL recalc Nov 7 | 1826 | 10.76 | 0.04 | **0.03** | Memo інвалідовано (EMI change 41.58→41.61); P2 interest легітимний |
| Pre-repay overdue | 1849-1903 | 10.76 | 0.04 | — | IP split [Nov7→Nov10] інвалідує Memo; +10.76/-10.76 корекції |
| Repayment 11.05 | **1919** | **0.00** | **0.05** | **0.10** ← BUG | payPrincipal: -10.76@Nov3, -0.04@Nov10 → Memo NOT invalidated |

---

## Детальний аналіз проблеми

### 1. Послідовність overdue корекцій під час reprocessing

Для кожної транзакції `processLatestTransaction` викликає `recalculateInterestForDate(txDate)` **перед** обробкою. Це в свою чергу викликає `recalculateModelOverdueAmountsTillDate()`, який знаходить прострочені періоди і додає парні корекції:
```
+overduePrincipal  на  period.fromDate (або lastOverdueBalanceChange)  <-- ПОЗИТИВНА
-aggregatedOverDue на  currentDate (або period.dueDate)                <-- НЕГАТИВНА
```

**ВАЖЛИВО:** Позитивна корекція потрапляє в **P1** IP, а негативна — в **P2** IP! Вони в РІЗНИХ періодах, бо `addBalanceCorrection(date, amount)` знаходить RepaymentPeriod для вказаної дати через `findRepaymentPeriod(date)`, і дата Nov 3 (P1 dueDate) ще в межах P1 діапазону.

**Послідовність викликів (верифіковано з логу):**

```
CBR (Nov 6) [лог рядки 1765-1807]: ПЕРША overdue перевірка
  recalculateModelOverdueAmountsTillDate: targetDate=Nov6, lastOverdueBalanceChange=null
  P1 outstandingP = 10.76
  +10.76@Nov3 -> P1 IP3[Oct15->Nov3].balCorr: 0.00 → 10.76
  -10.76@Nov6 -> P2 IP split [Nov3->Nov6].balCorr: 0.00 → -10.76
  creditPrincipal 0.04 -> P2 IP1[Nov3->Nov6].balCorr: -10.76 → -10.72
  lastOverdueBalanceChange: null → Nov6

FINAL recalcInterest (Nov 7) [лог рядки 1808-1844]: ДРУГА overdue перевірка
  adjustOverduePrincipal: currentDate=Nov7, lastOverdueBalanceChange=Nov6
  +10.76@Nov6 -> P2 IP1[Nov3->Nov6].balCorr: -10.72 + 10.76 = 0.04 (CBR credit!)
  -10.76@Nov7 -> P2 IP split [Nov6->Nov7].balCorr: 0.00 → -10.76
  lastOverdueBalanceChange: Nov6 → Nov7
  P2 calcDueInterest=0.03 (легітимний overdue interest) ← CORRECT STATE

Pre-repayment (Nov 10) [лог рядки 1849-1903]:
  РАУНД 1: targetDate=Nov10, lastOverdueBalanceChange=Nov7
    +10.76@Nov7 -> P2 IP2[Nov6->Nov7].balCorr: -10.76 + 10.76 = 0.00
    -10.76@Nov10 -> P2 IP split [Nov7->Nov10].balCorr: 0.00 → -10.76
    lastOverdueBalanceChange: Nov7 → Nov10

  → modelHasUpdates=true → model RE-EVALUATED → lastOverdueBalanceChange СКИНУТО на Nov7!

  РАУНД 2: targetDate=Nov10, lastOverdueBalanceChange=Nov7 (знову!)
    IP стан ВІДНОВЛЕНИЙ до значень перед Раундом 1
    ТІ САМІ корекції перезастосовані (не подвоєні — модель перебудовується)
    lastOverdueBalanceChange: Nov7 → Nov10

  same-date guard БЛОКУЄ третій раунд: currentDate=Nov10 == lastOverdueBalanceChange=Nov10

handleRepayment(11.05) [лог рядки 1904-1918] -> payPrincipal:
  P1: addBalanceCorrection(Nov 3, -10.76) -> IP3[Oct15->Nov3].balCorr: 10.76 → 0.00  ← скасовує overdue
  P2: addBalanceCorrection(Nov 10, -0.04) -> IP3[Nov7->Nov10].balCorr: -10.76 → -10.80
  ⚠ Обидва виклики модифікують ІСНУЮЧІ IP → Memo НЕ інвалідується!

POST-REPAYMENT [лог рядки 1939-2000]: calculateInterestRecalculationFutureOutstandingValue(Dec 3)
  P1 outstandingP=0.00, fullyPaid=true
  adjustOverduePrincipal: overduePrincipal=0.00, corrections: +0.00@Nov10, -0.00@Dec3
  lastOverdueBalanceChange: Nov10 → Dec3
  → modelHasUpdates=true → model RE-EVALUATED → lastOverdueBalanceChange СКИНУТО на Nov10
  → НЕСКІНЧЕННИЙ ЦИКЛ! (лог обрізано на рядку 2000)
  ПОПЕРЕДНІ НЕНУЛЬОВІ КОРЕКЦІЇ НЕ ЗНІМАЮТЬСЯ! <-- БАГ
```

### 2. Механізм завищення `getDuePrincipal()`: `futureUnrecognizedInterest`

```
RepaymentPeriod.java:340-345
```

**Повна формула:**
```java
getDuePrincipal() = max(
    negativeToZero(EMI + totalCredited + futureUnrecognizedInterest - getDueInterest()),
    paidPrincipal
)
```

**Критична деталь -- `getDueInterest()` має гілку для overpaid періодів:**
```java
getDueInterest() = max(
    paidPrincipal > calculatedDuePrincipal
        ? paidInterest                                          // <-- ГІЛКА A: overpaid principal
        : min(calculatedDueInterest, EMI+credited+future),      // <-- ГІЛКА B: стандартна
    paidInterest
)
```

**ФАКТИЧНИЙ механізм для P2:**

```
EMI = 41.58
totalCredited = 0.04 (creditedPrincipal від CBR)
futureUnrecognizedInterest = 0.05   <-- THE PHANTOM!
getDueInterest() = 0.00 (гілка A маскує його)

negativeToZero(41.58 + 0.04 + 0.05 - 0.00) = 41.67
paidPrincipal = 41.62
max(41.67, 41.62) = 41.67 <-- ЗАВИЩЕНО на 0.05

Без фантому:
  futureUnrecognizedInterest = 0.00
  negativeToZero(41.58 + 0.04 + 0.00 - 0.00) = 41.62
  max(41.62, 41.62) = 41.62  <-- CORRECT
```

`futureUnrecognizedInterest` ненульовий тому що P2 має `outstandingBal > 0` в своїх InterestPeriods (через застарілі overdue корекції). Ці ненульові баланси генерують 0.0545 фантомного interest (IP1: 0.0233 + IP2: 0.0078 + IP3: 0.0234, з логу рядки 1926-1928), що округлюється до **0.05**.

### 3. Стан Memo кешу -- доказ з логу (рядки 1919-1937)

Після `payPrincipal` model dump показує:
```
P2: calcDueInterest=EUR 0.10  dueInterest=EUR 0.00  duePrincipal=EUR 41.67  outstandingP=EUR 0.05
  IP1 [Nov3→Nov6]:  balCorr=0.04   outBal=10.76 calcDueInt=0.023
  IP2 [Nov6→Nov7]:  balCorr=0.00   outBal=10.80 calcDueInt=0.008
  IP3 [Nov7→Nov10]: balCorr=-10.80 outBal=10.80 calcDueInt=0.023  ← effective=0.00
  IP4 [Nov10→Dec3]: balCorr=0.00   outBal=0.00  calcDueInt=0

P3: calcDueInterest=EUR 0.10  (IP1: outBal=0.00, calcDueInt=0)
P4: calcDueInterest=EUR 0.10  (IP1: outBal=0.00, calcDueInt=0)
P5: calcDueInterest=EUR 0.10  (IP1: outBal=0.00, calcDueInt=0)
P6: calcDueInterest=EUR 0.10  (IP1: outBal=0.00, calcDueInt=0)
```

**P2:** IP sum(calcDueInt) = 0.023+0.008+0.023+0 = **0.054** → rounds to 0.05, НЕ 0.10.
**P3-P6:** мають нульові баланси → calcDueInterest має бути **0.00**, НЕ 0.10.

Значення **0.10 — стале**, закешоване до останніх `addBalanceCorrectionAmount()` викликів.

**Чому кеш не інвалідується:** `InterestPeriod` **НЕ перевизначає** `hashCode()`, тому використовується `System.identityHashCode()`.

| Операція | Змінює посилання? | Memo інвалідується? |
|----------|-------------------|---------------------|
| `addBalanceCorrectionAmount()` на існуючому IP | **НІ** -- той же об'єкт | **НІ** -- identity hash не змінився |
| `insertInterestPeriod()` (IP split) | **ТАК** -- новий елемент | **ТАК** -- ArrayList.hashCode() змінюється |
| `setEmi(newMoney)` / `addPaidPrincipalAmount()` | **ТАК** -- новий Money | **ТАК** |

**Ланцюг інвалідації:** `payPrincipal()` → `addBalanceCorrectionAmount()` на P1 IP3 та P2 IP3 → ОБА існуючі об'єкти → Memo **НЕ** інвалідується → stale 0.10 повертається для P2-P6.

### 3a. Нескінченний цикл в calculateInterestRecalculationFutureOutstandingValue (лог рядки 1939-2000)

Після `payPrincipal`, `recalculateModelOverdueAmountsTillDate(Dec3)` виконується для розрахунку futureOutstanding:

```
ЦИКЛ (повторюється нескінченно):
  recalculateModelOverdueAmountsTillDate: targetDate=Dec3, lastOverdueBalanceChange=Nov10
    P1: outstandingP=0.00, fullyPaid=true
    overDuePrincipal=0.00 → adjustOverduePrincipal: +0.00@Nov10, -0.00@Dec3
    lastOverdueBalanceChange: Nov10 → Dec3 ← CHANGED!
    hasChange=true ← тому що дата змінилася!

  recalculateInterestForDate: modelHasUpdates=true
    → model RE-EVALUATED → lastOverdueBalanceChange СКИНУТО на Nov10

  recalculateModelOverdueAmountsTillDate: targetDate=Dec3, lastOverdueBalanceChange=Nov10 ← ЗНОВУ!
    ... ТОЙ САМИЙ цикл ...
```

**Причина:** `adjustOverduePrincipal` змінює `lastOverdueBalanceChange` (Nov10→Dec3) навіть для **нульових** корекцій (+0.00/-0.00), бо guard `aggregatedOverDuePrincipal.isGreaterThanZero()` пропускається (він = 0). Але `hasChange` повертає `true` бо дата змінилася. Model re-evaluation скидає дату назад → нескінченний цикл.

### 4. Як завищений principal призводить до від'ємного Balance of Loan

```
LoanRepaymentScheduleService.java:77, 152, 242, 246
```

API обчислює `Balance of Loan` як накопичувальне віднімання:

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

API шар **не використовує `negativeToZero`** для `Balance of Loan`, тому -0.05 проходить напряму в API відповідь.

---

## `balanceCorrectionAmount` -- спільне поле

`InterestPeriod.balanceCorrectionAmount` -- це **одне поле**, яке накопичує корекції з **різних джерел**:

1. **Платіжні корекції** -- від `payPrincipal()` -- **НЕГАТИВНІ**
2. **Кредитні корекції** -- від `creditPrincipal()` -- **ПОЗИТИВНІ**
3. **Overdue корекції** -- від `adjustOverduePrincipal()` -- парні +X/-X

**Немає способу відрізнити**, яка частина `balanceCorrectionAmount` прийшла від overdue коригувань vs. платежів. Це робить вибіркове скасування нетривіальним.

---

## Захисти, які запобігають очищенню

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

Коли P1 вже сплачений: `overDuePrincipal` залишається 0 -> `adjustOverduePrincipal` ніколи не викликається в циклі.

### Бар'єр 2: Guard однієї дати в `adjustOverduePrincipal`

```java
if (!currentDate.equals(model.lastOverdueBalanceChange())) {
    // додати корекції
    return true;
}
return false;  // <-- ПРОПУСКАЄ якщо та сама дата
```

### Бар'єр 3: Умовне оновлення `lastOverdueBalanceChange`

```java
if (aggregatedOverDuePrincipal.isGreaterThanZero()
        && (model.lastOverdueBalanceChange() == null
            || model.lastOverdueBalanceChange().isBefore(recalculatedTargetDate))) {
    scheduleModel.lastOverdueBalanceChange(recalculatedTargetDate);
}
// ТІЛЬКИ якщо aggregated > 0 І дата > попередня
```

Коли overdue = 0 (після погашення P1), дата НЕ оновлюється -> попередні ненульові корекції залишаються.

---

## Очікуваний фінальний стан (графік + транзакції)

### Графік після фінального платежу (крок 7)

| Nr | Date | Paid date | Balance of loan | Principal due | Interest | Due | Paid | In advance | Late | Outstanding |
|----|------|-----------|-----------------|---------------|----------|-----|------|------------|------|-------------|
| 1 | 03 Nov 2025 | 10 Nov 2025 | 207.9 | 23.69 | 2.1 | 25.79 | 25.79 | 14.78 | 11.01 | 0.0 |
| 2 | 03 Dec 2025 | 10 Nov 2025 | 166.32 | 41.62 | 0.0 | 41.62 | 41.62 | 41.62 | 0.0 | 0.0 |
| 3 | 03 Jan 2026 | 15 Oct 2025 | 124.74 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| 4 | 03 Feb 2026 | 15 Oct 2025 | 83.16 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| 5 | 03 Mar 2026 | 15 Oct 2025 | 41.58 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| 6 | 03 Apr 2026 | 15 Oct 2025 | 0.0 | 41.58 | 0.0 | 41.58 | 41.58 | 41.58 | 0.0 | 0.0 |
| **Total** | | | | **231.63** | **2.1** | **233.73** | **233.73** | **222.72** | **11.01** | **0.0** |

**Ключові відмінності від бага:** P2 principal=41.62 (не 41.67), P2 Balance=166.32 (не 166.27), P6 Balance=0.0 (не -0.05). Статус: CLOSED_OBLIGATIONS_MET.

### Транзакції

| Дата | Тип | Сума | Principal | Interest | Loan Balance | Reverted | Replayed |
|------|-----|------|-----------|----------|-------------|----------|----------|
| 03 Oct 2025 | Disbursement | 231.59 | 0.0 | 0.0 | 231.59 | false | false |
| 15 Oct 2025 | Merchant Issued Refund | 220.83 | 220.83 | 0.0 | 10.76 | false | false |
| 15 Oct 2025 | Interest Refund | 1.85 | 0.0 | 1.85 | 10.76 | false | true |
| 30 Oct 2025 | Accrual | 2.84 | 0.0 | 2.84 | 0.0 | false | false |
| 30 Oct 2025 | Repayment | 11.04 | 10.76 | 0.2 | 0.0 | **true** | true |
| 30 Oct 2025 | Accrual Adjustment | 0.79 | 0.0 | 0.79 | 0.0 | false | false |
| 03 Nov 2025 | Accrual Activity | 2.1 | 0.0 | 2.1 | 0.0 | false | true |
| 06 Nov 2025 | Credit Balance Refund | 0.04 | 0.04 | 0.0 | 10.8 | false | true |
| 07 Nov 2025 | Repayment | 1.22 | 0.97 | 0.25 | 9.83 | false | false |
| 07 Nov 2025 | Accrual | 0.08 | 0.0 | 0.08 | 0.0 | false | false |
| 10 Nov 2025 | Repayment | 9.83 | 9.83 | 0.0 | 0.0 | false | false |
| 10 Nov 2025 | Accrual Adjustment | 0.03 | 0.0 | 0.03 | 0.0 | false | false |

**Зверніть увагу:** Клієнт робить ОДИН платіж 11.05 на 10 Nov, але система розбиває його на дві транзакції:
- **1.22 на 07 Nov** (P=0.97, I=0.25) — покриває залишок P1 interest (2.10 - 1.85 = 0.25) + частину principal
- **9.83 на 10 Nov** (P=9.83, I=0.0) — покриває залишок principal

Перевірка: 0.97 + 9.83 = 10.80 (principal) = 10.76 (P1 outstanding) + 0.04 (P2 CBR credit). Interest: 0.25 = P1 overdue interest.

---

## Чому це складно виправити

### Чому прості підходи не працюють:

1. **Не можна модифікувати тільки `getDuePrincipal()`** -- `max(calculated, paidPrincipal)` потрібен для сценаріїв pay-off та early repayment. Видалення його ламає інші тести.

2. **Не можна просто відняти фантомний interest** -- `balanceCorrectionAmount` спільне між платіжними корекціями та overdue корекціями. Немає способу відрізнити які з яких.

3. **Підхід "скасувати все на початку" -- занадто агресивний** -- скасування всіх overdue корекцій на початку кожного виклику `recalculateModelOverdueAmountsTillDate` знищує **легітимний** overdue interest, який має існувати на проміжних кроках.

4. **Підхід "скасувати в кінці коли нуль" -- також не спрацював** -- захист `lastOverdueBalanceChange` запобігає виконанню `adjustOverduePrincipal` на ту саму дату. Тому у ФІНАЛЬНОМУ виклику метод повертає `false`, і код очищення ніколи не виконується.

5. **Стан Memo кешу -- окрема проблема** -- навіть якщо overdue корекції будуть правильно скасовані, `calcDueInterest` для P2-P6 залишається 0.10 через identity-based hashCode в InterestPeriod. Потрібна також інвалідація кешу після `addBalanceCorrectionAmount()`.

### Фундаментальне протиріччя:

- **Проміжний стан (після undo, до платежу):** overdue корекції ЛЕГІТИМНІ — P1 прострочений → P2 отримує interest=0.03 (лог рядок 1832)
- **Фінальний стан (після платежу):** overdue корекції ЗАСТАРІЛІ — P1 сплачений → P2 має мати interest=0.0, але Memo повертає stale 0.10
- Система має **розрізняти** ці два стани, але:
  1. `balanceCorrectionAmount` — спільне поле для платежів, кредитів і overdue корекцій (немає способу відрізнити)
  2. Memo кеш не інвалідується при `addBalanceCorrectionAmount()` на існуючих IP
  3. Post-repayment `recalculateModelOverdueAmountsTillDate` додає нульові корекції (+0.00/-0.00), що створюють нескінченний цикл замість очищення старих

### Три проблеми, що потребують вирішення:

1. **Memo cache invalidation** — `addBalanceCorrectionAmount()` має інвалідувати Memo для `getCalculatedDueInterest()`
2. **Overdue cleanup** — коли P1 fullyPaid=true, залишкові overdue корекції в P2 IP мають бути знятими
3. **Infinite loop** — `adjustOverduePrincipal` не повинен повертати `hasChange=true` для нульових корекцій (+0.00/-0.00)

---

## Довідка: ключові файли та методи

| Файл | Ключові методи | Роль |
|------|---------------|------|
| `ProgressiveEMICalculator.java` | `recalculateModelOverdueAmountsTillDate`, `adjustOverduePrincipal`, `addBalanceCorrection`, `payPrincipal`, `calculateLastUnpaidRepaymentPeriodEMI` | Ядро розрахунків |
| `ProgressiveLoanInterestScheduleModel.java` | `changeOutstandingBalanceAndUpdateInterestPeriods`, `lastOverdueBalanceChange` | Модель графіку + стан overdue |
| `InterestPeriod.java` | `addBalanceCorrectionAmount`, `updateOutstandingLoanBalance`, `getCalculatedDueInterest` | Розрахунок балансу та відсотків |
| `RepaymentPeriod.java` | `getDuePrincipal`, `getOutstandingPrincipal`, `getEmiPlusCreditedAmountsPlusFutureUnrecognizedInterest` | Суми на рівні періоду |
| `AdvancedPaymentScheduleTransactionProcessor.java` | `processLatestTransaction`, `recalculateInterestForDate`, `reprocessProgressiveLoanTransactions` | Оркестрація обробки транзакцій |
| `LoanRepaymentScheduleService.java` | `extractLoanScheduleData` | API відповідь -- де з'являється від'ємний баланс |
