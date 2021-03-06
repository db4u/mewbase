package io.mewbase.server.impl.log;

import io.mewbase.bson.BsonObject;
import io.mewbase.client.MewException;
import io.mewbase.common.SubDescriptor;
import io.mewbase.server.Log;
import io.mewbase.server.LogReadStream;
import io.mewbase.server.ServerOptions;
import io.mewbase.server.impl.BasicFile;
import io.mewbase.server.impl.FileAccess;
import io.mewbase.util.AsyncResCF;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TODO:
 * <p>
 * 1. Corruption detection, incl CRC checks
 * 2. Version header
 * <p>
 * Created by tim on 07/10/16.
 */
public class LogImpl implements Log {

    private final static Logger logger = LoggerFactory.getLogger(LogImpl.class);

    private static final int MAX_CREATE_BUFF_SIZE = 10 * 1024 * 1024;
    private static final String LOG_INFO_FILE_TAIL = "-log-info.dat";

    private final Vertx vertx;
    private final FileAccess faf;
    private final String channel;
    private final ServerOptions options;
    private final Set<LogReadStreamImpl> fileLogStreams = new ConcurrentHashSet<>();

    private BasicFile currWriteFile;
    private BasicFile nextWriteFile;
    private int fileNumber; // Number of log file containing current head
    private int filePos;    // Position of head in head file
    private long headPos;   // Overall position of head in log
    private AtomicLong lastWrittenPos = new AtomicLong();  // Position of beginning of last safely written record
    private CompletableFuture<Void> nextFileCF;
    private long writeSequence;
    private long expectedSeq;
    private final PriorityQueue<WriteHolder> pq = new PriorityQueue<>();
    private CompletableFuture<Void> startRes;
    private long flushTimerID = -1;
    private boolean closed;

    public LogImpl(Vertx vertx, FileAccess faf, ServerOptions options, String channel) {
        this.vertx = vertx;
        this.channel = channel;
        this.options = options;
        this.faf = faf;
        if (options.getMaxLogChunkSize() < 1) {
            throw new IllegalArgumentException("maxLogChunkSize must be > 1");
        }
        if (options.getPreallocateSize() < 0) {
            throw new IllegalArgumentException("maxLogChunkSize must be >= 0");
        }
        if (options.getMaxRecordSize() < 1) {
            throw new IllegalArgumentException("maxRecordSize must be > 1");
        }
        if (options.getMaxRecordSize() > options.getMaxLogChunkSize()) {
            throw new IllegalArgumentException("maxRecordSize must be <= maxLogChunkSize");
        }
        if (options.getPreallocateSize() > options.getMaxLogChunkSize()) {
            throw new IllegalArgumentException("preallocateSize must be <= maxLogChunkSize");
        }
    }

    public synchronized CompletableFuture<Void> start() {
        closed = false;
        logger.trace("Starting file log " + this);
        if (startRes != null) {
            return startRes;
        }
        loadInfo();
        checkAndLoadFiles();
        File currFile = getFile(fileNumber);
        CompletableFuture<Void> cfCreate = null;
        if (!currFile.exists()) {
            if (fileNumber == 0 && filePos == 0) {
                // This is OK, new log
                logger.trace("Creating new log info file for channel {}", channel);
                saveInfo(true);
                // Create a new first file
                cfCreate = createAndFillFile(getFileName(0));
            } else {
                throw new MewException("Info file for channel {} doesn't match data file(s)");
            }
        }
        final File cFile = currFile;
        // Now open the BasicFile
        CompletableFuture<BasicFile> cf;
        if (cfCreate != null) {
            cf = cfCreate.thenCompose(v -> faf.openBasicFile(cFile));
        } else {
            cf = faf.openBasicFile(currFile);
        }
        startRes = cf.thenAccept(bf -> {
            currWriteFile = bf;
            logger.trace("Opened file log " + this);
        }).thenCompose(v -> saveInfoAsync(false)).thenRun(this::scheduleFlush);

        return startRes;
    }

    private synchronized void flush() {
        if (!closed) {
            if (currWriteFile == null) {
                // Sanity check
                throw new IllegalStateException("No currWriteFile");
            }
            logger.trace("Flushing log file");
            currWriteFile.flush().thenCompose(v -> saveInfoAsync(false)).thenRun(this::scheduleFlush);
        }
    }

    private synchronized void scheduleFlush() {
        flushTimerID = vertx.setTimer(options.getLogFlushInterval(), tid -> flush());
    }

