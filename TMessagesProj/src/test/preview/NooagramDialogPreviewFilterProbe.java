package org.telegram.ui.Cells;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import tw.nekomimi.nekogram.filters.AyuFilter;

import java.util.List;

public class NooagramDialogPreviewFilterProbe {
    private static final MessagesStorage storage = MessagesStorage.getInstance(0);
    private static final ConnectionsManager network = ConnectionsManager.instance;
    private static int passed;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static TLRPC.Message raw(int id) {
        TLRPC.Message message = new TLRPC.Message();
        message.id = id;
        message.date = id;
        message.dialog_id = -77;
        return message;
    }

    private static MessageObject add(int id) {
        TLRPC.Message message = raw(id);
        storage.database.rows.add(message);
        return new MessageObject(0, message, false, false);
    }

    private static TLRPC.messages_Messages page(int newest, int oldest) {
        TLRPC.messages_Messages page = new TLRPC.messages_Messages();
        for (int id = newest; id >= oldest; id--) page.messages.add(raw(id));
        return page;
    }

    private static DialogCell cell(MessageObject source) {
        DialogCell cell = new DialogCell();
        cell.source = source;
        cell.refreshFilteredPreview();
        return cell;
    }

    private static void drain() {
        int iterations = 0;
        while (!storage.queue.pending.isEmpty() || !AndroidUtilities.ui.isEmpty()) {
            check(++iterations < 1000, "Queue/notification loop");
            if (!storage.queue.pending.isEmpty()) storage.queue.next();
            if (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
        }
    }

    private static void reset() {
        NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.appDidLogout);
        UserConfig.getInstance(0).userId = 10;
        UserConfig.getInstance(0).loginTime++;
        storage.database.rows.clear();
        storage.database.scans = 0;
        storage.database.fail = false;
        storage.queue.pending.clear();
        storage.failWrite = false;
        storage.writes = 0;
        network.requests.clear();
        network.offsets.clear();
        AndroidUtilities.ui.clear();
        AndroidUtilities.delayed.clear();
        AyuFilter.regex.clear();
        AyuFilter.blocked.clear();
        AyuFilter.hideRegex = AyuFilter.hideBlocked = true;
        MessagesController.instance.chat.megagroup = true;
    }

