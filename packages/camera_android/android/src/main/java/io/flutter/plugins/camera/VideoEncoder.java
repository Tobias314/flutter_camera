package io.flutter.plugins.camera;
import android.media.ImageReader;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyStore;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.LinkedList;
import java.util.Queue;

class InputData {
    enum DataType { VIDEO, AUDIO, STOP }
    public DataType type;
    public Image data;

    public InputData(DataType type, Image data) {
        this.type = type;
        this.data = data;
    }
}

class EncodedData {
    public ByteBuffer byteBuffer;
    public MediaCodec.BufferInfo bufferInfo;

    public EncodedData(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        this.byteBuffer = byteBuffer;
        this.bufferInfo = bufferInfo;
    }
}

public class VideoEncoder {


    private static final String TAG = "[VideoEncoder-Android]";

    private boolean isInitialized = false;
    private String filepath;
    private int mWidth;
    private int mHeight;
    private int mFps;
    private int videoBitrate;
    private boolean mMuxerStarted;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaMuxer mMediaMuxer;
    private int mVideoFrameIdx;
    private int mAudioFrameIdx;
    private int mVideoTrackIndex;
    private int mAudioTrackIndex;
    private int mTrackCount;
    private int mAudioChannels;
    private Queue<EncodedData> videoQueue = new LinkedList<>();
    private Queue<EncodedData> audioQueue = new LinkedList<>();

    // input queue for video, audio, and stop signals
    private BlockingQueue<InputData> inputQueue = new LinkedBlockingQueue<>(5);

    // signal encoding success or error
    private CompletableFuture<Void> processingResult;

    private Thread processingThread;

    public VideoEncoder(String filepath, int fps, int videoBitrate){
        this.filepath = filepath;
        mFps = fps;
        this.videoBitrate = videoBitrate;
    }