    @Override
    public synchronized LogReadStream subscribe(SubDescriptor subDescriptor) {
        if (subDescriptor.getStartPos() < -1) {
            throw new IllegalArgumentException("startPos must be >= -1");
        }
        if (subDescriptor.getStartPos() > getLastWrittenPos()) {
            throw new IllegalArgumentException("startPos cannot be past head");
        }
        return new LogReadStreamImpl(this, subDescriptor,
                options.getReadBufferSize(), options.getMaxLogChunkSize());
    }

    @Override
    public synchronized CompletableFuture<Long> append(BsonObject obj) {

        Buffer record = obj.encode();
        int len = record.length();
        if (record.length() > options.getMaxRecordSize()) {
            throw new MewException("Record too long " + len + " max " + options.getMaxRecordSize());
        }

        CompletableFuture<Long> cf;

        int remainingSpace = options.getMaxLogChunkSize() - filePos;
        if (record.length() > remainingSpace) {
            if (remainingSpace > 0) {
                // Write into the remaining space so all log chunk files are same size
                Buffer buffer = Buffer.buffer(new byte[remainingSpace]);
                append0(buffer.length(), buffer);
            }
            // Move to next file
            if (nextWriteFile != null) {
                logger.trace("Moving to next log file");
                currWriteFile = nextWriteFile;
                filePos = 0;
                fileNumber++;
                nextWriteFile = null;
            } else {
                logger.warn("Eager create of next file too slow, nextFileCF {}", nextFileCF);
                checkCreateNextFile();
                // Next file creation is in progress, just wait for it
                cf = new CompletableFuture<>();
                nextFileCF.thenAccept(v -> {
                    // When complete just call append again
                    CompletableFuture<Long> again = append(obj);
                    again.handle((pos, t) -> {
                        if (t != null) {
                            cf.completeExceptionally(t);
                        } else {
                            cf.complete(pos);
                        }
                        return null;
                    });
                });
                return cf;
            }
        }

        long seq = writeSequence++;
        cf = append0(len, record);
        cf.thenApply(pos -> {
            sendToSubsOrdered(seq, pos, obj);
            return pos;
        });
        checkCreateNextFile();
        return cf;
    }

    protected synchronized void sendToSubsOrdered(long seq, long pos, BsonObject obj) {
        // Writes can complete in a different order to which they were submitted, we we need to reorder to ensure
        // records are delivered in the correct order
        if (seq == expectedSeq) {
            sendToSubs(pos, obj);
        } else {
            // Out of order
            pq.add(new WriteHolder(seq, pos, obj));
        }
        while (true) {
            WriteHolder head = pq.peek();
            if (head != null && head.seq == expectedSeq) {
                pq.poll();
                sendToSubs(head.pos, head.obj);
            } else {
                break;
            }
        }
    }

    @Override
    public synchronized CompletableFuture<Void> close() {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        closed = true;
        if (flushTimerID != -1) {
            vertx.cancelTimer(flushTimerID);
            flushTimerID = -1;
        }
        CompletableFuture<Void> ret;
        if (currWriteFile != null) {
            ret = currWriteFile.close();
        } else {
            ret = CompletableFuture.completedFuture(null);
        }
        if (nextWriteFile != null) {
            ret = ret.thenCompose(v -> nextWriteFile.close());
        }
        if (nextFileCF != null) {
            CompletableFuture<Void> ncf = nextFileCF;
            ret = ret.thenCompose(v -> ncf);
        }
        ret = ret.thenRun(() -> saveInfo(true));
        return ret;
    }

    @Override
    public synchronized int getFileNumber() {
        return fileNumber;
    }

    @Override
    public synchronized long getHeadPos() {
        return headPos;
    }

    @Override
    public synchronized int getFilePos() {
        return filePos;
    }

    void removeSubHolder(LogReadStreamImpl stream) {
        fileLogStreams.remove(stream);
    }

    void readdSubHolder(LogReadStreamImpl stream) {
        fileLogStreams.add(stream);
    }

    public long getLastWrittenPos() {
        return lastWrittenPos.get();
    }

    long getLastWrittenEndPos() {
        return lastWrittenPos.get();
    }

    FileCoord getCoord(long pos) {
        return new FileCoord(pos, options.getMaxLogChunkSize());
    }

    CompletableFuture<BasicFile> openFile(int fileNumber) {
        File file = new File(options.getLogsDir(), getFileName(fileNumber));
        return faf.openBasicFile(file);
    }

    void scheduleOp(Runnable runner) {
        faf.scheduleOp(runner);
    }

