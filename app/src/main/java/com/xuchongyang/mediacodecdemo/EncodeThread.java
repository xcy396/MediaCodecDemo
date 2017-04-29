package com.xuchongyang.mediacodecdemo;

import android.media.MediaCodec;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created by Mark Xu on 17/4/27.
 */

public class EncodeThread extends Thread {
    private static final String TAG = "EncodeThread";
    private MediaCodec mEncoder;
    protected InitSession mInitSession;
    protected boolean isEncode = true;
    private Surface mSurface;

    public EncodeThread(MediaCodec encoder, Surface surface){
        mEncoder = encoder;
        mSurface = surface;
    }

    @Override
    public void run() {
        mInitSession = new InitSession(mSurface);
        while (isEncode) {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            getEncodeData();
        }
    }

    /**
     * 获取编码后的数据并发送
     */
    private void getEncodeData(){
        // TODO: 17/4/28 移到循环外部
        ByteBuffer[] outputBuffers = mEncoder.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, 0);
        while (outputBufferIndex >= 0 && isEncode) {
            ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
            byte[] outData = new byte[bufferInfo.size];
            outputBuffer.get(outData);
            sendData(outData);
            mEncoder.releaseOutputBuffer(outputBufferIndex, false);
            outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, 0);
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
        Log.e(TAG, "sendData: " + Arrays.deepToString(data));
    }
}