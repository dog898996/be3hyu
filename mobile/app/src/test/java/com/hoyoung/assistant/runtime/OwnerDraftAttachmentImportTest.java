package com.hoyoung.assistant.runtime;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** JVM integration evidence only; real Android SAF is covered by MediaExplorerDeviceTest. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerDraftAttachmentImportTest {
    private Context context;
    private HoyoungStore store;
    private OwnerDraftStore drafts;
    private InternalFiles files;
    private String folder;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store = new HoyoungStore(context); drafts = new OwnerDraftStore(store); files = new InternalFiles(context);
        folder = "import-regression/" + UUID.randomUUID() + "/";
    }
    @After public void close() { store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); }

    @Test public void repeatedImportStillPassesRealAttachmentFreezeAndPreservesDraftIdentity() throws Exception {
        String id = original("photo.txt", "the same preserved bytes");
        drafts.edit("Keep this exact text\n한글 🙂", new JSONArray());
        OwnerDraftStore.Snapshot first = drafts.appendAttachments(new JSONArray().put(id), UiAutomationProvenance.OWNER);
        OwnerDraftStore.Snapshot repeated = drafts.appendAttachments(new JSONArray().put(id), UiAutomationProvenance.OWNER);
        JSONArray sendable = files.freezeAttachments(repeated.attachments());
        assertEquals(1, sendable.length()); assertEquals(id, sendable.getJSONObject(0).getString("id"));
        same(first, repeated);
    }

    @Test public void duplicateBatchAndAlreadySavedDuplicateDraftRecoverWithoutLosingTextOrAuthorship() throws Exception {
        String id = original("original.txt", "immutable original");
        OwnerDraftStore.Snapshot repeatedBatch = drafts.appendAttachments(new JSONArray().put(id).put(id), UiAutomationProvenance.AGENT);
        assertEquals(1, files.freezeAttachments(repeatedBatch.attachments()).length());
        OwnerDraftStore.Snapshot old = drafts.edit("Older unsendable draft stays exact", new JSONArray().put(id).put(id), UiAutomationProvenance.AGENT);
        OwnerDraftStore.Snapshot repaired = drafts.read();
        assertEquals(old.text, repaired.text); assertEquals(old.inputActor, repaired.inputActor);
        assertEquals(1, files.freezeAttachments(repaired.attachments()).length());
        assertNotEquals("A repaired tuple needs a new submission identity", old.clientInputId, repaired.clientInputId);
        same(repaired, drafts.read());
    }

    @Test public void limitCountsDistinctOriginalsAndNinthNewOriginalLeavesDraftUnchanged() throws Exception {
        JSONArray seven = new JSONArray();
        for (int i = 0; i < 7; i++) seven.put(original("file-" + i + ".txt", "distinct original " + i));
        drafts.edit("All seven must survive", seven);
        String eighth = original("file-7.txt", "distinct original 7");
        OwnerDraftStore.Snapshot eight = drafts.appendAttachments(new JSONArray().put(seven.getString(0)).put(eighth), UiAutomationProvenance.OWNER);
        assertEquals(8, files.freezeAttachments(eight.attachments()).length());
        for (int i = 0; i < 7; i++) assertEquals(seven.getString(i), eight.attachments().getString(i));
        assertEquals(eighth, eight.attachments().getString(7));
        same(eight, drafts.appendAttachments(new JSONArray().put(eighth).put(seven.getString(0)), UiAutomationProvenance.AGENT));
        String ninth = original("file-8.txt", "distinct original 8");
        assertThrows(IllegalArgumentException.class,
                () -> drafts.appendAttachments(new JSONArray().put(ninth), UiAutomationProvenance.OWNER));
        same(eight, drafts.read());
    }

    @Test public void differentBytesWithTheSameFilenameRemainSeparateVerifiedOriginals() throws Exception {
        String first = original("same-name.txt", "first version");
        String second = original("same-name.txt", "second version");
        assertNotEquals(first, second);
        OwnerDraftStore.Snapshot both = drafts.appendAttachments(new JSONArray().put(first).put(second), UiAutomationProvenance.OWNER);
        assertEquals(2, files.freezeAttachments(both.attachments()).length());
        assertEquals("first version", bytes(first)); assertEquals("second version", bytes(second));
    }

    @Test public void duplicateMetadataClaimsRemainVisibleToIntegrityValidation() throws Exception {
        String id = original("claimed.txt", "the verified bytes");
        JSONObject contradictory = new JSONObject().put("id", id).put("size", 99999);
        drafts.edit("Do not hide contradictory metadata", new JSONArray().put(id).put(contradictory));
        OwnerDraftStore.Snapshot read = drafts.read();
        assertEquals(2, read.attachments().length());
        assertEquals(99999, read.attachments().getJSONObject(1).getLong("size"));
        assertThrows(java.io.IOException.class, () -> files.freezeAttachments(read.attachments()));
    }

    @Test public void legacyDraftMigrationIsSendableOnItsFirstRead() throws Exception {
        String id = original("legacy.txt", "old original");
        store.putSetting("ui.draft", "First launch after an older version");
        store.putSetting("ui.attachments", new JSONArray().put(id).put(id).toString());
        OwnerDraftStore.Snapshot migrated = drafts.read();
        assertEquals("First launch after an older version", migrated.text);
        assertEquals(1, files.freezeAttachments(migrated.attachments()).length());
        same(migrated, drafts.read());
    }

    @Test public void legacyFormattingAloneDoesNotCreateANewDraftRevision() throws Exception {
        String id = original("formatting.txt", "single original");
        String formatted = "[\n  \"" + id + "\"\n]";
        store.putSetting("ui.draft", "Keep the input cohort"); store.putSetting("ui.attachments", formatted);
        OwnerDraftStore.Snapshot first = drafts.read();
        assertEquals(formatted, first.attachmentsJson);
        same(first, drafts.read());
        same(first, drafts.appendAttachments(new JSONArray().put(id), UiAutomationProvenance.AGENT));
    }

    private String original(String name, String text) throws Exception {
        JSONObject working = files.writeFile(folder + name, text);
        return files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0).getString("id");
    }
    private String bytes(String id) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); files.exportFile(id, output);
        return output.toString(StandardCharsets.UTF_8);
    }
    private static void same(OwnerDraftStore.Snapshot expected, OwnerDraftStore.Snapshot actual) {
        assertEquals(expected.text, actual.text); assertEquals(expected.attachmentsJson, actual.attachmentsJson);
        assertEquals(expected.clientInputId, actual.clientInputId); assertEquals(expected.revision, actual.revision);
        assertEquals(expected.inputActor, actual.inputActor);
    }
}