    private synchronized void sendToSubs(long pos, BsonObject bsonObject) {
        expectedSeq++;
        lastWrittenPos.set(pos);
        for (LogReadStreamImpl stream : fileLogStreams) {
            if (stream.matches(bsonObject)) {
                try {
                    stream.handle(pos, bsonObject);
                } catch (Throwable t) {
                    logger.error("Failed to send to subs", t);
                }
            }
        }
    }

    private CompletableFuture<Long> append0(int len, Buffer record) {
        int writePos = filePos;
        long overallWritePos = headPos;
        filePos += len;
        headPos += len;
        return currWriteFile.append(record, writePos).thenApply(v -> overallWritePos);
    }

    private synchronized void saveInfo(boolean shutdown) {
        logger.trace("Saving file info with shutdown: " + shutdown);
        BsonObject info = new BsonObject();
        info.put("fileNumber", fileNumber);
        info.put("headPos", headPos);
        info.put("fileHeadPos", filePos);
        info.put("lastWrittenPos", lastWrittenPos.get());
        info.put("shutdown", shutdown);
        saveFileInfo(info);
    }

    private CompletableFuture<Void> saveInfoAsync(boolean shutdown) {
        AsyncResCF<Void> ar = new AsyncResCF<>();
        vertx.executeBlocking(fut -> {
            saveInfo(shutdown);
            fut.complete();
        }, ar);
        return ar;
    }

    private void loadInfo() {
        BsonObject info = loadFileInfo();
        logger.trace("loaded fileinfo: " + info);
        if (info != null) {
            try {
                Integer fNumber = info.getInteger("fileNumber");
                if (fNumber == null) {
                    throw new MewException("Invalid log info file, no fileNumber");
                }
                if (fNumber < 0) {
                    throw new MewException("Invalid log info file, negative fileNumber");
                }
                this.fileNumber = fNumber;
                Integer hPos = info.getInteger("headPos");
                if (hPos == null) {
                    throw new MewException("Invalid log info file, no headPos");
                }
                if (hPos < 0) {
                    throw new MewException("Invalid log info file, negative headPos");
                }
                this.headPos = hPos;
                Integer lwPos = info.getInteger("lastWrittenPos");
                if (lwPos == null) {
                    throw new MewException("Invalid log info file, no lastWrittenPos");
                }
                if (lwPos < 0) {
                    throw new MewException("Invalid log info file, negative lastWrittenPos");
                }
                this.lastWrittenPos.set(lwPos);
                Integer fhPos = info.getInteger("fileHeadPos");
                if (fhPos == null) {
                    throw new MewException("Invalid log info file, no fileHeadPos");
                }
                if (fhPos < 0) {
                    throw new MewException("Invalid log info file, negative fileHeadPos");
                }
                this.filePos = fhPos;
                Boolean shutdown = info.getBoolean("shutdown");
                if (shutdown == null) {
                    throw new MewException("Invalid log info file, no shutdown");
                }
                if (!shutdown) {
                    // TODO
                    // Bail out for now, need to deal with this gracefully
                    throw new MewException("Log was not shutdown cleanly, could be corrupt!");
                }
            } catch (ClassCastException e) {
                throw new MewException("Invalid info file for channel " + channel, e);
            }
        }
    }

