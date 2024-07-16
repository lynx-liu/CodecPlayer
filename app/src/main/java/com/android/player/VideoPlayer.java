package com.android.player;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;

public class VideoPlayer implements VideoDecode.PlayVideo {
    private VideoDecode mVideoDecode;
    private YUVRenderer glRenderer;

    public VideoPlayer(Context context, GLSurfaceView surfaceView) {
        surfaceView.setEGLContextClientVersion(2);

        glRenderer = new YUVRenderer(surfaceView, getDM(context));
        surfaceView.setRenderer(glRenderer);

        mVideoDecode=new VideoDecode(this);
        mVideoDecode.setSourceFile(new File("/sdcard/test.mp4"));
    }

    public void start(){
        mVideoDecode.start();
    }

    public void stop() {
        mVideoDecode.isInterrupted();
    }

    @Override
    public void doFrame(byte[] frame) {
        mVideoDecode.playFrame(frame,glRenderer);
    }

    @Override
    public void playFinish() {
        Log.d("llx","playFinish");
    }

    public DisplayMetrics getDM(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);
        return outMetrics;
    }
}
