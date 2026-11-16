package snack;

import java.math.BigDecimal;

/** The external HR system. We create claims; HR approves them and pays in the employee's payroll currency. */
public interface Hr {

    record Claim(String employeeId, BigDecimal amount, String currency, String description, String receiptImage) {
    }

    enum Status { PENDING, APPROVED, REJECTED }

    /** Returns the HR claim id. */
    String createClaim(Claim claim);

    Status status(String claimId);
}