    private BsonObject loadFileInfo() {
        File f = new File(options.getLogsDir(), getLogInfoFileName());
        if (!f.exists()) {
            return null;
        } else {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                Buffer buff = Buffer.buffer(bytes);
                return new BsonObject(buff);
            } catch (IOException e) {
                throw new MewException(e);
            }
        }
    }

    private void saveFileInfo(BsonObject info) {
        Buffer buff = info.encode();
        File f = new File(options.getLogsDir(), getLogInfoFileName());
        try {
            if (!f.exists()) {
                if (!f.createNewFile()) {
                    throw new MewException("Failed to create file " + f);
                }
            }
            Files.write(f.toPath(), buff.getBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new MewException(e);
        }
    }

    private File getFile(int fileNumber) {
        return new File(options.getLogsDir(), getFileName(fileNumber));
    }

    private synchronized void checkCreateNextFile() {
        // We create a next file when the current file is half written
        if (nextFileCF == null && nextWriteFile == null && filePos > options.getMaxLogChunkSize() / 2) {
            nextFileCF = new CompletableFuture<>();
            CompletableFuture<Void> cfNext = createAndFillFile(getFileName(fileNumber + 1));
            CompletableFuture<BasicFile> cfBf = cfNext.thenCompose(v -> {
                File next = getFile(fileNumber + 1);
                return faf.openBasicFile(next);
            });
            cfBf.handle((bf, t) -> {
                synchronized (LogImpl.this) {
                    if (t == null) {
                        nextWriteFile = bf;
                        if (nextFileCF == null) {
                            logger.error("nextFileCF is null");
                        }
                        nextFileCF.complete(null);
                        nextFileCF = null;
                    } else {
                        logger.error("Failed to create next file", t);
                    }
                    return null;
                }
            }).exceptionally(t -> {
                logger.error(t.getMessage(), t);
                return null;
            });

        }
    }

    private CompletableFuture<Void> createAndFillFile(String fileName) {
        AsyncResCF<Void> cf = new AsyncResCF<>();
        File next = new File(options.getLogsDir(), fileName);
        vertx.executeBlocking(fut -> {
            createAndFillFileBlocking(next, options.getPreallocateSize());
            fut.complete(null);
        }, false, cf);
        return cf;
    }

    private void createAndFillFileBlocking(File file, int size) {
        logger.trace("Creating log file {} with size {}", file, size);
        ByteBuffer buff = ByteBuffer.allocate(MAX_CREATE_BUFF_SIZE);
        try (RandomAccessFile rf = new RandomAccessFile(file, "rw")) {
            FileChannel ch = rf.getChannel();
            int pos = 0;
            // We fill the file in chunks in case it is v. big - we don't want to allocate a huge byte buffer
            while (pos < size) {
                int writeSize = Math.min(MAX_CREATE_BUFF_SIZE, size - pos);
                buff.limit(writeSize);
                buff.position(0);
                ch.position(pos);
                ch.write(buff);
                pos += writeSize;
            }
            ch.force(true);
            ch.position(0);
            ch.close();
        } catch (Exception e) {
            throw new MewException("Failed to create log file", e);
        }
        logger.trace("Created log file {}", file);
    }

    /*
    List and check all the files in the log dir for the channel
     */
    private void checkAndLoadFiles() {
        Map<Integer, File> fileMap = new HashMap<>();
        File logDir = new File(options.getLogsDir());
        File[] files = logDir.listFiles(file -> {
            String name = file.getName();
            int lpos = name.lastIndexOf("-");
            if (name.endsWith(LOG_INFO_FILE_TAIL)) {
                return false;
            }
            if (lpos == -1) {
                logger.warn("Unexpected file in log dir: " + file);
                return false;
            } else {
                String chName = name.substring(0, lpos);
                int num = Integer.valueOf(name.substring(lpos + 1, name.length() - 4));
                boolean matches = chName.equals(channel);
                if (matches) {
                    fileMap.put(num, file);
                }
                return matches;
            }
        });
        if (files == null) {
            throw new MewException("Failed to list files in dir " + logDir.toString());
        }

        Arrays.sort(files, (f1, f2) -> f1.compareTo(f2));
        // All files before the head file must be right size
        // TODO test this
        for (int i = 0; i < files.length; i++) {
            if (i < fileNumber && options.getMaxLogChunkSize() != files[i].length()) {
                throw new MewException("File unexpected size: " + files[i]);
            }
        }

        logger.trace("There are {} files in {} for channel {}", files.length, logDir, channel);

        for (int i = 0; i < fileMap.size(); i++) {
            // Check file names are contiguous
            String fname = getFileName(i);
            if (!fileMap.containsKey(i)) {
                throw new MewException("Log files not in expected sequence, can't find " + fname);
            }
        }
    }

    private String getFileName(int i) {
        return channel + "-" + i + ".log";
    }

    private String getLogInfoFileName() {
        return channel + LOG_INFO_FILE_TAIL;
    }

    static final class FileCoord {
        final long pos;
        final int fileMaxSize;
        final int fileNumber;
        final int filePos;

        public FileCoord(long pos, int fileMaxSize) {
            this.pos = pos;
            this.fileMaxSize = fileMaxSize;
            this.fileNumber = (int)(pos / fileMaxSize);
            this.filePos = (int)(pos % fileMaxSize);
        }
    }

    private static final class WriteHolder implements Comparable<WriteHolder> {
        final long seq;

        final long pos;
        final BsonObject obj;

        public WriteHolder(long seq, long pos, BsonObject obj) {
            this.seq = seq;
            this.pos = pos;
            this.obj = obj;
        }

        @Override
        public int compareTo(WriteHolder other) {
            return Long.compare(this.seq, other.seq);
        }
    }


}
