package com.xuchongyang.mediacodecdemo;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.xuchongyang.mediacodecdemo.decode.EncoderDebugger;
import com.xuchongyang.mediacodecdemo.decode.NV21Convertor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

/**
 * MainActivity
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    @BindView(R.id.local_surface_view)
    SurfaceView mLocalSurfaceView;
    @BindView(R.id.remote_surface_view)
    SurfaceView mRemoteSurfaceView;
    @BindView(R.id.begin)
    Button mBeginButton;

    private int width = 1280;
    private int height = 720;
    private static final int FRAME_RATE = 30;
    private int bitrate = 2 * width * height * FRAME_RATE / 20;
    private int mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private Camera mCamera;
    private SurfaceHolder surfaceHolder;
    private boolean started = false;
    private NV21Convertor mConvertor;
    private EncoderDebugger debugger;

    private EncodeThread mEncodeThread;
    private MediaCodec mEncoder;
    private int mCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        initMediaCodec();
        mLocalSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceHolder = holder;
                createCamera(surfaceHolder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopPreview();
                destroyCamera();
            }
        });
        mLocalSurfaceView.getHolder().setFixedSize(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);

        mRemoteSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                mEncodeThread = new EncodeThread(mEncoder, holder.getSurface());
                mEncodeThread.start();
            }

            @Override
            public void surfaceChanged(final SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyCamera();
        mEncoder.stop();
        mEncoder.release();
        mEncoder = null;
        mEncodeThread.stop();
    }

    @OnClick(R.id.begin)
    public void click() {
        if (!started) {
            startPreview();
        } else {
            stopPreview();
        }
    }

    /**
     * 开启摄像头
     * @param surfaceHolder
     * @return
     */
    private boolean createCamera(SurfaceHolder surfaceHolder) {
        try {
            mCamera = Camera.open(mCameraId);
            Camera.Parameters parameters = mCamera.getParameters();
            int[] max = determineMaximumSupportedFramerate(parameters);
            Camera.CameraInfo camInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, camInfo);
            int cameraRotationOffset = camInfo.orientation;
            int rotate = (360 + cameraRotationOffset - getDegree()) % 360;
            parameters.setRotation(rotate);
            parameters.setPreviewFormat(ImageFormat.NV21);
            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
            parameters.setPreviewSize(width, height);
            parameters.setPreviewFpsRange(max[0], max[1]);
            mCamera.setParameters(parameters);
//            mCamera.autoFocus(null);
            int displayRotation;
            displayRotation = (cameraRotationOffset - getDegree() + 360) % 360;
            mCamera.setDisplayOrientation(displayRotation);
            mCamera.setPreviewDisplay(surfaceHolder);
            return true;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stack = sw.toString();
            Toast.makeText(this, stack, Toast.LENGTH_LONG).show();
            destroyCamera();
            e.printStackTrace();
            return false;
        }
    }

    public static int[] determineMaximumSupportedFramerate(Camera.Parameters parameters) {
        int[] maxFps = new int[]{0, 0};
        List<int[]> supportedFpsRanges = parameters.getSupportedPreviewFpsRange();
        for (Iterator<int[]> it = supportedFpsRanges.iterator(); it.hasNext(); ) {
            int[] interval = it.next();
            if (interval[1] > maxFps[1] || (interval[0] > maxFps[0] && interval[1] == maxFps[1])) {
                maxFps = interval;
            }
        }
        return maxFps;
    }

    /**
     * 开启预览
     */
    public synchronized void startPreview() {
        if (mCamera != null && !started) {
            mCamera.startPreview();
            int previewFormat = mCamera.getParameters().getPreviewFormat();
            Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
            int size = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(previewFormat) / 8;
            mCamera.addCallbackBuffer(new byte[size]);
            // Camera  采集信息回调
            // TODO: 17/6/15 获取到数据的格式？ YUV？支持的分辨率？
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if (data == null) {
                        return;
                    }
                    // TODO: 17/4/28 移到循环外部
                    ByteBuffer[] inputBuffers = mEncoder.getInputBuffers();
                    byte[] dst;
                    dst = data;
//        Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
//        if (getDegree() == 0) {
//            dst = Util.rotateNV21Degree90(data, previewSize.width, previewSize.height);
//        } else {
//            dst = data;
//        }
                    try {
                        int bufferIndex = mEncoder.dequeueInputBuffer(0);
                        if (bufferIndex >= 0) {
                            inputBuffers[bufferIndex].clear();
                            inputBuffers[bufferIndex].put(dst);
                            mEncoder.queueInputBuffer(bufferIndex, 0, inputBuffers[bufferIndex].position(), mCount * 1000000 / FRAME_RATE, 0);
                            mCount++;
                            synchronized (mEncodeThread) {
                                mEncodeThread.notify();
                            }
                        } else {
                            Log.e(TAG, "No buffer available !");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    mCamera.addCallbackBuffer(data);
                }
            });
            started = true;
            mBeginButton.setText("停止");
        }
    }

    /**
     * 停止预览
     */
    public synchronized void stopPreview() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
            started = false;
            mBeginButton.setText("开始");
        }
    }

    /**
     * 销毁Camera
     */
    protected synchronized void destroyCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            try {
                mCamera.release();
            } catch (Exception e) {

            }
            mCamera = null;
        }
    }

    /**
     * 初始化 MediaCodec 编码器
     */
    private void initMediaCodec() {
        String mimeType = "video/avc";
        debugger = EncoderDebugger.debug(getApplicationContext(), width, height);
        mConvertor = debugger.getNV21Convertor();

        int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecInfo codecInfo = null;
        for (int i = 0; i < numCodecs && codecInfo == null; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (!info.isEncoder()) {
                continue;
            }
            String[] types = info.getSupportedTypes();
            boolean found = false;
            for (int j = 0; j < types.length && !found; j++) {
                if (types[j].equals(mimeType)) {
                    found = true;
                }
            }
            if (!found)
                continue;
            codecInfo = info;
        }
        if (codecInfo == null) {
            return;
        }

        // TODO 确定编码器的颜色输入格式
        // TODO: 17/6/15 摄像头 API
        int colorFormat = 0;
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int format = capabilities.colorFormats[i];
            Log.e(TAG, "initMediaCodec: format is " + format);
            switch (format) {
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
                case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                    if (colorFormat == 0)
                        colorFormat = format;
                    break;
                default:
                    break;
            }
        }
        if (colorFormat == 0) {
            return;
        }

        try {
            Log.i(TAG, "initMediaCodec: name is " + debugger.getEncoderName());
            mEncoder = MediaCodec.createByCodecName(debugger.getEncoderName());
            MediaFormat mediaFormat;
//            if (dgree == 0) {
//                mediaFormat = MediaFormat.createVideoFormat("video/avc", height, width);
//            } else {
                mediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);
//            }
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, debugger.getEncoderColorFormat());
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取当前屏幕旋转角度
     * @return
     */
    private int getDegree() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
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