    public void setup(int width, int height) throws InterruptedException, IOException {
        // Clear queues
        inputQueue.clear();
        videoQueue.clear();
        audioQueue.clear();

        // reset
        stopProcessingThread();

        // save
        mHeight = height;
        mWidth = width;

        // reset
        mVideoFrameIdx = 0;
        mAudioFrameIdx = 0;
        mMuxerStarted = false;
        mTrackCount = 0;

        // Initialize the MediaMuxer
        mMediaMuxer = new MediaMuxer(filepath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // setup video?
        if (width != 0 && height != 0) {

            // color format
            int colorFormat = getColorFormat();
            if (isColorFormatSupported("video/avc", colorFormat) == false) {
                return;
            }
                
            // Video format
            Log.i(TAG, "calling MediaFormat.createVideoFormat()");
            MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", width, height);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            //videoFormat.setInteger(MediaFormat.KEY_LATENCY, 1);

            
            // Video encoder
            mVideoEncoder = MediaCodec.createEncoderByType("video/avc");
            Log.i(TAG, "calling mVideoEncoder.configure()");
            mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            // start
            try {
                Log.i(TAG, "calling mVideoEncoder.start()");
                mVideoEncoder.start();
            } catch (Exception e) {
                return;
            }
        }
        isInitialized = true;

        // Start thread
        startProcessingThread();
    }

    public void addFrame(Image frame) throws InterruptedException, IOException {
        if(!isInitialized){
            this.setup(frame.getWidth(), frame.getHeight());
        }
        InputData inputData = new InputData(InputData.DataType.VIDEO, frame);
        inputQueue.put(inputData);
    }

    public void finish() throws ExecutionException, InterruptedException {
        // if processing error, throw exception
        if (processingResult.isDone()) {
            processingResult.get();
        }

        // Send STOP signal
        inputQueue.put(new InputData(InputData.DataType.STOP, null));

        // Wait for processingResult to complete
        processingResult.get();
    }

    private void stopProcessingThread() throws InterruptedException {
        if (processingThread != null && processingThread.isAlive()) {
            inputQueue.put(new InputData(InputData.DataType.STOP, null));
            processingThread.join(); 
        }
    }

    private void startProcessingThread() {
        processingResult = new CompletableFuture<>();
        processingThread = new Thread(() -> {
            try {
                while (true) {
                    InputData inputData = inputQueue.take(); // Blocks if queue is empty
                    if (inputData.type == InputData.DataType.STOP) {
                        // Finish processing
                        break;
                    } else if (inputData.type == InputData.DataType.VIDEO) {
                        Image frame = inputData.data;
                        feedVideoEncoder(frame);
                        drainEncoder(mVideoEncoder, false);
                    }
                }
                // Finalize encoders
                if (mVideoEncoder != null) {
                    drainEncoder(mVideoEncoder, true);
                    mVideoEncoder.stop();
                    mVideoEncoder.release();
                    mVideoEncoder = null;
                }
                if (mAudioEncoder != null) {
                    drainEncoder(mAudioEncoder, true);
                    mAudioEncoder.stop();
                    mAudioEncoder.release();
                    mAudioEncoder = null;
                }
                if (mMediaMuxer != null) {
                    if (mMuxerStarted) {
                        mMediaMuxer.stop();
                    }
                    mMediaMuxer.release();
                    mMediaMuxer = null;
                }

                // Complete successfully
                processingResult.complete(null);

            } catch (Exception e) {
                Log.e(TAG, "Error in processing thread", e);
                processingResult.completeExceptionally(e);
                inputQueue.clear();  // release input threads
            }
        });
        processingThread.start();
    }

    private void feedVideoEncoder(Image frame) throws Exception {
        // Calculate presentation time
        long presentationTime = mVideoFrameIdx * 1000000L / mFps;

        // Dequeue input buffer
        int inIdx = mVideoEncoder.dequeueInputBuffer(-1);
        if (inIdx >= 0) {
            // Get buffer size
            ByteBuffer buffer = mVideoEncoder.getInputBuffer(inIdx);
            int size = buffer.capacity();

            // Get input image
            Image inputImage = mVideoEncoder.getInputImage(inIdx);

            inputImage.getPlanes()[0] = frame.getPlanes()[0];
            inputImage.getPlanes()[1] = frame.getPlanes()[1];
            inputImage.getPlanes()[2] = frame.getPlanes()[2];

            // Queue input buffer
            mVideoEncoder.queueInputBuffer(inIdx, 0, size, presentationTime, 0);
        }

        // Increment frame index
        mVideoFrameIdx++;
    }

    private boolean isColorFormatSupported(String mimeType, int desiredColorFormat) {
        MediaCodecInfo codecInfo = getCodecInfo(mimeType);
        if (codecInfo == null) {
            return false;
        }

        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == desiredColorFormat) {
                return true;
            }
        }

