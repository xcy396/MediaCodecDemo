package com.xuchongyang.mediacodecdemo;

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.xuchongyang.mediacodecdemo.decode.EncoderDebugger;
import com.xuchongyang.mediacodecdemo.decode.NV21Convertor;

import java.io.IOException;
import java.io.PipedReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by Mark Xu on 17/4/27.
 */

public class EncodeThread extends Thread {
    private static final String TAG = "EncodeThread";
    private MediaCodec mMediaCodec;
    private InitSession mInitSession;
    private long seqNumber=0;
    private byte[] mPpsSps = new byte[0];
    private boolean isEncode=true;
    private Surface mSurface;

    private Camera mCamera;
    private Activity mActivity;


    public EncodeThread(Activity activity, MediaCodec mediaCodec, Surface surface, Camera camera){
        mActivity = activity;
        this.mMediaCodec=mediaCodec;
        this.mSurface=surface;
        mCamera = camera;
    }
    @Override
    public void run() {
        this.mInitSession = new InitSession(mSurface);
        while (isEncode) {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            encode();
        }
    }

    private void encode(){
        ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        while (outputBufferIndex >= 0 && isEncode) {
            ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
            byte[] outData = new byte[bufferInfo.size];
            outputBuffer.get(outData);
            sendData(outData);
            mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    /**
     * 将每帧进行分包并发送数据
     * @param bytes
     */
    private void sendData(byte[] bytes) {
        int dataLength = (bytes.length - 1) / 1480 + 1;
        final byte[][] data = new byte[dataLength][];
        final boolean[] marks = new boolean[dataLength];
        marks[marks.length - 1] = true;
        int x = 0;
        int y = 0;
        int length = bytes.length;
        for (int i = 0; i < length; i++){
            if (y == 0){
                data[x] = new byte[length - i > 1480 ? 1480 : length - i];
            }
            data[x][y] = bytes[i];
            y++;
            if (y == data[x].length){
                y = 0;
                x++;
            }
        }
        mInitSession.rtpSession.sendData(data, null, marks, -1, null);
    }

    private int getDegree() {
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break; // Natural orientation
            case Surface.ROTATION_90:
                degrees = 90;
                break; // Landscape left
            case Surface.ROTATION_180:
                degrees = 180;
                break;// Upside down
            case Surface.ROTATION_270:
                degrees = 270;
                break;// Landscape right
        }
        return degrees;
    }
}
