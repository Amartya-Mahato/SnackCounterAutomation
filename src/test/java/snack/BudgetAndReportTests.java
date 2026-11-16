package snack;

import static org.junit.jupiter.api.Assertions.*;
import static snack.Fakes.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetAndReportTests {
    FakeChat chat;
    FakeHr hr;
    Volunteer oslA, oslB, blrA, blrB;
    RefillService service;

    @BeforeEach
    void setUp() {
        chat = new FakeChat();
        hr = new FakeHr();
        oslA = new Volunteer("oslA", "OSL");
        oslB = new Volunteer("oslB", "OSL");
        blrA = new Volunteer("blrA", "BLR");
        blrB = new Volunteer("blrB", "BLR");
        service = new RefillService(chat, hr, List.of(OSL, BLR), List.of(oslA, oslB, blrA, blrB));
    }

    /** Full cycle: report, whoever is offered accepts, and they complete with this amount. */
    Refill buy(String office, Money amount, Instant at) {
        Refill r = service.reportEmpty(office, "someone", at);
        assertTrue(service.accept(r.id, r.assignee, at), "accept " + r.id);
        assertTrue(service.complete(r.id, r.assignee, amount, "receipt.jpg", Set.of(), at));
        return r;
    }

    @Test
    void moneyNeverMixesCurrenciesAndAddsExactly() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "NOK").plus(Money.of("1", "INR")));
        assertEquals(Money.of("0.30", "NOK"), Money.of("0.10", "NOK").plus(Money.of("0.20", "NOK")));
    }

    @Test
    void spendCapIsWhatIsLeftOfTheMonthlyBudget() {
        Instant t = oslo("2026-11-16T10:00");
        for (int i = 0; i < 4; i++) {
            buy("OSL", Money.of("1200", "NOK"), t.plusSeconds(i * 60));
        }
        buy("OSL", Money.of("500", "NOK"), t.plusSeconds(300));       // 5300 of 6000 spent

        Refill r = service.reportEmpty("OSL", "someone", t.plusSeconds(400));
        service.accept(r.id, r.assignee, t.plusSeconds(400));

        assertEquals(Money.of("700", "NOK"), r.spendCap);
    }

    @Test
    void noOneIsSentShoppingWhenTheBudgetIsUsedUp() {
        Instant t = oslo("2026-11-16T10:00");
        for (int i = 0; i < 5; i++) {
            buy("OSL", Money.of("1150", "NOK"), t.plusSeconds(i * 60)); // 5750 spent, 250 left < 300 minimum
        }

        Refill r = service.reportEmpty("OSL", "someone", t.plusSeconds(600));

        assertEquals(RefillState.ESCALATED, r.state);
        assertTrue(chat.last("#snacks-oslo").contains("budget is used up"));
        assertFalse(service.accept(r.id, "oslA", t.plusSeconds(700)), "stepping up does not bypass the budget");

        service.tick(oslo("2026-12-01T09:00"));
        assertEquals(RefillState.OFFERED, r.state, "a new month brings a new budget");
    }

    @Test
    void overspendIsFlaggedButStillReimbursed() {
        Refill r = service.reportEmpty("OSL", "someone", oslo("2026-11-16T10:00"));
        service.accept(r.id, r.assignee, oslo("2026-11-16T10:00"));

        service.complete(r.id, r.assignee, Money.of("1350", "NOK"), "receipt.jpg", Set.of(), oslo("2026-11-16T12:00"));

        assertTrue(r.overCap);
        assertEquals(RefillState.CLAIM_SUBMITTED, r.state, "the volunteer already paid; never leave them out of pocket");
    }

    @Test
    void visitorBuysInTheHostOfficesCurrencyAndBudget() {
        oslA.active = false;
        oslB.active = false;
        blrA.visitOffice = "OSL";
        blrA.visitUntil = LocalDate.of(2026, 11, 27);

        Refill r = service.reportEmpty("OSL", "someone", oslo("2026-11-16T10:00"));
        assertEquals("blrA", r.assignee);
        service.accept(r.id, "blrA", oslo("2026-11-16T10:00"));

        assertFalse(service.complete(r.id, "blrA", Money.of("9000", "INR"), "r.jpg", Set.of(), oslo("2026-11-16T12:00")),
                "the receipt is in NOK, so INR is refused");
        assertTrue(service.complete(r.id, "blrA", Money.of("900", "NOK"), "r.jpg", Set.of(), oslo("2026-11-16T12:00")));

        assertEquals("NOK", hr.claims.get(r.claimId).currency(), "HR converts to the visitor's INR payroll, not us");
        assertEquals(Money.of("5100", "NOK"), service.remaining(OSL, oslo("2026-11-16T12:00")));
        assertEquals(Money.of("15000", "INR"), service.remaining(BLR, bengaluru("2026-11-16T12:00")),
                "the Bengaluru budget is untouched");
    }

    @Test
    void monthlyReportKeepsCurrenciesApartAndCombinesInNok() {
        buy("OSL", Money.of("1000", "NOK"), oslo("2026-11-16T10:00"));
        buy("OSL", Money.of("500.50", "NOK"), oslo("2026-11-17T10:00"));
        buy("BLR", Money.of("2500", "INR"), bengaluru("2026-11-16T10:00"));
        buy("BLR", Money.of("1500", "INR"), bengaluru("2026-11-17T10:00"));
        // 20:00 UTC on 30 Nov is 01:30 on 1 Dec in Bengaluru: it belongs to Bengaluru's December budget
        Refill december = service.reportEmpty("BLR", "someone", bengaluru("2026-11-30T10:00"));
        service.accept(december.id, december.assignee, bengaluru("2026-11-30T10:00"));
        service.complete(december.id, december.assignee, Money.of("700", "INR"), "r.jpg", Set.of("biscuits"),
                Instant.parse("2026-11-30T20:00:00Z"));

        MonthlyReport report = service.monthlyReport(YearMonth.of(2026, 11), Map.of("INR", new BigDecimal("0.12")));

        assertEquals(Money.of("1500.50", "NOK"), report.offices().get(0).spent());
        assertEquals(Money.of("4000", "INR"), report.offices().get(1).spent());
        assertEquals(Money.of("1980.50", "NOK"), report.totalNok(), "1500.50 NOK + 4000 INR x 0.12");
        assertTrue(report.render().contains("Total: 1980.50 NOK"));

        MonthlyReport dec = service.monthlyReport(YearMonth.of(2026, 12), Map.of("INR", new BigDecimal("0.12")));
        assertEquals(Money.of("700", "INR"), dec.offices().get(1).spent());
        assertEquals(Map.of("biscuits", 1L), dec.offices().get(1).leftOver());
    }

    @Test
    void reportRefusesToGuessAMissingExchangeRate() {
        assertThrows(IllegalArgumentException.class, () -> service.monthlyReport(YearMonth.of(2026, 11), Map.of()));
    }
}