        return false;
    }

    private boolean isAudioFormatSupported(String mimeType, int sampleRate, int profile) {
        MediaCodecInfo codecInfo = getCodecInfo(mimeType);
        if (codecInfo == null) {
            return false;
        }

        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);

        // Check if sample rate is supported
        boolean isSampleRateSupported = false;
        for (int rate : capabilities.getAudioCapabilities().getSupportedSampleRates()) {
            if (rate == sampleRate) {
                isSampleRateSupported = true;
                break;
            }
        }

        // Check if profile is supported
        boolean isProfileSupported = (capabilities.profileLevels != null);
        for (MediaCodecInfo.CodecProfileLevel level : capabilities.profileLevels) {
            if (level.profile == profile) {
                isProfileSupported = true;
                break;
            }
        }

        return isSampleRateSupported && isProfileSupported;
    }

    private MediaCodecInfo getCodecInfo(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private byte[] rgbaToYuv420Planar(byte[] rgba, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + (frameSize / 4);

        byte[] yuv420 = new byte[width * height * 3 / 2];

        int r, g, b, y, u, v;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                r = rgba[j * width * 4 + i * 4] & 0xFF;
                g = rgba[j * width * 4 + i * 4 + 1] & 0xFF;
                b = rgba[j * width * 4 + i * 4 + 2] & 0xFF;

                // RGB to YUV formula
                y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                yuv420[yIndex++] = (byte) Math.max(0, Math.min(255, y));
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420[uIndex++] = (byte) Math.max(0, Math.min(255, u));
                    yuv420[vIndex++] = (byte) Math.max(0, Math.min(255, v));
                }
            }
        }

        return yuv420;
    }

    private void fillImage(Image image, byte[] yuv420, int width, int height) {
        Image.Plane[] planes = image.getPlanes();

        // Fill Y plane
        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int yOffset = 0;
        for (int i = 0; i < height; i++) {
            int yPos = i * yRowStride;
            yBuffer.position(yPos);
            for (int j = 0; j < width; j++) {
                yBuffer.put(yPos + j * yPixelStride, yuv420[yOffset++]);
            }
        }

        // Fill U plane
        ByteBuffer uBuffer = planes[1].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int uHeight = height / 2;
        int uWidth = width / 2;
        int uOffset = width * height;
        for (int i = 0; i < uHeight; i++) {
            int uPos = i * uRowStride;
            uBuffer.position(uPos);
            for (int j = 0; j < uWidth; j++) {
                uBuffer.put(uPos + j * uPixelStride, yuv420[uOffset++]);
            }
        }

        // Fill V plane
        ByteBuffer vBuffer = planes[2].getBuffer();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int vHeight = height / 2;
        int vWidth = width / 2;
        int vOffset = width * height + (width / 2) * (height / 2);
        for (int i = 0; i < vHeight; i++) {
            int vPos = i * vRowStride;
            vBuffer.position(vPos);
            for (int j = 0; j < vWidth; j++) {
                vBuffer.put(vPos + j * vPixelStride, yuv420[vOffset++]);
            }
        }
    }

    private int getColorFormat() {
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    private void signalEndOfStream(MediaCodec encoder) {
        try {
            int inputBufferIndex = encoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                // No data, but signal end of stream through the buffer flag.
                encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error signaling end of stream: ", e);
            // Handle error
        }
    }

    private int expectedTrackCount() {
        if (mAudioChannels > 0 && mWidth > 0) {
            return 2;
        } else {
            return 1;
        }
    }

    private void processQueues() {
        for (int i = 0; i < 2; i++) {
            Queue<EncodedData> queue = i == 0 ? videoQueue : audioQueue;
            int trackIndex = i == 0 ? mVideoTrackIndex : mAudioTrackIndex;
            while (!queue.isEmpty()) {
                EncodedData data = queue.poll(); // Retrieve and remove the head of the queue
                ByteBuffer byteBuffer = data.byteBuffer;
                MediaCodec.BufferInfo bufferInfo = data.bufferInfo;

                byteBuffer.position(bufferInfo.offset);
                byteBuffer.limit(bufferInfo.offset + bufferInfo.size);
                mMediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo); // Write data to the MediaMuxer
            }
        }
    }

    /**
     * Extracts all pending data from the specified encoder & feed it to the muxer.
     *
     * @param encoder The MediaCodec encoder to drain.
     * @param trackIndex The muxer track index associated with this encoder.
     * @param endOfStream If true, signals end-of-stream to the encoder.
     */
    private void drainEncoder(MediaCodec encoder, boolean endOfStream) {
        final int TIMEOUT_USEC = endOfStream ? 10000 : 0;
        if (endOfStream) {
            signalEndOfStream(encoder);
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER)
            {
                if (!endOfStream) {
                    break; // Exit the loop if not EOS
                }
            } 
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
            {
                MediaFormat newFormat = encoder.getOutputFormat();
                Log.i(TAG, "calling mMediaMuxer.addTrack()");
                if (encoder == mVideoEncoder) {
                    mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
                } else {
                    mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
                }
                mTrackCount++;
                if (mTrackCount == expectedTrackCount()) {
                    Log.i(TAG, "calling mMediaMuxer.start()");
                    mMediaMuxer.start();
                    mMuxerStarted = true;
                }
            }
            else if (encoderStatus < 0)
            {
                // Ignore unexpected status.
                Log.e(TAG, "encoderStatus < 0");
            } 
            else 
            {
                ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Ignore codec config data.
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0) {
                    EncodedData data = new EncodedData(encodedData, bufferInfo);
                    if (encoder == mVideoEncoder) {
                        videoQueue.add(data);
                    } else {
                        audioQueue.add(data);
                    }
                    if (mMuxerStarted) {
                        processQueues();
                    }
                }

                encoder.releaseOutputBuffer(encoderStatus, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break; // Break out of the loop if EOS is reached.
                }
            }
        }
    }
}