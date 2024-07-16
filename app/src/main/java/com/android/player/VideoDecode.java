package com.android.player;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class VideoDecode extends Thread{

    private static final String TAG = "llx";
    private File sourceFile;
    public int mVideoWidth;
    public int mVideoHeight;
    private boolean mLoop = true;
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private VideoPlayer videoPlayer;

    private OutputStream outputStream = null;
    private LocalSocket localSocket = new LocalSocket(LocalSocket.SOCKET_STREAM);
    private LocalSocketAddress localSocketAddress = new LocalSocketAddress("/dev/socket/video0",LocalSocketAddress.Namespace.FILESYSTEM);

    public VideoDecode(VideoPlayer videoPlayer) {
        this.videoPlayer=videoPlayer;
    }

    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }

    @Override
    public synchronized void start() {
        initDecode();
        super.start();
    }

    @Override
    public void run() {
        super.run();
        doExtract();
    }

    @Override
    public void interrupt() {
        mLoop = false;
        try {
            outputStream.close();
            localSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.interrupt();
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    private void initDecode() {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(sourceFile.toString()); //抛异常
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + sourceFile);
            }
            extractor.selectTrack(trackIndex);

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            Log.d(TAG, "Video size is " + mVideoWidth + "x" + mVideoHeight);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    private static int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.d("videodecode",mime);
            if (mime.startsWith("video/")) {
                Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                return i;
            }
        }
        return -1;
    }

    private void doExtract()  {
        MediaCodec decoder = null;

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(sourceFile.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        int trackIndex = selectTrack(extractor);
        if (trackIndex < 0) {
            throw new RuntimeException("No video track found in " + sourceFile);
        }
        extractor.selectTrack(trackIndex);

        MediaFormat format = extractor.getTrackFormat(trackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);
        try {
            decoder = MediaCodec.createDecoderByType(mime);
        } catch (IOException e) {
            e.printStackTrace();
        }
        decoder.configure(format, null, null, 0);
        decoder.start();


        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        PlayVideo playFrame = videoPlayer;

        while (!isInterrupted()) {
            int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufIndex >= 0) {
                ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                // Read the sample data into the ByteBuffer.  This neither respects nor
                // updates inputBuf's position, limit, etc.
                int chunkSize = extractor.readSampleData(inputBuf, 0);
                if (chunkSize < 0) {
                    // End of stream -- send empty frame with EOS flag set.
                    decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    if (extractor.getSampleTrackIndex() != trackIndex) {
                        Log.w(TAG, "WEIRD: got sample from track " +
                                extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                    }
                    long presentationTimeUs = extractor.getSampleTime();
                    decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, presentationTimeUs, 0 /*flags*/);
                    extractor.advance();
                }
            }


            int decoderStatus = decoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = decoder.getOutputFormat();
                Log.d("llx",newFormat.toString());
            } else if (decoderStatus >= 0) {
                boolean doRender = (mBufferInfo.size != 0);
                if (doRender) {
                    ByteBuffer buffer = decoder.getOutputBuffer(decoderStatus);
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    injectCamera(bytes);
                    playFrame.doFrame(bytes);
                }

                decoder.releaseOutputBuffer(decoderStatus, doRender);

                if (((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) && mLoop) {
                    Log.d(TAG, "Reached EOS, looping");
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    decoder.flush();    // reset decoder state
                }
            }
        }
        playFrame.playFinish();
    }

    private boolean injectCamera(byte[] bytes) {
        try {
            if(!localSocket.isConnected()) {
                localSocket.connect(localSocketAddress);
                localSocket.setSendBufferSize(mVideoWidth*mVideoHeight*3/2);
            }

            if(localSocket.isConnected()) {
                if(outputStream==null) {
                    outputStream = localSocket.getOutputStream();
                }

                if(outputStream!=null) {
                    outputStream.write(bytes);
                    return true;
                }
            } else {
                Log.d("llx","Connect Fail");
            }
        } catch (IOException e) {
            try {
                if(outputStream!=null) {
                    outputStream.close();
                    outputStream = null;
                }
                localSocket.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            localSocket = new LocalSocket(LocalSocket.SOCKET_STREAM);
        }
        return false;
    }

    public interface PlayVideo {
        void doFrame(byte[] frame);
        void playFinish();
    }

    public void playFrame(byte[] yuv, YUVRenderer glRenderer) {
        glRenderer.update(yuv, getVideoWidth(), getVideoHeight());
    }
}