    private static void test(String name, Runnable body) {
        reset();
        body.run();
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) {
        test("R15 recycled cell completes shared state", () -> {
            MessageObject top = add(100);
            add(90);
            AyuFilter.regex.add(100);
            DialogCell first = cell(top);
            first.dialog = -88;
            drain();
            check(first.applied == null, "Recycled cell received old dialog");
            DialogCell second = cell(top);
            check(second.applied != null && second.applied.getId() == 90, "Shared result stranded in loading");
        });
        test("R16 newest eligible replaces old preview", () -> {
            AyuFilter.regex.addAll(List.of(100, 102));
            MessageObject top = add(100);
            add(90);
            DialogCell cell = cell(top);
            drain();
            check(cell.applied.getId() == 90, "Initial preview");
            add(101);
            cell.source = add(102);
            cell.refreshFilteredPreview();
            check(cell.applied != null && cell.applied.getId() == 90, "Known visible preview disappeared during refresh");
            drain();
            check(cell.applied.getId() == 101, "Old replacement beat newer eligible message");
        });
        test("A newly filtered top immediately keeps the previous normal preview", () -> {
            DialogCell cell = cell(add(90));
            AyuFilter.regex.add(100);
            add(95);
            cell.source = add(100);
            cell.refreshFilteredPreview();
            check(cell.applied != null && cell.applied.getId() == 90, "Visible source was not remembered");
            drain();
            check(cell.applied.getId() == 95, "Did not select the closest unfiltered message");
        });
        test("Rule changes keep only revalidated visible previews", () -> {
            DialogCell cell = cell(add(90));
            cell.source = add(95); cell.refreshFilteredPreview();
            cell.source = add(100); cell.refreshFilteredPreview();
            AyuFilter.regex.add(100);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.regexFiltersUpdated);
            cell.refreshFilteredPreview();
            check(cell.applied != null && cell.applied.getId() == 95, "Rule change discarded valid predecessor");
            drain();
            AyuFilter.regex.add(95);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.regexFiltersUpdated);
            AndroidUtilities.ui.remove().run();
            check(cell.applied != null && cell.applied.getId() == 90, "Newly filtered cached preview leaked");
            drain();
            check(cell.applied.getId() == 90, "Wrong preview after filtering both newer messages");
        });
        test("An invalid cached replacement is searched again without a minute-long wait", () -> {
            AyuFilter.regex.add(100);
            DialogCell cell = cell(add(100)); add(90);
            drain();
            AyuFilter.regex.add(90); add(85);
            cell.refreshFilteredPreview();
            check(cell.applied == null, "Newly hidden replacement was retained");
            drain();
            check(cell.applied != null && cell.applied.getId() == 85, "Invalid replacement waited for cooldown");
        });
        test("Transient database failure retains a confirmed visible preview", () -> {
            DialogCell cell = cell(add(90));
            AyuFilter.regex.add(100); cell.source = add(100);
            storage.database.fail = true;
            cell.refreshFilteredPreview(); drain();
            check(cell.applied != null && cell.applied.getId() == 90, "Failure cleared the normal preview");
            storage.database.fail = false; add(95);
            AndroidUtilities.advance(3000); drain();
            check(cell.applied.getId() == 95, "Retry did not refresh retained preview");
        });
        test("Remembered previews cannot cross login identities", () -> {
            cell(add(90));
            UserConfig.getInstance(0).userId = 20;
            storage.database.rows.clear();
            AyuFilter.regex.add(100);
            DialogCell current = cell(add(100)); add(85);
            check(current.applied == null, "Previous login's preview was reused");
            drain();
            check(current.applied.getId() == 85, "New login did not resolve its own preview");
        });
        test("Remembered previews cannot cross full dialog identities", () -> {
            cell(add(90));
            AyuFilter.regex.add(100);
            TLRPC.Message source = raw(100); source.dialog_id = -88;
            DialogCell other = new DialogCell(); other.dialog = -88;
            other.source = new MessageObject(0, source, false, false);
            other.refreshFilteredPreview();
            check(other.applied == null, "Different dialog received remembered preview");
        });
        test("Deleting a preview discards it before a later source change", () -> {
            AyuFilter.regex.addAll(List.of(100, 101));
            DialogCell cell = cell(add(100)); add(90); add(85); drain();
            check(cell.applied.getId() == 90, "Initial preview missing");
            storage.database.rows.removeIf(message -> message.id == 90);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.messagesDeleted, List.of(90), 77L, false);
            drain();
            check(cell.applied.getId() == 85, "Deleted preview remained cached");
            cell.source = add(101); cell.refreshFilteredPreview();
            check(cell.applied.getId() == 85, "Source change resurrected a deleted preview");
            drain();
        });
        test("Manually hidden messages cannot become replacement previews", () -> {
            DialogCell cell = cell(add(90));
            MessageObject newer = add(95); newer.messageOwner.hide = true;
            cell.source = add(100); cell.source.messageOwner.hide = true;
            cell.refreshFilteredPreview(); drain();
            check(cell.applied.getId() == 90, "Manually hidden candidate leaked");
        });
        test("An edited preview is replaced without clearing its normal content first", () -> {
            AyuFilter.regex.add(100);
            DialogCell cell = cell(add(100)); add(90); drain();
            TLRPC.Message replacement = raw(90); replacement.edit_date = 999;
            storage.database.rows.removeIf(message -> message.id == 90);
            storage.database.rows.add(replacement);
            MessageObject edited = new MessageObject(0, replacement, false, false);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.replaceMessagesObjects, -77L, List.of(edited), false);
            AndroidUtilities.ui.remove().run();
            check(cell.applied == edited, "Edited normal preview was not kept while rechecking");
            drain();
            check(cell.applied.messageOwner.edit_date == 999, "Stale preview body was restored");
        });
        test("A replacement that becomes hidden after editing falls back to another normal message", () -> {
            DialogCell cell = cell(add(80));
            cell.source = add(90); cell.refreshFilteredPreview();
            AyuFilter.regex.add(100); cell.source = add(100); cell.refreshFilteredPreview(); drain();
            TLRPC.Message replacement = raw(90); replacement.edit_date = 999; replacement.hide = true;
            storage.database.rows.removeIf(message -> message.id == 90);
            storage.database.rows.add(replacement); add(85);
            MessageObject edited = new MessageObject(0, replacement, false, false);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.replaceMessagesObjects, -77L, List.of(edited), false);
            AndroidUtilities.ui.remove().run();
            check(cell.applied != null && cell.applied.getId() == 80, "Edited hidden preview was retained");
            drain();
            check(cell.applied.getId() == 85, "Did not find the closest visible message after editing");
        });
        test("Another channel's deletion cannot clear the current normal preview", () -> {
            AyuFilter.regex.addAll(List.of(100, 102));
            DialogCell cell = cell(add(100)); add(90); drain();
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.messagesDeleted, List.of(90), 88L, false);
            drain();
            cell.source = add(102); cell.refreshFilteredPreview();
            check(cell.applied != null && cell.applied.getId() == 90, "Unrelated deletion cleared remembered preview");
            drain();
        });
        test("Private deletion cannot invalidate a channel with the same message id", () -> {
            AyuFilter.regex.add(100);
            DialogCell channel = cell(add(100)); add(90); drain();
            TLRPC.Message previous = raw(90); previous.dialog_id = 777;
            storage.database.rows.add(previous);
            TLRPC.Message top = raw(100); top.dialog_id = 777;
            storage.database.rows.add(top);
            TLRPC.Message older = raw(85); older.dialog_id = 777;
            storage.database.rows.add(older);
            DialogCell personal = new DialogCell(); personal.dialog = 777;
            personal.source = new MessageObject(0, top, false, false);
            personal.refreshFilteredPreview(); drain();
            check(personal.applied.getId() == 90, "Initial private preview missing");
            storage.database.rows.removeIf(message -> message.dialog_id == 777 && message.id == 90);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.messagesDeleted, List.of(90), 0L, false);
            drain();
            check(personal.applied.getId() == 85, "Private deleted preview was retained");
            check(channel.applied.getId() == 90, "Private deletion cleared a channel's preview");
        });
        test("Chat show-filtered state cannot leak a hidden message into the preview", () -> {
            DialogCell cell = cell(add(90));
            MessageObject top = add(100);
            top.skipAyuFiltering = true;
            AyuFilter.regex.add(100);
            cell.source = top;
            cell.refreshFilteredPreview();
            check(cell.applied != null && cell.applied.getId() == 90, "Chat rendering flag bypassed preview filtering");
            check(top.skipAyuFiltering, "Preview filtering changed the chat rendering flag");
            drain();
            check(cell.applied.getId() == 90, "Hidden source was cached as a normal preview");
        });
        test("Pending failed and exhausted searches are distinct states", () -> {
            AyuFilter.regex.addAll(List.of(100, 99));
            DialogCell cell = cell(add(100));
            check(NooagramDialogPreviewFilter.getStatus(0, -77) == NooagramDialogPreviewFilter.SEARCHING,
                    "Initial search claimed every message was filtered");
            drain(); network.respond(null, true); drain();
            check(NooagramDialogPreviewFilter.getStatus(0, -77) == NooagramDialogPreviewFilter.UNAVAILABLE,
                    "Network failure claimed history was exhausted");
            AndroidUtilities.advance(3000); drain(); network.respond(page(99, 99), false); drain();
            check(cell.applied == null && NooagramDialogPreviewFilter.getStatus(0, -77) == NooagramDialogPreviewFilter.EXHAUSTED,
                    "Confirmed exhausted history had wrong state");
        });
        test("R17 account identity swap rejects queued callback", () -> {
            AyuFilter.regex.add(100);
            MessageObject top = add(100);
            add(90);
            DialogCell old = cell(top);
            storage.queue.next();
            UserConfig.getInstance(0).userId = 20;
            storage.database.rows.clear();
            MessageObject newTop = add(100);
            add(95);
            DialogCell current = cell(newTop);
            drain();
            check(old.applied == null, "Old login callback applied");
            check(current.applied.getId() == 95, "New login reused old history");
        });
        test("R17 same-user relogin rejects old session", () -> {
            AyuFilter.regex.add(100);
            MessageObject top = add(100);
            add(90);
            DialogCell old = cell(top);
            storage.queue.next();
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.appDidLogout);
            storage.database.rows.clear();
            MessageObject newTop = add(100);
            add(95);
            DialogCell current = cell(newTop);
            drain();
            check(old.applied == null && current.applied.getId() == 95, "Logout generation failed");
        });
        test("R17 late network response after logout cannot write or publish", () -> {
            AyuFilter.regex.add(200);
            DialogCell old = cell(add(200));
            drain();
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.appDidLogout);
            UserConfig.getInstance(0).userId = 20;
            storage.database.rows.clear();
            MessageObject top = add(200);
            add(195);
            DialogCell current = cell(top);
            network.respond(page(199, 120), false);
            drain();
            check(storage.writes == 0 && old.applied == null, "Old network response wrote into new login");
            check(current.applied.getId() == 195, "Old network response replaced new preview");
        });
        test("R18 pagination follows storage and reaches page two", () -> {
            AyuFilter.regex.add(200);
            DialogCell cell = cell(add(200));
            for (int id = 121; id <= 200; id++) AyuFilter.regex.add(id);
            drain();
            network.respond(page(200, 121), false);
            AndroidUtilities.ui.remove().run();
            check(storage.writes == 0 && network.offsets.equals(List.of(0)), "Advanced before storage");
            drain();
            check(network.offsets.equals(List.of(0, 121)), "Wrong continuation offset");
            network.respond(page(120, 41), false);
            drain();
            check(cell.applied.getId() == 120, "Page two eligible message missing");
            check(network.requests.isEmpty(), "Continued after finding result");
        });
        test("R18 candidate beyond local cap is read directly", () -> {
            for (int id = 1201; id <= 2000; id++) { add(id); AyuFilter.regex.add(id); }
            DialogCell cell = cell(new MessageObject(0, raw(2000), false, false));
            drain();
            check(storage.database.scans == 10, "Local search exceeded cap");
            check(network.offsets.equals(List.of(1201)), "History did not start after scanned window");
            network.respond(page(1200, 1121), false);
            drain();
            check(cell.applied.getId() == 1200, "Fetched candidate remained outside scan window");
            check(storage.database.scans == 10, "Fetched page restarted the capped local scan");
            AndroidUtilities.advance(60000);
            cell.refreshFilteredPreview();
            drain();
            check(cell.applied.getId() == 1200, "Bounded rescan discarded known eligible preview");
            check(network.offsets.size() == 1, "Valid fallback unnecessarily continued history");
            storage.database.rows.removeIf(message -> message.id == 1200);
            AyuFilter.regex.addAll(java.util.stream.IntStream.rangeClosed(1121, 1199).boxed().toList());
            AndroidUtilities.advance(60000);
            cell.refreshFilteredPreview();
            drain();
            network.respond(new TLRPC.messages_Messages(), false);
            drain();
            check(cell.applied == null, "Deleted fallback was retained");
        });
        test("R18 failure retries same offset once automatically", () -> {
            AyuFilter.regex.add(200);
            cell(add(200));
            drain();
            network.respond(null, true);
            drain();
            AndroidUtilities.advance(3000);
            drain();
            check(network.offsets.equals(List.of(0, 0)), "Failed page not retried");
            network.respond(null, true);
            drain();
            AndroidUtilities.advance(3000);
            drain();
            check(network.offsets.size() == 2, "Unbounded automatic retry loop");
        });
        test("R18 failed storage cannot advance offset or publish preview", () -> {
            AyuFilter.regex.add(200);
            DialogCell cell = cell(add(200));
            drain();
            storage.failWrite = true;
            network.respond(page(199, 120), false);
            drain();
            check(cell.applied == null && network.offsets.equals(List.of(0)), "Published unwritten response");
            storage.failWrite = false;
            AndroidUtilities.advance(3000);
            drain();
            check(network.offsets.equals(List.of(0, 0)), "Storage failure skipped page");
            network.respond(page(199, 120), false);
            drain();
            check(cell.applied.getId() == 199, "Retry did not recover");
        });
        test("R18 history page budget and stalled request are bounded", () -> {
            AyuFilter.regex.add(500);
            cell(add(500));
            for (int id = 1; id <= 500; id++) AyuFilter.regex.add(id);
            drain();
            for (int p = 0; p < 5; p++) { network.respond(page(500 - p * 80, 421 - p * 80), false); drain(); }
            check(network.offsets.size() == 5 && network.requests.isEmpty(), "History budget exceeded");
        });
        test("Five filtered pages continue after cooldown and restore the older normal preview", () -> {
            for (int id = 101; id <= 500; id++) AyuFilter.regex.add(id);
            DialogCell cell = cell(add(500)); drain();
            for (int p = 0; p < 5; p++) { network.respond(page(500 - p * 80, 421 - p * 80), false); drain(); }
            check(cell.applied == null && network.offsets.size() == 5, "First pass exceeded its budget");
            check(NooagramDialogPreviewFilter.getStatus(0, -77) == NooagramDialogPreviewFilter.SEARCHING,
                    "Page cap was mistaken for the end of history");
            AndroidUtilities.advance(59999); drain();
            check(network.offsets.size() == 5, "History resumed before cooldown");
            AndroidUtilities.advance(1); drain();
            check(network.offsets.size() == 6 && network.offsets.get(5) == 101, "Lost continuation cursor");
            network.respond(page(100, 21), false); drain();
            check(cell.applied != null && cell.applied.getId() == 100, "Older normal preview not restored");
            AndroidUtilities.advance(60000); drain();
            check(network.offsets.size() == 6, "Kept paging after finding preview");
        });
        test("Detached cells stop continuation until reattached", () -> {
            for (int id = 101; id <= 500; id++) AyuFilter.regex.add(id);
            DialogCell cell = cell(add(500)); drain();
            for (int p = 0; p < 5; p++) { network.respond(page(500 - p * 80, 421 - p * 80), false); drain(); }
            cell.attached = false; AndroidUtilities.advance(60000); drain();
            check(network.offsets.size() == 5, "Detached cell kept requesting history");
            cell.attached = true; cell.refreshFilteredPreview(); drain();
            check(network.offsets.size() == 6 && network.offsets.get(5) == 101, "Reattach lost its cursor");
        });
        test("Exhausted all-filtered history does not cause a request loop", () -> {
            AyuFilter.regex.addAll(List.of(100, 99));
            DialogCell cell = cell(add(100)); drain();
            network.respond(page(99, 99), false); drain();
            check(cell.applied == null, "Filtered message appeared in preview");
            AndroidUtilities.advance(60000); drain();
            check(network.offsets.size() == 1, "Exhausted history kept paging");
        });
        test("R18 timeout releases loading and schedules retry", () -> {
            AyuFilter.regex.add(200);
            cell(add(200));
            drain();
            AndroidUtilities.advance(30000);
            drain();
            check(!network.cancelled.isEmpty(), "Stalled request was not cancelled");
            AndroidUtilities.advance(3000);
            drain();
            check(network.offsets.size() == 2, "Timeout left loading stuck");
        });
        test("R19 source and candidates share blocked and regex policy", () -> {
            AyuFilter.blocked.addAll(List.of(100, 99));
            AyuFilter.regex.add(98);
            MessageObject top = add(100);
            add(99); add(98); add(97);
            DialogCell cell = cell(top);
            drain();
            check(cell.applied.getId() == 97, "Hidden candidate applied");
            AyuFilter.hideRegex = false;
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.regexFiltersUpdated);
            drain();
            check(cell.applied.getId() == 98, "Regex masking treated as hiding");
            MessagesController.instance.chat.megagroup = false;
            check(!NooagramDialogPreviewFilter.isHidden(0, -77, top), "Blocked policy leaked outside megagroups");
        });
        test("R20 rule invalidation refreshes waiting cells without notification loop", () -> {
            AyuFilter.regex.addAll(List.of(100, 99));
            MessageObject top = add(100);
            add(99); add(98);
            DialogCell cell = cell(top);
            drain();
            check(cell.applied.getId() == 98, "Initial rules");
            AyuFilter.regex.remove(99);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.regexFiltersUpdated);
            drain();
            check(cell.applied.getId() == 99 && cell.refreshes < 10, "Rules did not refresh latest preview");
        });
        System.out.println("Preview integration probes passed: " + passed);
    }
}
