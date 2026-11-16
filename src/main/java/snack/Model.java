package snack;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/** Lifecycle of one refill, from "box is empty" to "volunteer has their money back". */
enum RefillState {
    REPORTED,        // someone said the box is empty; waiting for office hours to offer it
    OFFERED,         // offered to one named volunteer, who must accept or decline before the deadline
    COMMITTED,       // a volunteer is buying; nobody else may buy
    ESCALATED,       // nobody available or budget used up; coordinator told, anyone may still claim it
    REFILLED,        // receipt recorded; HR claim not yet accepted by the HR system (retried by tick)
    CLAIM_SUBMITTED, // HR has the claim and approval takes 1 to 10 days
    REIMBURSED,
    CLAIM_REJECTED;

    /** The box is still empty: a new "empty" report must not start a second refill. */
    boolean open() {
        return this == REPORTED || this == OFFERED || this == COMMITTED || this == ESCALATED;
    }

    /** Snacks were bought: counts towards the budget and towards the buyer's fair share. */
    boolean bought() {
        return ordinal() >= REFILLED.ordinal();
    }
}

/** One snack counter: its budget is in its own currency, whoever buys. */
record Office(String code, String currency, Money monthlyBudget, Money typicalMin, Money typicalMax,
              ZoneId zone, String channel, String coordinatorId) {
}

class Volunteer {
    final String id;
    final String homeOffice;
    boolean active = true;
    String visitOffice;      // set while visiting the other office
    LocalDate visitUntil;
    LocalDate awayUntil;     // holiday

    Volunteer(String id, String homeOffice) {
        this.id = id;
        this.homeOffice = homeOffice;
    }

    String officeOn(LocalDate day) {
        return visitOffice != null && !day.isAfter(visitUntil) ? visitOffice : homeOffice;
    }

    boolean awayOn(LocalDate day) {
        return awayUntil != null && !day.isAfter(awayUntil);
    }
}

class Refill {
    final String id;
    final String office;
    final String reportedBy;
    final Instant reportedAt;

    RefillState state = RefillState.REPORTED;
    String assignee;
    Instant deadline;
    LocalDate escalatedOn;
    final Set<String> passed = new HashSet<>(); // declined or timed out on this refill

    Money spendCap;
    Money amount;
    Instant purchasedAt;
    String receiptImage;
    Set<String> leftovers = Set.of(); // what was still in the box: the "what doesn't get eaten" signal
    boolean overCap;
    String claimId;
    Instant reimbursedAt;

    Refill(String id, String office, String reportedBy, Instant reportedAt) {
        this.id = id;
        this.office = office;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
    }
}
