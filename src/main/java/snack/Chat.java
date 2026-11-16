package snack;

/** The company's internal chat tool. Faked in tests; the real one would be a bot client. */
public interface Chat {
    void dm(String employeeId, String text);

    void post(String channel, String text);
}
