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
            drain();
            check(cell.applied.getId() == 101, "Old replacement beat newer eligible message");
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
