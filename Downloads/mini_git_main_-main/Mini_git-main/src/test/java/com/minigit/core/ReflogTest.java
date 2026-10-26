package com.minigit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReflogTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void readsEntriesNewestFirstAndHonoursTheLimit() throws IOException {
        Reflog.append(temporaryDirectory, "commit", "main", "", "a", "one");
        Reflog.append(temporaryDirectory, "commit", "main", "a", "b", "two");
        Reflog.append(temporaryDirectory, "commit", "main", "b", "c", "three");

        List<Reflog.Entry> all = Reflog.read(temporaryDirectory, 0);

        assertEquals(3, all.size());
        assertEquals("three", all.get(0).detail());
        assertEquals("one", all.get(2).detail());
        assertEquals(2, Reflog.read(temporaryDirectory, 2).size());
    }

    @Test
    void missingLogIsEmpty() throws IOException {
        assertTrue(Reflog.read(temporaryDirectory, 10).isEmpty());
    }

    @Test
    void detailsWithSpecialCharactersSurvive() throws IOException {
        String detail = "tabs\tand\nnewlines \"quoted\" ünïcode";

        Reflog.append(temporaryDirectory, "commit", "main", "", "a", detail);

        assertEquals(
                detail,
                Reflog.read(temporaryDirectory, 1).get(0).detail()
        );
    }

    @Test
    void malformedLinesAreIgnored() throws IOException {
        Reflog.append(temporaryDirectory, "commit", "main", "", "a", "ok");

        Files.writeString(
                temporaryDirectory.resolve("logs").resolve(Reflog.FILE_NAME),
                "garbage line\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND
        );

        assertEquals(1, Reflog.read(temporaryDirectory, 10).size());
    }
}
