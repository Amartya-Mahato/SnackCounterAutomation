package snack;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Simulated stand-ins for the chat tool and the HR system, plus a two-office setup shared by the tests. */
final class Fakes {
    private Fakes() {
    }

    record Message(String to, String text) {
    }

    static class FakeChat implements Chat {
        final List<Message> sent = new ArrayList<>();

        @Override
        public synchronized void dm(String employeeId, String text) {
            sent.add(new Message(employeeId, text));
        }

        @Override
        public synchronized void post(String channel, String text) {
            sent.add(new Message(channel, text));
        }

        List<String> to(String recipient) {
            return sent.stream().filter(m -> m.to().equals(recipient)).map(Message::text).toList();
        }

        String last(String recipient) {
            List<String> all = to(recipient);
            return all.isEmpty() ? null : all.get(all.size() - 1);
        }
    }

    static class FakeHr implements Hr {
        final Map<String, Claim> claims = new LinkedHashMap<>();
        final Map<String, Status> statuses = new LinkedHashMap<>();
        boolean down;

        @Override
        public String createClaim(Claim claim) {
            if (down) {
                throw new IllegalStateException("HR system unavailable");
            }
            String id = "HR-" + (claims.size() + 1);
            claims.put(id, claim);
            statuses.put(id, Status.PENDING);
            return id;
        }

        @Override
        public Status status(String claimId) {
            return statuses.get(claimId);
        }
    }

    static final ZoneId OSLO = ZoneId.of("Europe/Oslo");
    static final ZoneId BENGALURU = ZoneId.of("Asia/Kolkata");

    static final Office OSL = new Office("OSL", "NOK", Money.of("6000", "NOK"), Money.of("300", "NOK"),
            Money.of("1200", "NOK"), OSLO, "#snacks-oslo", "osl-coordinator");
    static final Office BLR = new Office("BLR", "INR", Money.of("15000", "INR"), Money.of("500", "INR"),
            Money.of("3000", "INR"), BENGALURU, "#snacks-bengaluru", "blr-coordinator");

    /** Local office time on a weekday: Monday 16 Nov 2026. */
    static Instant oslo(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(OSLO).toInstant();
    }

    static Instant bengaluru(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(BENGALURU).toInstant();
    }
}
