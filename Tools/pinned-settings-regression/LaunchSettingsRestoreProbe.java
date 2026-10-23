public final class LaunchSettingsRestoreProbe {
    private LaunchSettingsRestoreProbe() {
    }

    public static void verify() {
        org.telegram.ui.ActionBar.BaseFragment restored =
                LaunchSettingsRestoreProbeFactory.createRestoredCustomSettingsFragment(
                        "com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity");
        require(restored != null, "The AyuMoments settings page was not restored");
        require(restored.getClass().getName().equals(
                        "com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity"),
                "The restored settings page had the wrong type");

        require(LaunchSettingsRestoreProbeFactory.createRestoredCustomSettingsFragment(
                        "org.telegram.ui.ProfileActivity") == null,
                "A non-settings fragment was restored");
        require(LaunchSettingsRestoreProbeFactory.createRestoredCustomSettingsFragment(
                        "com.radolyn.ayugram.settings.MissingActivity") == null,
                "A missing settings class did not fail closed");
        require(LaunchSettingsRestoreProbeFactory.createRestoredCustomSettingsFragment(
                        "com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity$1") == null,
                "A nested class name was restored");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
