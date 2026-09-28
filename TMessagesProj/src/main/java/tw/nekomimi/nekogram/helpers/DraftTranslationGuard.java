package tw.nekomimi.nekogram.helpers;

public final class DraftTranslationGuard {
    private long draftVersion;
    private long requestVersion;

    public void draftChanged() {
        draftVersion++;
    }

    public Ticket begin() {
        return new Ticket(++requestVersion, draftVersion);
    }

    public void cancel() {
        requestVersion++;
    }

    public boolean canApply(Ticket ticket) {
        return ticket != null && ticket.request == requestVersion && ticket.draft == draftVersion;
    }

    public static final class Ticket {
        private final long request;
        private final long draft;

        private Ticket(long request, long draft) {
            this.request = request;
            this.draft = draft;
        }
    }
}
