package org.apache.hadoop.fs;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.qcloud.cos.model.PartETag;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.buffer.CosNByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class CosFsDataOutputStream extends OutputStream {
    static final Logger LOG =
            LoggerFactory.getLogger(CosFsDataOutputStream.class);

    private final Configuration conf;
    private final NativeFileSystemStore store;
    private MessageDigest digest;
    private long blockSize;
    private String key;
    private int currentBlockId = 0;
    private final Set<CosNByteBuffer> blockCacheByteBuffers =
            new HashSet<CosNByteBuffer>();
    private CosNByteBuffer currentBlockBuffer;
    private OutputStream currentBlockOutputStream;
    private String uploadId = null;
    private final ListeningExecutorService executorService;
    private final List<ListenableFuture<PartETag>> partEtagList =
            new LinkedList<ListenableFuture<PartETag>>();
    private int blockWritten = 0;
    private boolean closed = false;

    public CosFsDataOutputStream(
            Configuration conf,
            NativeFileSystemStore store,
            String key, long blockSize,
            ExecutorService executorService) throws IOException {
        this.conf = conf;
        this.store = store;
        this.key = key;
        this.blockSize = blockSize;
        if (this.blockSize < Constants.MIN_PART_SIZE) {
            LOG.warn("The minimum size of a single block is limited to " +
                    "greater than or equal to {}.", Constants.MIN_PART_SIZE);
            this.blockSize = Constants.MIN_PART_SIZE;
        }
        if (this.blockSize > Constants.MAX_PART_SIZE) {
            LOG.warn("The maximum size of a single block is limited to " +
                    "smaller than or equal to {}.", Constants.MAX_PART_SIZE);
            this.blockSize = Constants.MAX_PART_SIZE;
        }

        this.executorService =
                MoreExecutors.listeningDecorator(executorService);

        try {
            this.currentBlockBuffer =
                    BufferPool.getInstance().getBuffer((int) this.blockSize);
        } catch (InterruptedException e) {
            String exceptionMsg = String.format("Getting a buffer size:[%d] " +
                            "from the buffer pool occurs an exception.",
                    this.blockSize);
            throw new IOException(exceptionMsg);
        }
        try {
            this.digest = MessageDigest.getInstance("MD5");
            this.currentBlockOutputStream = new DigestOutputStream(
                    new BufferOutputStream(this.currentBlockBuffer),
                    this.digest);
        } catch (NoSuchAlgorithmException e) {
            this.digest = null;
            this.currentBlockOutputStream =
                    new BufferOutputStream(this.currentBlockBuffer);
        }
    }

    @Override
    public void flush() throws IOException {
        this.currentBlockOutputStream.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.currentBlockOutputStream.flush();
        this.currentBlockOutputStream.close();
        if (!this.blockCacheByteBuffers.contains(this.currentBlockBuffer)) {
            this.blockCacheByteBuffers.add(this.currentBlockBuffer);
        }
        // 加到块列表中去
        if (this.blockCacheByteBuffers.size() == 1) {
            // 单个文件就可以上传完成
            byte[] md5Hash = this.digest == null ? null : this.digest.digest();
            store.storeFile(this.key,
                    new BufferInputStream(this.currentBlockBuffer),
                    md5Hash,
                    this.currentBlockBuffer.getByteBuffer().remaining());
        } else {
            PartETag partETag = null;
            if (this.blockWritten > 0) {
                LOG.info("Upload the last part. blockId: [{}], written: [{}]",
                        this.currentBlockBuffer, this.blockWritten);
                partETag = store.uploadPart(
                        new BufferInputStream(this.currentBlockBuffer), key,
                        uploadId, currentBlockId + 1,
                        currentBlockBuffer.getByteBuffer().remaining());
            }
            final List<PartETag> futurePartEtagList =
                    this.waitForFinishPartUploads();
            if (null == futurePartEtagList) {
                throw new IOException("failed to multipart upload to cos, " +
                        "abort it.");
            }
            List<PartETag> tempPartETagList =
                    new LinkedList<PartETag>(futurePartEtagList);
            if (null != partETag) {
                tempPartETagList.add(partETag);
            }
            store.completeMultipartUpload(this.key, this.uploadId,
                    tempPartETagList);
        }
        try {
            BufferPool.getInstance().returnBuffer(this.currentBlockBuffer);
        } catch (InterruptedException e) {
            LOG.error("Returning the buffer to BufferPool occurs an exception" +
                    ".", e);
        }
        LOG.info("OutputStream for key [{}] upload complete", key);
        this.blockWritten = 0;
        this.closed = true;
    }

    private List<PartETag> waitForFinishPartUploads() throws IOException {
        try {
            LOG.info("Waiting for finish part uploads...");
            return Futures.allAsList(this.partEtagList).get();
        } catch (InterruptedException e) {
            LOG.error("Interrupt the part upload...", e);
            return null;
        } catch (ExecutionException e) {
            LOG.error("Cancelling futures...");
            for (ListenableFuture<PartETag> future : this.partEtagList) {
                future.cancel(true);
            }
            (store).abortMultipartUpload(this.key, this.uploadId);
            LOG.error("Multipart upload with id: {} to {}.", this.uploadId,
                    this.key);
            String exceptionMsg = String.format("multipart upload with id: %s" +
                    " to %s.", this.uploadId, this.key);
            throw new IOException(exceptionMsg);
        }
    }

    private void uploadPart() throws IOException {
        this.currentBlockOutputStream.flush();
        this.currentBlockOutputStream.close();
        this.blockCacheByteBuffers.add(this.currentBlockBuffer);

        if (this.currentBlockId == 0) {
            uploadId = (store).getUploadId(key);
        }

        ListenableFuture<PartETag> partETagListenableFuture =
                this.executorService.submit(new Callable<PartETag>() {
                    private final CosNByteBuffer buffer = currentBlockBuffer;
                    private final String localKey = key;
                    private final String localUploadId = uploadId;
                    private final int blockId = currentBlockId;

                    @Override
                    public PartETag call() throws Exception {
                        PartETag partETag = (store).uploadPart(
                                new BufferInputStream(this.buffer),
                                this.localKey,
                                this.localUploadId,
                                this.blockId + 1,
                                this.buffer.getByteBuffer().remaining());
                        BufferPool.getInstance().returnBuffer(this.buffer);
                        return partETag;
                    }
                });
        this.partEtagList.add(partETagListenableFuture);
        try {
            this.currentBlockBuffer =
                    BufferPool.getInstance().getBuffer((int) this.blockSize);
        } catch (InterruptedException e) {
            String exceptionMsg = String.format("getting a buffer size: [%d] " +
                            "from the buffer pool occurs an exception.",
                    this.blockSize);
            throw new IOException(exceptionMsg, e);
        }
        this.currentBlockId++;
        if (null != this.digest) {
            this.digest.reset();
            this.currentBlockOutputStream = new DigestOutputStream(
                    new BufferOutputStream(this.currentBlockBuffer),
                    this.digest);
        } else {
            this.currentBlockOutputStream =
                    new BufferOutputStream(this.currentBlockBuffer);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (this.closed) {
            throw new IOException("block stream has been closed.");
        }

        while (len > 0) {
            long writeBytes = 0;
            if (this.blockWritten + len > this.blockSize) {
                writeBytes = this.blockSize - this.blockWritten;
            } else {
                writeBytes = len;
            }

            this.currentBlockOutputStream.write(b, off, (int) writeBytes);
            this.blockWritten += writeBytes;
            if (this.blockWritten >= this.blockSize) {
                this.uploadPart();
                this.blockWritten = 0;
            }
            len -= writeBytes;
            off += writeBytes;
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        this.write(b, 0, b.length);
    }

    @Override
    public void write(int b) throws IOException {
        if (this.closed) {
            throw new IOException("block stream has been closed.");
        }

        byte[] singleBytes = new byte[1];
        singleBytes[0] = (byte) b;
        this.currentBlockOutputStream.write(singleBytes, 0, 1);
        this.blockWritten += 1;
        if (this.blockWritten >= this.blockSize) {
            this.uploadPart();
            this.blockWritten = 0;
        }
    }
}
