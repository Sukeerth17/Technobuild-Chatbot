package in.technobuild.chatbot.exception;

public class SqlValidationException extends RuntimeException {

    private final String rejectedSql;

    public SqlValidationException(String message, String rejectedSql) {
        super(message);
        this.rejectedSql = rejectedSql;
    }

    public String getRejectedSql() {
        return rejectedSql;
    }
}
