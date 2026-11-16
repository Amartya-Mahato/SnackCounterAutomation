package snack;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** The one report management asked for: each office in its own currency, plus one total in NOK. */
public record MonthlyReport(YearMonth month, List<Line> offices, Money totalNok, Map<String, BigDecimal> ratesToNok) {

    public record Line(String office, Money spent, Money budget, int refills, int overCap,
                       Map<String, Long> refillsByVolunteer, Map<String, Long> leftOver) {
    }

    static MonthlyReport build(Collection<Office> offices, Function<Office, List<Refill>> boughtIn,
                               YearMonth month, Map<String, BigDecimal> ratesToNok) {
        List<Line> lines = new ArrayList<>();
        Money total = Money.zero("NOK");
        for (Office o : offices) {
            List<Refill> rs = boughtIn.apply(o);
            Money spent = rs.stream().map(r -> r.amount).reduce(Money.zero(o.currency()), Money::plus);
            BigDecimal rate = o.currency().equals("NOK") ? BigDecimal.ONE : ratesToNok.get(o.currency());
            if (rate == null) {
                throw new IllegalArgumentException("No NOK rate given for " + o.currency());
            }
            total = total.plus(spent.convert(rate, "NOK"));
            lines.add(new Line(o.code(), spent, o.monthlyBudget(), rs.size(),
                    (int) rs.stream().filter(r -> r.overCap).count(),
                    rs.stream().collect(groupingBy(r -> r.assignee, TreeMap::new, counting())),
                    rs.stream().flatMap(r -> r.leftovers.stream()).collect(groupingBy(t -> t, TreeMap::new, counting()))));
        }
        return new MonthlyReport(month, List.copyOf(lines), total, Map.copyOf(ratesToNok));
    }

    /** Text posted to management's chat channel on the 1st of each month. */
    public String render() {
        StringBuilder sb = new StringBuilder("Snack spending " + month + "\n");
        for (Line l : offices) {
            sb.append(String.format("%s: %s of %s budget, %d refills (%d over cap), refills by %s, left over: %s%n",
                    l.office(), l.spent(), l.budget(), l.refills(), l.overCap(), l.refillsByVolunteer(), l.leftOver()));
        }
        return sb.append("Total: ").append(totalNok).append(" (rates to NOK: ").append(new TreeMap<>(ratesToNok)).append(")").toString();
    }
}
