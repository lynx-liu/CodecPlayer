package com.android.player;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
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
                    /*
                    ByteBuffer buffer = decoder.getOutputBuffer(decoderStatus);
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                     */
                    Image image = decoder.getOutputImage(decoderStatus);
                    byte[] bytes = getDataFromImage(image);

                    injectCamera(bytes);
                    playFrame.doFrame(bytes);
                }

                decoder.releaseOutputBuffer(decoderStatus, doRender);

                if (((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) && mLoop) {
                    //Log.d(TAG, "Reached EOS, looping");
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    decoder.flush();    // reset decoder state
                }
            }
        }
        playFrame.playFinish();
    }

    /**
     * Get a byte array image data from an Image object.
     * <p>
     * Read data from all planes of an Image into a contiguous unpadded,
     * unpacked 1-D linear byte array, such that it can be write into disk, or
     * accessed by software conveniently. It supports YUV_420_888/NV21/YV12
     * input Image format.
     * </p>
     * <p>
     * For YUV_420_888/NV21/YV12/Y8/Y16, it returns a byte array that contains
     * the Y plane data first, followed by U(Cb), V(Cr) planes if there is any
     * (xstride = width, ystride = height for chroma and luma components).
     * </p>
     */
    private static byte[] getDataFromImage(Image image) {
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        int rowStride, pixelStride;
        byte[] data = null;

        // Read image data
        Image.Plane[] planes = image.getPlanes();

        // Check image validity
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                if (planes.length != 3) {
                    Log.e(TAG, "YUV420 format Images should have 3 planes");
                    return null;
                }
                break;
            default:
                Log.e(TAG, "Unsupported Image Format: " + format);
                return null;
        }

        ByteBuffer buffer = null;

        int offset = 0;
        data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        //Log.d(TAG,"decode image w:"+width+", h:"+height+", bitppxl:"+ImageFormat.getBitsPerPixel(format));
        byte[] rowData = new byte[planes[0].getRowStride()];
        for (int i = 0; i < planes.length; i++) {
            int shift = (i == 0) ? 0 : 1;
            buffer = planes[i].getBuffer();
            rowStride = planes[i].getRowStride();
            pixelStride = planes[i].getPixelStride();
            // For multi-planar yuv images, assuming yuv420 with 2x2 chroma subsampling.
            int w = crop.width() >> shift;
            int h = crop.height() >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
                int length;
                if (pixelStride == bytesPerPixel) {
                    // Special case: optimized read of the entire row
                    length = w * bytesPerPixel;
                    buffer.get(data, offset, length);
                    offset += length;
                } else {
                    // Generic case: should work for any pixelStride but slower.
                    // Use intermediate buffer to avoid read byte-by-byte from
                    // DirectByteBuffer, which is very bad for performance
                    length = (w - 1) * pixelStride + bytesPerPixel;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[offset++] = rowData[col * pixelStride];
                    }
                }
                // Advance buffer the remainder of the row stride
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
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
