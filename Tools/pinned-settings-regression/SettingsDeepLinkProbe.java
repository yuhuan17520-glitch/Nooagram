import java.util.ArrayList;
import java.util.List;

public final class SettingsDeepLinkProbe {
    private SettingsDeepLinkProbe() {
    }

    public static void verify() {
        SettingsHelperProbe.TestCallback callback = new SettingsHelperProbe.TestCallback();
        boolean[] unknown = {false};
        UriStub route = new UriStub(
                List.of("nasettings", "ayumoments"),
                "DeletedIconColor",
                "3"
        );

        SettingsHelperProbe.processDeepLink(null, route, callback, () -> unknown[0] = true);
        require(!unknown[0], "ayumoments was reported as an unknown setting");
        require(callback.fragment != null, "ayumoments did not present a fragment");
        require(callback.fragment.getClass().getName().equals(
                        "com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity"),
                "ayumoments routed to " + callback.fragment.getClass().getName());
        org.telegram.messenger.AndroidUtilities.flush();
        String imported = com.radolyn.ayugram.settings.BaseNekoXSettingsActivity.lastImport;
        require("DeletedIconColor=3".equals(imported),
                "ayumoments row import was not dispatched: " + imported);

        callback = new SettingsHelperProbe.TestCallback();
        unknown[0] = false;
        SettingsHelperProbe.processDeepLink(
                null,
                new UriStub(List.of("nasettings", "missing"), null, null),
                callback,
                () -> unknown[0] = true
        );
        require(unknown[0] && callback.fragment == null, "Unknown ayumoments sibling did not fail closed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

final class UriStub extends android.net.Uri {
    private final List<String> segments;
    private final String row;
    private final String value;

    UriStub(List<String> segments, String row, String value) {
        this.segments = segments;
        this.row = row;
        this.value = value;
    }

    @Override
    public List<String> getPathSegments() {
        return segments;
    }

    @Override
    public String getQueryParameter(String key) {
        if ("r".equals(key) || "row".equals(key)) return row;
        if ("v".equals(key) || "value".equals(key)) return value;
        return null;
    }
}
