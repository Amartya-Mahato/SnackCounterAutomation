package snack;

import static org.junit.jupiter.api.Assertions.*;
import static snack.Fakes.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefillLifecycleTests {
    static final Instant MON_10 = oslo("2026-11-16T10:00");

    FakeChat chat;
    FakeHr hr;
    List<Volunteer> people;
    RefillService service;

    @BeforeEach
    void setUp() {
        chat = new FakeChat();
        hr = new FakeHr();
        people = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            people.add(new Volunteer("osl" + (i < 10 ? "0" : "") + i, "OSL"));
        }
        people.add(new Volunteer("blr01", "BLR"));
        service = new RefillService(chat, hr, List.of(OSL, BLR), people);
    }

    Volunteer person(String id) {
        return people.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
    }

    @Test
    void emptyBoxIsOfferedToOneNamedPersonNotBroadcast() {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);

        assertEquals(RefillState.OFFERED, r.state);
        assertEquals("osl01", r.assignee);
        assertTrue(chat.last("osl01").contains("Can you refill the OSL snack box (up to 1200.00 NOK)?"));
        assertNull(chat.last("osl02"), "only the chosen person is asked");
    }

    @Test
    void secondEmptyReportJoinsTheOpenRefill() {
        Refill first = service.reportEmpty("OSL", "a", MON_10);
        service.accept(first.id, "osl01", MON_10);

        Refill second = service.reportEmpty("OSL", "b", MON_10.plusSeconds(60));

        assertSame(first, second);
        assertEquals("Thanks! The OSL snack box is already being handled by osl01.", chat.last("b"));
    }

    @Test
    void twoPeopleCannotBothCommitToTheSameRefill() throws Exception {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 1; i <= 10; i++) { // the offered person plus nine who step up on their own, all at once
            String id = "osl" + (i < 10 ? "0" : "") + i;
            results.add(pool.submit(() -> {
                start.await();
                return service.accept(r.id, id, MON_10);
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> f : results) {
            winners += f.get() ? 1 : 0;
        }
        pool.shutdown();

        assertEquals(1, winners);
        assertEquals(RefillState.COMMITTED, r.state);
    }

    @Test
    void offerNobodyAnswersIsHandedToTheNextPerson() {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);

        service.tick(oslo("2026-11-16T11:59"));
        assertEquals("osl01", r.assignee, "still within the 2 working hours");

        service.tick(oslo("2026-11-16T12:00"));
        assertEquals("osl02", r.assignee);
        assertEquals(RefillState.OFFERED, r.state);
        assertTrue(chat.last("osl01").contains("went to someone else"));
    }

    @Test
    void refillNobodyCompletesIsHandedToSomeoneElse() {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);
        service.accept(r.id, "osl01", MON_10);

        service.tick(oslo("2026-11-17T16:59"));
        assertEquals("osl01", r.assignee, "still has until the end of the next working day");

        service.tick(oslo("2026-11-17T17:00"));          // end of next working day
        assertEquals(RefillState.REPORTED, r.state, "office is closed at 17:00, so it waits for the morning");
        service.tick(oslo("2026-11-18T09:00"));
        assertEquals(RefillState.OFFERED, r.state);
        assertEquals("osl02", r.assignee);

        boolean late = service.complete(r.id, "osl01", Money.of("500", "NOK"), "receipt.jpg", Set.of(), oslo("2026-11-18T09:05"));
        assertFalse(late, "the original holder can no longer complete, so there is no double purchase");
        assertTrue(chat.last("osl01").contains("contact osl-coordinator"));
    }

    @Test
    void declineGoesToTheNextPersonAndAnEmptyPoolEscalates() {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);
        service.decline(r.id, "osl01", MON_10);
        assertEquals("osl02", r.assignee);

        for (int i = 2; i <= 10; i++) {
            service.decline(r.id, r.assignee, MON_10);
        }

        assertEquals(RefillState.ESCALATED, r.state);
        assertTrue(chat.last("osl-coordinator").contains("no volunteer is available"));
        assertTrue(service.accept(r.id, "osl05", MON_10.plusSeconds(600)), "anyone can still step up after escalation");
    }

    @Test
    void escalatedRefillIsRetriedWithTheWholePoolNextWorkingDay() {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);
        for (int i = 1; i <= 10; i++) {
            service.decline(r.id, r.assignee, MON_10);
        }
        assertEquals(RefillState.ESCALATED, r.state);

        service.tick(oslo("2026-11-17T09:00"));

        assertEquals(RefillState.OFFERED, r.state);
        assertEquals("osl01", r.assignee);
    }

    @Test
    void whoBuysRotatesToWhoeverHasDoneFewestRefills() {
        List<String> buyers = new ArrayList<>();
        Instant t = MON_10;
        for (int i = 0; i < 3; i++) {
            Refill r = service.reportEmpty("OSL", "someone", t);
            buyers.add(r.assignee);
            service.accept(r.id, r.assignee, t);
            service.complete(r.id, r.assignee, Money.of("400", "NOK"), "r.jpg", Set.of(), t.plusSeconds(3600));
            t = t.plusSeconds(7200);
        }
        assertEquals(List.of("osl01", "osl02", "osl03"), buyers);
    }

    @Test
    void awayPeopleAreSkippedAndVisitorsCountWhereTheyAre() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        for (int i = 1; i <= 10; i++) {
            person("osl" + (i < 10 ? "0" : "") + i).awayUntil = today.plusDays(7); // whole Oslo team on holiday
        }
        Volunteer visitor = person("blr01");
        visitor.visitOffice = "OSL";
        visitor.visitUntil = today.plusDays(10);

        Refill r = service.reportEmpty("OSL", "someone", MON_10);

        assertEquals("blr01", r.assignee, "a Bengaluru volunteer visiting Oslo can refill the Oslo box");
        Refill blr = service.reportEmpty("BLR", "someone", bengaluru("2026-11-16T11:00"));
        assertEquals(RefillState.ESCALATED, blr.state, "...and is not asked to refill Bengaluru while away from it");
    }

    @Test
    void nothingIsOfferedOutsideOfficeHours() {
        Refill r = service.reportEmpty("OSL", "someone", oslo("2026-11-16T20:00"));
        assertEquals(RefillState.REPORTED, r.state);
        assertNull(chat.last("osl01"));

        service.tick(oslo("2026-11-17T08:59"));
        assertEquals(RefillState.REPORTED, r.state);

        service.tick(oslo("2026-11-17T09:00"));
        assertEquals(RefillState.OFFERED, r.state);
    }

    @Test
    void offerDeadlineCountsWorkingHoursOnly() {
        Refill late = service.reportEmpty("OSL", "someone", oslo("2026-11-16T16:30"));
        assertEquals(oslo("2026-11-17T10:30"), late.deadline);

        assertEquals(oslo("2026-11-23T10:30"),
                WorkingHours.plus(Fakes.OSLO, oslo("2026-11-20T16:30"), RefillService.OFFER_WINDOW),
                "Friday afternoon rolls over the weekend to Monday");
    }

    @Test
    void completingFilesExactlyOneHrClaimWithAllFields() {
        Refill r = service.reportEmpty("OSL", "someone", MON_10);
        service.accept(r.id, "osl01", MON_10);
        hr.down = true;

        service.complete(r.id, "osl01", Money.of("845.50", "NOK"), "receipt-1.jpg", Set.of(), oslo("2026-11-16T15:00"));
        assertEquals(RefillState.REFILLED, r.state, "HR was down; the refill is still recorded");
        assertEquals(0, hr.claims.size());

        hr.down = false;
        service.tick(oslo("2026-11-16T15:05"));
        service.tick(oslo("2026-11-16T15:10"));

        assertEquals(1, hr.claims.size(), "the retry files the claim once, not on every tick");
        assertEquals(new Hr.Claim("osl01", Money.of("845.50", "NOK").amount(), "NOK",
                "Snack refill OSL-1 (OSL office)", "receipt-1.jpg"), hr.claims.get("HR-1"));
    }

    @Test
    void hrDecisionClosesTheRefill() {
        Refill paid = service.reportEmpty("OSL", "someone", MON_10);
        service.accept(paid.id, "osl01", MON_10);
        service.complete(paid.id, "osl01", Money.of("500", "NOK"), "r.jpg", Set.of(), MON_10);
        Refill rejected = service.reportEmpty("OSL", "someone", MON_10.plusSeconds(60));
        service.accept(rejected.id, "osl02", MON_10);
        service.complete(rejected.id, "osl02", Money.of("500", "NOK"), "r.jpg", Set.of(), MON_10.plusSeconds(120));

        hr.statuses.put(paid.claimId, Hr.Status.APPROVED);
        hr.statuses.put(rejected.claimId, Hr.Status.REJECTED);
        service.tick(oslo("2026-11-20T10:00"));

        assertEquals(RefillState.REIMBURSED, paid.state);
        assertEquals(RefillState.CLAIM_REJECTED, rejected.state);
        assertTrue(chat.last("osl02").contains("rejected"));
        assertTrue(chat.last("osl-coordinator").contains("HR rejected claim"));
    }
}
