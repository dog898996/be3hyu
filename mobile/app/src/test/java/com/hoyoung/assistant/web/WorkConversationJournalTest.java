package com.hoyoung.assistant.web;

import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class WorkConversationJournalTest {
    private Context context;
    @Before public void before() throws Exception { context=RuntimeEnvironment.getApplication(); clean(); }
    @After public void clean() throws Exception { if(context!=null) for(String suffix:new String[]{"",".bak",".new"})Files.deleteIfExists(new File(context.getNoBackupFilesDir(),"work-conversation.json"+suffix).toPath()); }
    @Test public void onlyMatchingCreationCanOwnConversationAndDeletionSurvivesRestart() throws Exception {
        WorkConversationJournal journal=new WorkConversationJournal(context);
        assertFalse(journal.accept("foreign-request","personal-chat-123"));
        journal.begin("request-one");assertFalse(journal.accept("different-request","personal-chat-123"));
        assertTrue(journal.accept("request-one","owned-chat-123"));
        journal=new WorkConversationJournal(context);assertEquals("owned-chat-123",journal.id());
        assertFalse(journal.accept("request-two","personal-chat-123"));
        journal.requestCleanup();journal=new WorkConversationJournal(context);assertTrue(journal.cleaning());
        journal.beginDeletion();journal=new WorkConversationJournal(context);assertTrue(journal.deletionAttempted());
        final WorkConversationJournal restored=journal;
        assertThrows(IOException.class,restored::beginDeletion);
        assertThrows(IOException.class,()->restored.begin("request-two"));
        assertThrows(IOException.class,()->restored.deleted("personal-chat-123",false));
        assertEquals("owned-chat-123",restored.id());
        restored.deleted("owned-chat-123",false);assertEquals("",new WorkConversationJournal(context).id());
    }
    @Test public void unknownCreationAndCorruptReceiptsNeverAuthorizeDeletion() throws Exception {
        WorkConversationJournal journal=new WorkConversationJournal(context);journal.begin("unsent");
        assertThrows(IOException.class,journal::requestCleanup);journal.notSent("different");assertEquals("unsent",journal.pendingRequest());
        journal.notSent("unsent");journal.begin("new-request");assertTrue(journal.accept("new-request","owned-chat-456"));
        Files.write(new File(context.getNoBackupFilesDir(),"work-conversation.json").toPath(),"{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WorkConversationJournal corrupted=new WorkConversationJournal(context);assertTrue(corrupted.blocked());
        assertThrows(IOException.class,corrupted::requestCleanup);
    }
    @Test public void ownerCanResolveUnknownCreationOnlyAfterReviewingThatExactRequest() throws Exception {
        WorkConversationJournal journal=new WorkConversationJournal(context);journal.begin("unknown-request");
        assertThrows(IOException.class,()->journal.unknownCreationDeletedByOwner("other"));
        journal.unknownCreationDeletedByOwner("unknown-request");assertEquals("",new WorkConversationJournal(context).pendingRequest());
        journal.begin("next-request");assertTrue(journal.accept("next-request","owned-chat-567"));
        assertThrows(IOException.class,()->journal.unknownCreationDeletedByOwner("next-request"));
    }
}
