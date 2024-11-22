package io.flutter.plugins.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;
import android.util.Log;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.media.MediaMuxer;
import java.util.LinkedList;

class EncodedData {
    public ByteBuffer byteBuffer;
    public MediaCodec.BufferInfo bufferInfo;

    public EncodedData(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        this.byteBuffer = byteBuffer;
        this.bufferInfo = bufferInfo;
    }
}

class VideoEncoder extends Thread {

    int mWidth, mHeight, mFps, mBitrate;

    MediaCodec.BufferInfo mBufferInfo;
    MediaCodec mCodec;
    volatile boolean mRunning = true;
    Surface mSurface;
    final long mTimeoutUsec;
    boolean mIsSetup = false;
    private MediaFormat mEncodedFormat;
    private MediaMuxer mMuxer;
    private int mVideoTrack = -1;
    BlockingQueue<EncodedData> mEncodedDataQueue = new LinkedBlockingQueue<EncodedData>();

    public VideoEncoder(int width, int height, int fps, int bitrate) {
        mWidth = width;
        mHeight = height;
        mFps = fps;
        mBitrate = bitrate;
        mBufferInfo = new MediaCodec.BufferInfo();
        mTimeoutUsec = 10000l;
    }

    public void shutDown() {
        mRunning = false;
    }

    @Override
    public void run() {
        Log.d("VideoEncoder2", "run...: ");
        if (!mIsSetup) {
            throw new RuntimeException("VideoEncoder2 not setup! Call setup() first.");
        }
        try {
            while (mRunning) {
                encode();
            }
            encode();
        } finally {
            release();
        }
    }

    protected void onEncodedSample(MediaCodec.BufferInfo info, ByteBuffer data) {
        Log.d("VideoEncoder2", "onEncodedSample: ");
    }

    void encode() {
        final int TIMEOUT_USEC = 0; // no wait
        while (true) {
            int encoderStatus = mCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available, try again later
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mEncodedFormat = mCodec.getOutputFormat();
                Log.d("VideoEncoder2", "encoder output format changed: " + mEncodedFormat);
            } else if (encoderStatus < 0) {
                Log.w("VideoEncoder2", "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // ignored
            } else {
                ByteBuffer encodedData = mCodec.getOutputBuffer(encoderStatus);
                ;
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.d("VideoEncoder2", "BUFFER_FLAG_CODEC_CONFIG ignored");
                }

                if (mBufferInfo.size != 0) {
                   mEncodedDataQueue.add(new EncodedData(encodedData, mBufferInfo));
                }

                mCodec.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.w("VideoEncoder2", "unexpected end of stream");
                    break; 
                }
            }
        }
    }

    void release() {
        mCodec.stop();
        mCodec.release();
        mSurface.release();
    }

    Surface getSurface() {
        return mSurface;
    }

    BlockingQueue<EncodedData> getEncodedDataQueue() {
        return mEncodedDataQueue;
    }

    MediaFormat getEncodedFormat() {
        return mEncodedFormat;
    }

    void setup() {
        setupEncoder();
        mIsSetup = true;
    }

    private void setupEncoder() {
        Log.d("VideoEncoder2", "setup: ");
        // configure encoder
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);// TODO

        try {
            mCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mCodec.createInputSurface();
        mCodec.start();
        Log.d("VideoEncoder2", "setup done!");
    }
}