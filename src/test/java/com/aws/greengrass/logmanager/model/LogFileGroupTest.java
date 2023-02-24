package com.aws.greengrass.logmanager.model;

import com.aws.greengrass.logmanager.exceptions.InvalidLogGroupException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.aws.greengrass.logmanager.util.TestUtils.createLogFileWithSize;
import static com.aws.greengrass.logmanager.util.TestUtils.givenAStringOfSize;
import static com.aws.greengrass.logmanager.util.TestUtils.readFileContent;
import static com.aws.greengrass.logmanager.util.TestUtils.writeFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class LogFileGroupTest {
    @TempDir
    static Path directoryPath;
    @TempDir
    private Path workDir;

    @BeforeEach
    void setup() {
        Arrays.stream(directoryPath.toFile().listFiles()).forEach(File::delete);
    }


    public LogFile arrangeLogFile(String name, int byteSize) throws InterruptedException, IOException {
        LogFile file = createLogFileWithSize(directoryPath.resolve(name).toUri(), byteSize);
        // Wait to avoid file's lastModified to be the same if called to fast
        TimeUnit.MILLISECONDS.sleep(100);
        return file;
    }

    @Test
    void GIVEN_log_files_THEN_find_the_active_file() throws IOException, InterruptedException,
            InvalidLogGroupException {
        LogFile file = new LogFile(directoryPath.resolve("greengrass_test.log_1").toUri());
        byte[] bytesArray = givenAStringOfSize(1024).getBytes(StandardCharsets.UTF_8);
        writeFile(file, bytesArray);

        //Intentionally sleep lazily here to differ the creation time of two files.
        TimeUnit.SECONDS.sleep(1);

        LogFile file2 = new LogFile(directoryPath.resolve("greengrass_test.log_2").toUri());
        byte[] bytesArray2 = givenAStringOfSize(1024).getBytes(StandardCharsets.UTF_8);
        writeFile(file2, bytesArray2);

        Pattern pattern = Pattern.compile("^greengrass_test.log\\w*$");
        ComponentLogConfiguration compLogInfo = ComponentLogConfiguration.builder()
                .directoryPath(directoryPath)
                .fileNameRegex(pattern).name("greengrass_test").build();
        Instant instant = Instant.EPOCH;
        LogFileGroup logFileGroup = LogFileGroup.create(compLogInfo, instant, workDir);

        assertEquals(2, logFileGroup.getLogFiles().size());
        assertFalse(logFileGroup.isActiveFile(file));
        assertTrue(logFileGroup.isActiveFile(file2));
    }


    @Test
    void GIVEN_fileWithNotEnoughBytes_WHEN_logGroupCreated_THEN_itIsExcluded() throws IOException,
            InvalidLogGroupException {
        // Given
        File rotatedFile = createLogFileWithSize(directoryPath.resolve("test.log.1").toUri(), 2048);
        createLogFileWithSize(directoryPath.resolve("test.log").toUri(), 500); // active file

        // When
        Pattern pattern = Pattern.compile("test.log\\w*");
        ComponentLogConfiguration compLogInfo = ComponentLogConfiguration.builder()
                .directoryPath(directoryPath)
                .fileNameRegex(pattern).name("greengrass_test").build();
        Instant instant = Instant.EPOCH;
        LogFileGroup logFileGroup = LogFileGroup.create(compLogInfo, instant, workDir);

        // Then
        assertEquals(1, logFileGroup.getLogFiles().size());
        LogFile logFile = logFileGroup.getLogFiles().get(0);
        assertEquals(readFileContent(rotatedFile), readFileContent(logFile));
    }

    @Test
    void GIVEN_last_processedTime_THEN_it_returns_the_unprocessed_files_correctly() throws IOException,
            InterruptedException, InvalidLogGroupException {
        // Given
        LogFile cLogFile = arrangeLogFile("test.log.3", 2048);
        LogFile bLogFile = arrangeLogFile("test.log.2", 2048);
        LogFile aLogFile = arrangeLogFile("test.log.1", 2048);
        LogFile activeFile = arrangeLogFile("test.log", 1024);
        Instant lastProcessedFileInstant = Instant.ofEpochMilli(bLogFile.lastModified());

        // When
        ComponentLogConfiguration config = ComponentLogConfiguration.builder()
                .directoryPath(directoryPath)
                .fileNameRegex(Pattern.compile("test.log\\w*")).name("greengrass_test")
                .build();
        LogFileGroup group = LogFileGroup.create(config, lastProcessedFileInstant, workDir);

        // Then
        List<LogFile> processed = group.getProcessedLogFiles();
        assertEquals(2, processed.size());
        assertTrue(processed.stream().anyMatch(f -> f.getName().equals(cLogFile.getName())));
        assertTrue(processed.stream().anyMatch(f -> f.getName().equals(bLogFile.getName())));

        List<LogFile> unprocessed = group.getLogFiles();
        assertEquals(2, unprocessed.size());
        assertTrue(unprocessed.stream().anyMatch(f -> f.getName().equals(aLogFile.getName())));
        assertTrue(unprocessed.stream().anyMatch(f -> f.getName().equals(activeFile.getName())));
    }
}
