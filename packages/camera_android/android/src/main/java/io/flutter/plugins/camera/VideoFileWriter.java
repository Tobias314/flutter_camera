package io.flutter.plugins.camera;
import android.util.Log;
import android.media.MediaMuxer;
import java.util.concurrent.LinkedBlockingQueue;
import android.media.MediaCodec;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import java.util.concurrent.BlockingQueue;

public class VideoFileWriter extends Thread{

    volatile boolean mRunning = true;
    protected List<Long> frameTimestamps = new ArrayList<Long>();

    private String mFilePath;
    private VideoEncoder mEncoder;
    private int mVideoTrack = -1;
    private BlockingQueue<EncodedData> mEncodedDataQueue;
    private MediaMuxer mMuxer;

    final String TAG = "VideoFileWriter";

    public VideoFileWriter(String filePath, VideoEncoder encoder) {
        mFilePath = filePath;
        mEncoder = encoder;
        mEncodedDataQueue = mEncoder.getEncodedDataQueue();
        
    }

    public void finish() {
        mRunning = false;
    }

    @Override
    public void run() {
        Log.d("VideoEncoder2", "run...: ");
        try {
            while (mRunning) {
                write();
            }
        } finally {
            release();
        }
    }
    void write() {
        if (mEncodedDataQueue.isEmpty()) {
            return;
        }

        try {
            if (mMuxer == null) {
                mMuxer = new MediaMuxer(mFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                mVideoTrack = mMuxer.addTrack(mEncoder.getEncodedFormat());
                mMuxer.start();
            }
            while (!mEncodedDataQueue.isEmpty()) {
                EncodedData data = mEncodedDataQueue.poll();
                long frameTimestamp = mEncoder.frameTimestampsQueue.take();
                frameTimestamps.add(frameTimestamp);
                //frameTimestamps.add(data.bufferInfo.presentationTimeUs / 1000);
                ByteBuffer buf = data.byteBuffer;
                MediaCodec.BufferInfo info = data.bufferInfo;
                mMuxer.writeSampleData(mVideoTrack, buf, info);
            }
        } catch (IOException ioe) {
            Log.w(TAG, "muxer failed", ioe);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void release() {
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
        }
    }
}
