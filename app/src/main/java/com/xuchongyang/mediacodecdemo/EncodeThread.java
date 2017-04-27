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
    public EncodeThread(MediaCodec mediaCodec,Surface surface){
        this.mMediaCodec=mediaCodec;
        this.mSurface=surface;
        setPriority(9);
    }
    @Override
    public void run() {
        this.mInitSession = new InitSession(mSurface);
        synchronized (this){
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        while (isEncode) {
            encode();
            synchronized (this){
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void encode(){
        ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        while (outputBufferIndex >= 0 &&isEncode) {
            ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
            byte[] outData = new byte[bufferInfo.size];
            outputBuffer.get(outData);
            //记录pps和sps
            if (outData[0] == 0 && outData[1] == 0 && outData[2] == 0 && outData[3] == 1 && outData[4] == 103) {
                mPpsSps = outData;
            } else if (outData[0] == 0 && outData[1] == 0 && outData[2] == 0 && outData[3] == 1 && outData[4] == 101) {
                //在关键帧前面加上pps和sps数据
                byte[] iframeData = new byte[mPpsSps.length + outData.length];
                System.arraycopy(mPpsSps, 0, iframeData, 0, mPpsSps.length);
                System.arraycopy(outData, 0, iframeData, mPpsSps.length, outData.length);
                outData = iframeData;
            }
            Log.e(TAG, "onPreviewFrame: send byte array " + Arrays.toString(outData));
            sendData(outData);
//                        Util.save(outData, 0, outData.length, path, true);
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
        int x=0;
        int y=0;
        int length=bytes.length;
        for (int i=0;i<length;i++){
            if (y==0){
                data[x]=new byte[length-i>1480?1480:length-i];
            }
            data[x][y]=bytes[i];
            y++;
            if (y==data[x].length){
                y=0;
                x++;
            }
        }
        mInitSession.rtpSession.sendData(data, null, marks, -1, null);
//        ThreadPoolManager.getDefault().addTask(new Runnable() {
//            @Override
//            public void run() {
//
//            }
//        });
    }
}
