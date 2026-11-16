# Snack Counter Automation

## The idea
People already know the routine: see the empty box, buy snacks, refill, send the receipt.
**I keep that routine and automate only the parts that break:** who goes, what happens if they don't, the money claim, the budget and the report.
Everything happens in the company chat. There is no new app and no hardware.

## From "empty" to "reimbursed"
1. **Someone taps "box is empty" in chat.** A refill is created. If one is already open, the person is told who is handling it.
2. **The bot asks one volunteer** by DM: "Can you refill it, up to 1 200 NOK?"
   - The volunteer is someone in that office today, not on holiday, who has done the fewest refills lately.
   - If they say no, or don't answer within 2 working hours, the bot asks the next person.
3. **The volunteer accepts.** Now nobody else can take it, and the channel is told "Ana is buying".
4. **The volunteer buys, refills and posts the receipt photo and amount in chat.**
   - If they haven't done it by the end of the next working day, the job is handed to someone else.
5. **The bot files the claim in HR right away**, with the amount, currency, description, receipt and employee ID.
6. **HR approves (1–10 days)** and the volunteer is told. The refill is now closed.

If nobody can do it, the office coordinator gets a message and the bot tries again the next day.
Messages go out only during office hours, so nobody is pinged at night.

## What people see in chat
There is no new app; the chat **is** the interface. A typical refill looks like this:
```
#snacks-oslo   Bot: Snack box reported empty by Priya. Finding someone to refill it.
DM to Ola      Bot: Can you refill the Oslo snack box (up to 1 200 NOK)?  [Accept] [Decline]
#snacks-oslo   Bot: Ola is buying snacks, so nobody else needs to.
DM to Kari     Bot: Thanks! The Oslo snack box is already being handled by Ola.   (Kari reported it again)
DM to Ola      Ola: [receipt photo] 845.50
#snacks-oslo   Bot: Snack box refilled by Ola. Thank you!
DM to Ola      Bot: HR approved your snack claim of 845.50 NOK.
```

## How each problem is solved
| Problem | Solution |
|---|---|
| Box stays empty because everyone thinks someone else will go | One named person is asked, with a deadline. If they don't act, it moves to the next person. |
| Two people buy on the same day | Only one open refill per office, and only one person can accept it. |
| A few people do all the refills | The person with the fewest recent refills is asked first. |
| People wait weeks for their money | The claim is sent to HR the moment the receipt is posted. |
| Nobody knows spending or what gets eaten | Every refill is recorded, and a monthly report is sent to management. When refilling, the volunteer can tap what was left in the box (what gets left over isn't being eaten). |

## Main parts and their data
| Part | What it keeps |
|---|---|
| Office | currency, monthly budget, time zone, chat channel, coordinator |
| Volunteer | employee ID, home office, visiting office (and until when), on holiday until |
| Refill | office, status, who is doing it, deadline, amount, receipt, HR claim ID |
| Chat (simulated) | sends messages, receives button clicks |
| HR system (simulated) | creates a claim, returns its status |
| Monthly report | nothing stored; calculated from the refills |

## Money and the two currencies
- **Each office spends in its own currency:** Oslo 6 000 NOK, Bengaluru 15 000 INR per month.
- **A visitor buys in the local currency.** A Bengaluru person in Oslo pays in NOK, and it counts against Oslo's budget. HR converts it to their salary currency, so we don't.
- **Before buying, the volunteer is told how much is left.** If the budget is used up, nobody is sent shopping.
- **If someone spends a bit too much, they still get paid back**, because it's their own money. It's flagged in the report.
- **The monthly report** shows each office in its own currency, plus one total in NOK using that month's exchange rate.

## What I deliberately did not build
- Sensors or cameras on the box: a person tapping "empty" is enough.
- Stock counting per item: the "what was left" tap answers what gets eaten.
- Reading receipts automatically (OCR), or my own approval step: HR already checks receipts.
- A website or dashboard: chat plus one monthly report is enough.
- Calendar or attendance integration: "decline" and "on holiday" cover hybrid work.

**Not built, but worth suggesting to management:** a company card for snacks would remove the reimbursement wait completely.

## Things I assumed (I would have asked)
- We can check a claim's status in HR.
- Office hours are 09–17 local time, Mon–Fri, and each office has one coordinator.
- Management wants the combined total in NOK.
- Visitors may refill the office they are visiting.

## Code and tests
Java 17 with JUnit 5. Run the tests with `./mvnw test`.
- `RefillService` has the main logic: the refill steps, choosing who buys and the budget check.
- `Chat` and `Hr` are simulated in the tests.

The tests prove that:
- only one of ten people accepting at once gets the refill;
- an unfinished refill is handed to someone else;
- budgets and totals are correct in NOK and INR.
