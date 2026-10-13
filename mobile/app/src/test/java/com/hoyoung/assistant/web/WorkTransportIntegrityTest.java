package com.hoyoung.assistant.web;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

/** Pure file/protocol checks; these tests do not log in to ChatGPT or use a model. */
public class WorkTransportIntegrityTest {
    @Test public void orderedCapturePreservesWhitespaceUnicodeAndDigest() throws Exception {
        File folder = Files.createTempDirectory("work-capture-test-").toFile();
        WorkWebController.ResponseCaptureJournal capture = new WorkWebController.ResponseCaptureJournal(folder, "request", "capture", 2);
        String first = "    첫 줄 😀\n";
        String second = "끝 줄  \n";
        capture.append(0, first); capture.append(1, second);
        File original = capture.finish(first.length() + second.length());
        byte[] bytes = Files.readAllBytes(original.toPath());
        assertEquals(first + second, new String(bytes, StandardCharsets.UTF_8));
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), capture.sha256());
        String manifest = new String(Files.readAllBytes(new File(original.getPath() + ".json").toPath()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"captureComplete\":true"));
        assertTrue(manifest.contains("\"receivedParts\":2"));
    }

    @Test public void missingOutOfOrderAndDuplicatedPartsCannotComplete() throws Exception {
        File folder = Files.createTempDirectory("work-capture-test-").toFile();
        WorkWebController.ResponseCaptureJournal capture = new WorkWebController.ResponseCaptureJournal(folder, "request", "capture", 2);
        assertThrows(IOException.class, () -> capture.append(1, "not first"));
        capture.append(0, "first");
        assertThrows(IOException.class, () -> capture.append(0, "duplicate"));
        assertThrows(IOException.class, () -> capture.finish(5));
        capture.closePartial();
        File[] manifests = folder.listFiles((dir, name) -> name.endsWith(".json"));
        assertEquals(1, manifests.length);
        assertTrue(new String(Files.readAllBytes(manifests[0].toPath()), StandardCharsets.UTF_8).contains("\"captureComplete\":false"));
    }

    @Test public void incorrectManifestAndSplitUnicodeCannotComplete() throws Exception {
        File folder = Files.createTempDirectory("work-capture-test-").toFile();
        WorkWebController.ResponseCaptureJournal capture = new WorkWebController.ResponseCaptureJournal(folder, "request", "capture", 1);
        assertThrows(IOException.class, () -> capture.append(0, "bad\uD83D"));
        capture.append(0, "valid 😀");
        assertThrows(IOException.class, () -> capture.finish(2));
        capture.closePartial();
    }

    @Test public void captureAndAttachmentBoundsFailBeforeUnboundedAllocation() throws Exception {
        File folder = Files.createTempDirectory("work-capture-test-").toFile();
        assertThrows(IOException.class, () -> new WorkWebController.ResponseCaptureJournal(folder, "request", "capture", 101));
        WorkWebController.ResponseCaptureJournal capture = new WorkWebController.ResponseCaptureJournal(folder, "request", "capture", 1);
        assertThrows(IOException.class, () -> capture.append(0, "a".repeat(100_002)));
        capture.closePartial();
        WorkWebController.LimitedAttachmentBytes bytes = new WorkWebController.LimitedAttachmentBytes(3);
        bytes.write(new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> bytes.write(4));
        assertArrayEquals(new byte[]{1, 2, 3}, bytes.toByteArray());
    }

    @Test public void loginHtmlCannotBeReportedAsAWorkingFileButHtmlArtifactsCan() {
        assertFalse(WorkingDownload.isDownloadResponse("text/html; charset=utf-8", null));
        assertFalse(WorkingDownload.isDownloadResponse("application/xhtml+xml", "inline"));
        assertTrue(WorkingDownload.isDownloadResponse("text/html", "attachment; filename=report.html"));
        assertTrue(WorkingDownload.isDownloadResponse("application/zip", null));
    }

    @Test public void prematureDownloadEofFailsBeforeACompletedFileCanBePublished() throws Exception {
        WorkingDownload.ExpectedLengthInputStream shortFile = new WorkingDownload.ExpectedLengthInputStream(new java.io.ByteArrayInputStream(new byte[]{1, 2}), 3);
        assertEquals(2, shortFile.read(new byte[8], 0, 8));
        assertThrows(IOException.class, shortFile::read);
        WorkingDownload.ExpectedLengthInputStream complete = new WorkingDownload.ExpectedLengthInputStream(new java.io.ByteArrayInputStream(new byte[]{1, 2}), 2);
        assertEquals(2, complete.read(new byte[8], 0, 8)); assertEquals(-1, complete.read());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append("0123456789abcdef".charAt((item & 255) >>> 4)).append("0123456789abcdef".charAt(item & 15));
        return result.toString();
    }
}
