package in.technobuild.chatbot.exception;

public class TokenBudgetExceededException extends RuntimeException {

    private final int remainingBudget;

    public TokenBudgetExceededException(String message) {
        super(message);
        this.remainingBudget = 0;
    }

    public TokenBudgetExceededException(String message, int remainingBudget) {
        super(message);
        this.remainingBudget = remainingBudget;
    }

    public int getRemainingBudget() {
        return remainingBudget;
    }
}
