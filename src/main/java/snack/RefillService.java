package snack;

import static snack.RefillState.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Coordinates refills. People still notice the empty box, shop and pay as they do today;
 * this decides who goes, hands the job on when nobody acts, guards the budget and files the HR claim.
 *
 * Every public method is called by the chat bot (a button or command) or by a scheduler ({@link #tick}).
 */
// ponytail: one lock for all counters; use a lock per counter, or a DB unique constraint on
// "one open refill per counter", once this runs on more than one instance.
public class RefillService {
    static final Duration OFFER_WINDOW = Duration.ofHours(2);
    static final int FAIRNESS_DAYS = 90;

    private final Map<String, Office> offices = new LinkedHashMap<>();
    private final Map<String, Volunteer> volunteers = new TreeMap<>();
    private final List<Refill> refills = new ArrayList<>();
    private final Chat chat;
    private final Hr hr;
    private int seq;

    RefillService(Chat chat, Hr hr, Collection<Office> offices, Collection<Volunteer> volunteers) {
        this.chat = chat;
        this.hr = hr;
        offices.forEach(o -> this.offices.put(o.code(), o));
        volunteers.forEach(v -> this.volunteers.put(v.id, v));
    }

    /** Anyone (volunteer or not) reports the box is empty. Repeat reports join the open refill. */
    public synchronized Refill reportEmpty(String officeCode, String reporterId, Instant now) {
        Office o = office(officeCode);
        Refill open = openRefill(officeCode);
        if (open != null) {
            chat.dm(reporterId, "Thanks! The " + officeCode + " snack box is already being handled"
                    + (open.assignee == null ? "." : " by " + open.assignee + "."));
            return open;
        }
        Refill r = new Refill(officeCode + "-" + (++seq), officeCode, reporterId, now);
        refills.add(r);
        chat.post(o.channel(), "Snack box reported empty by " + reporterId + ". Finding someone to refill it.");
        dispatch(r, now);
        return r;
    }

    /**
     * Commit to buying. Works for the person it was offered to, and for any eligible volunteer who
     * steps up on their own ("I'll do it" in the channel). The first commit wins; everyone else gets false.
     */
    public synchronized boolean accept(String refillId, String volunteerId, Instant now) {
        Refill r = refill(refillId);
        Office o = office(r.office);
        boolean offeredToMe = r.state == OFFERED && volunteerId.equals(r.assignee);
        boolean stepsUp = r.state.open() && r.state != COMMITTED && eligible(volunteers.get(volunteerId), o, now);
        if (!offeredToMe && !stepsUp) {
            chat.dm(volunteerId, r.state == COMMITTED
                    ? "Thanks, but " + r.assignee + " is already buying for " + o.code() + "."
                    : "Refill " + r.id + " is not open to you.");
            return false;
        }
        Money cap = remaining(o, now).min(o.typicalMax());
        if (cap.lessThan(o.typicalMin())) {
            chat.dm(volunteerId, "Sorry, the " + o.code() + " snack budget for this month is used up.");
            return false;
        }
        r.state = COMMITTED;
        r.assignee = volunteerId;
        r.spendCap = cap;
        r.deadline = WorkingHours.endOfNextWorkday(o.zone(), now);
        chat.dm(volunteerId, "Thanks! Please refill the " + o.code() + " box, spend at most " + cap
                + ", and send the receipt here when done.");
        chat.post(o.channel(), volunteerId + " is buying snacks, so nobody else needs to.");
        return true;
    }

    public synchronized void decline(String refillId, String volunteerId, Instant now) {
        Refill r = refill(refillId);
        if (r.state == OFFERED && volunteerId.equals(r.assignee)) {
            passOn(r, now);
        }
    }

    /** The buyer sends the receipt. The HR claim is filed immediately, so nobody waits on paperwork. */
    public synchronized boolean complete(String refillId, String volunteerId, Money amount, String receiptImage,
                                         Set<String> leftovers, Instant now) {
        Refill r = refill(refillId);
        Office o = office(r.office);
        if (r.state != COMMITTED || !volunteerId.equals(r.assignee)) {
            chat.dm(volunteerId, "Refill " + r.id + " is not assigned to you any more. If you already bought snacks, contact "
                    + o.coordinatorId() + ".");
            return false;
        }
        if (!amount.currency().equals(o.currency()) || amount.amount().signum() <= 0) {
            chat.dm(volunteerId, "Please enter the receipt total in " + o.currency() + ", the currency you paid in.");
            return false;
        }
        r.amount = amount;
        r.receiptImage = receiptImage;
        r.leftovers = Set.copyOf(leftovers);
        r.purchasedAt = now;
        // Over the cap is flagged, never refused: the volunteer has already spent their own money.
        r.overCap = r.spendCap.lessThan(amount);
        r.state = REFILLED;
        chat.post(o.channel(), "Snack box refilled by " + volunteerId + ". Thank you!");
        submitClaim(r, o);
        return true;
    }

    /** Run by a scheduler every few minutes: deadlines, retries and HR status. */
    public synchronized void tick(Instant now) {
        for (Refill r : refills) {
            Office o = office(r.office);
            switch (r.state) {
                case REPORTED -> dispatch(r, now);
                case ESCALATED -> {
                    if (r.escalatedOn.isBefore(today(o, now))) { // new day: try the whole pool again
                        r.passed.clear();
                        r.state = REPORTED;
                        dispatch(r, now);
                    }
                }
                case OFFERED -> {
                    if (!now.isBefore(r.deadline)) {
                        chat.dm(r.assignee, "No answer, so refill " + r.id + " went to someone else.");
                        passOn(r, now);
                    }
                }
                case COMMITTED -> {
                    if (!now.isBefore(r.deadline)) {
                        chat.dm(r.assignee, "Refill " + r.id + " was not completed in time and has been handed to someone else.");
                        r.spendCap = null;
                        passOn(r, now);
                    }
                }
                case REFILLED -> submitClaim(r, o);
                case CLAIM_SUBMITTED -> {
                    Hr.Status status = hr.status(r.claimId);
                    if (status == Hr.Status.APPROVED) {
                        r.state = REIMBURSED;
                        r.reimbursedAt = now;
                        chat.dm(r.assignee, "HR approved your snack claim of " + r.amount + ".");
                    } else if (status == Hr.Status.REJECTED) {
                        r.state = CLAIM_REJECTED;
                        chat.dm(r.assignee, "HR rejected your snack claim of " + r.amount + ". " + o.coordinatorId() + " will follow up.");
                        chat.dm(o.coordinatorId(), "HR rejected claim " + r.claimId + " for refill " + r.id + ".");
                    }
                }
                default -> {
                }
            }
        }
    }

    public synchronized MonthlyReport monthlyReport(YearMonth month, Map<String, java.math.BigDecimal> ratesToNok) {
        return MonthlyReport.build(offices.values(), office -> bought(office, month), month, ratesToNok);
    }

    // ---- choosing who buys -------------------------------------------------------------

    /** Available volunteer in this office with the fewest refills lately; ties go to whoever bought longest ago. */
    Volunteer chooseBuyer(Refill r, Office o, Instant now) {
        Instant since = now.minus(Duration.ofDays(FAIRNESS_DAYS));
        return volunteers.values().stream()
                .filter(v -> eligible(v, o, now) && !r.passed.contains(v.id) && !holdsOpenRefill(v.id))
                .min(Comparator.comparingLong((Volunteer v) -> recentPurchases(v.id, since).size())
                        .thenComparing(v -> recentPurchases(v.id, since).stream().max(Comparator.naturalOrder()).orElse(Instant.EPOCH))
                        .thenComparing(v -> v.id))
                .orElse(null);
    }

    private List<Instant> recentPurchases(String volunteerId, Instant since) {
        return refills.stream()
                .filter(x -> x.state.bought() && volunteerId.equals(x.assignee) && x.purchasedAt.isAfter(since))
                .map(x -> x.purchasedAt)
                .toList();
    }

    /** Eligibility follows where the person is this week, not their home office. */
    private boolean eligible(Volunteer v, Office o, Instant now) {
        LocalDate day = today(o, now);
        return v != null && v.active && o.code().equals(v.officeOn(day)) && !v.awayOn(day);
    }

    private boolean holdsOpenRefill(String volunteerId) {
        return refills.stream().anyMatch(x -> (x.state == OFFERED || x.state == COMMITTED) && volunteerId.equals(x.assignee));
    }

    // ---- lifecycle helpers -------------------------------------------------------------

    private void dispatch(Refill r, Instant now) {
        Office o = office(r.office);
        if (!WorkingHours.isOpen(o.zone(), now)) {
            return; // stays REPORTED; offered when the office opens
        }
        Money cap = remaining(o, now).min(o.typicalMax());
        if (cap.lessThan(o.typicalMin())) {
            escalate(r, o, now, "this month's snack budget is used up");
            return;
        }
        Volunteer v = chooseBuyer(r, o, now);
        if (v == null) {
            escalate(r, o, now, "no volunteer is available");
            return;
        }
        r.state = OFFERED;
        r.assignee = v.id;
        r.deadline = WorkingHours.plus(o.zone(), now, OFFER_WINDOW);
        chat.dm(v.id, "Can you refill the " + o.code() + " snack box (up to " + cap + ")? Accept or decline within "
                + OFFER_WINDOW.toHours() + " working hours. [" + r.id + "]");
    }

    private void passOn(Refill r, Instant now) {
        r.passed.add(r.assignee);
        r.assignee = null;
        r.deadline = null;
        r.state = REPORTED;
        dispatch(r, now);
    }

    private void escalate(Refill r, Office o, Instant now, String why) {
        r.state = ESCALATED;
        r.escalatedOn = today(o, now);
        chat.post(o.channel(), "The " + o.code() + " snack box is still empty: " + why + ".");
        chat.dm(o.coordinatorId(), "Refill " + r.id + " needs attention: " + why + ".");
    }

    private void submitClaim(Refill r, Office o) {
        if (r.claimId != null) {
            return; // never file the same refill twice
        }
        try {
            r.claimId = hr.createClaim(new Hr.Claim(r.assignee, r.amount.amount(), r.amount.currency(),
                    "Snack refill " + r.id + " (" + o.code() + " office)", r.receiptImage));
            r.state = CLAIM_SUBMITTED;
        } catch (RuntimeException e) {
            // Stays REFILLED and tick() retries.
            // ponytail: if HR created the claim but the reply was lost, a retry files it twice; needs an idempotency key from HR.
        }
    }

    // ---- budget ------------------------------------------------------------------------

    /** Budget left this month, in the office's own currency. No conversion, so no rounding drift. */
    Money remaining(Office o, Instant now) {
        YearMonth month = YearMonth.from(now.atZone(o.zone()));
        Money spent = bought(o, month).stream().map(x -> x.amount).reduce(Money.zero(o.currency()), Money::plus);
        return o.monthlyBudget().minus(spent);
    }

    /** Refills bought at this counter in this month, by the counter's local calendar. */
    private List<Refill> bought(Office o, YearMonth month) {
        return refills.stream()
                .filter(x -> x.office.equals(o.code()) && x.state.bought()
                        && YearMonth.from(x.purchasedAt.atZone(o.zone())).equals(month))
                .toList();
    }

    // ---- lookups -----------------------------------------------------------------------

    private Refill openRefill(String officeCode) {
        return refills.stream().filter(x -> x.office.equals(officeCode) && x.state.open()).findFirst().orElse(null);
    }

    Refill refill(String id) {
        return refills.stream().filter(x -> x.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown refill " + id));
    }

    private Office office(String code) {
        Office o = offices.get(code);
        if (o == null) {
            throw new IllegalArgumentException("Unknown office " + code);
        }
        return o;
    }

    private static LocalDate today(Office o, Instant now) {
        return now.atZone(o.zone()).toLocalDate();
    }
}
