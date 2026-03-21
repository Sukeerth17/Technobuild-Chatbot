package in.technobuild.chatbot.exception;

public class PiiDetectedException extends RuntimeException {

    private final int piiCount;

    public PiiDetectedException(String message, int piiCount) {
        super(message);
        this.piiCount = piiCount;
    }

    public int getPiiCount() {
        return piiCount;
    }
}
