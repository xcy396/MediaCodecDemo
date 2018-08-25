### 由于个人工作变更，本项目不再进行维护



公司的项目中视频通话模块计划采用的方案包括开源库 Linphone、MediaCodec 实现、WebRTC 等，由于目标设备硬件配置较差，采用软编软解的 Linphone 在性能上遇到了瓶颈。因此最近在尝试使用 MediaCodec 和 rtp 库 jlibrtp 来实现视频通话。

在本次实践中遇到的问题包括：
1、分包、合包问题
3、在 UI 线程发送数据的问题（sendData 的耗时操作解决）
4、Android 相机方向问题
5、视频通话性能优化问题
6、颜色模式问题(todo)
7、MediaCodec 的实现原理

# 整体结构

![](http://oj1pajfyu.bkt.clouddn.com/MediaCodec.png)

# MediaCodec 的原理

使用 MediaCodec 实现视频通话，必须搞懂对 MediaCodec 的原理，不然编解码的代码都看不懂，更不用谈后面 rtp 包的相关问题了。关于 MediaCodec 的原理，我是多次阅读[卢俊老师的博客](http://ticktick.blog.51cto.com/823160/1760191)后，才完全搞明白的，在此摘取卢老师博客的精华部分，帮助理解：

> 1、MediaCodec提供了一套访问 Android 底层多媒体模块的接口，主要是音视频的编解码接口

> 2、Android 底层多媒体模块采用的是 OpenMax 框架，任何 Android 底层编解码模块的实现，都必须遵循 OpenMax 标准。Google 官方默认提供了一系列的软件编解码器：包括：OMX.google.h264.encoder，OMX.google.h264.decoder， OMX.google.aac.encoder， OMX.google.aac.decoder 等等，而硬件编解码功能，则需要由芯片厂商依照 OpenMax 框架标准来完成，所以，一般采用不同芯片型号的手机，硬件编解码的实现和性能是不同的

> 3、Android 应用层统一由 MediaCodec API 来提供各种音视频编解码功能，由参数配置来决定采用何种编解码算法、是否采用硬件编解码加速等等

MediaCodec 的基本使用流程如下：

```plain
- createEncoderByType/createDecoderByType
- configure
- start
- while(1) {
  		- dequeueInputBuffer
  		- queueInputBuffer
  		- dequeueOutputBuffer
  		- releaseOutBuffer
  }
- stop
- release
```

下面是 Android 官方文档上的图，所有精华都在这图中了，再结合上面的使用流程就不难理解了。

![](http://oj1pajfyu.bkt.clouddn.com/mediaCodec1.png)

下面结合视频通话模块分编码和解码两个部分介绍：

#### 编码

对于编码，就是 camera 采集镜头，传给 MediaCodec 进行编码，打包成 H.264 包后给 jlibrtp 通过 rtp 包发送出去。

MediaCodec 对象（编码器）左右两侧各维护一个缓冲区队列。

左侧的为 input buffer 队列， client（camera） 负责生产 input data 数据，首先申请队列中空的 buffer（dequeueInputBuffer 方法）
然后 client 将采集的数据填入申请的空 buffer 中并将其放回队列（queueInputBuffer 方法）
MediaCodec 进行编码，编码成功后将数据放入 output buffer 队列，将原始数据 buffer 置为空后再放入 input buffer 队列。
右侧的 client（可以理解为 rlibrtp）从 output buffer 队列申请编码好的 buffer，并发送（dequeueOutputBuffer 方法）
发送完成后，右侧 client 再将该 buffer 放回 output buffer 缓冲区队列（releaseOutBuffer 方法）
至此，一帧数据的编码工作已经完成。

#### 解码

理解了编码的工作原理，解码就很简单了，MediaCodec 同样是维护两个缓冲区队列。

左侧的 client（可以理解为 rlibrtp）接收到对方传来的数据后，从 input 缓冲区申请空的 buffer（dequeueInputBuffer 方法）
将接收到的数据填入申请的空 buffer 中，并放回 input buffer 缓冲区队列（queueInputBuffer 方法）
MediaCodec 进行解码，解码成功后将数据放入 output buffer 队列，将原始数据 buffer 置为空后再放回 input buffer 队列。
右侧的 client（可以理解为 APP 端）从 output buffer 队列中申请解码好的数据，并进行渲染（dequeueOutputBuffer 方法）
渲染完成后，右侧 client 再将其放回 output buffer 队列（releaseOutBuffer 方法）
这样，解码一帧的工作也就完成了，可以看到，真正的编解码工作 MediaCodec 都默默的为我们做了，我们需要实现的就是不停地塞数据、取数据。原理明白后，剩下的就是具体实现以及优化工作了。

# 分包、合包问题
#### 分包问题

实现 RTP 协议的开源库有 C 编写的 ORTP，C++编写的 JRTPLIB，以及 java 实现的 jlibrtp。我采用的是 jlibrtp，由于 jlibrtp 对于每一包的大小限制在1480字节，因此需要在发送数据前对编码好的 H.264数据进行分包。

一开始的分包代码是这样写的：

```java
/**
     * 将每帧进行分包并发送数据
     * @param bytes
     */
    private void sendData(byte[] bytes) {
        int dataLength = (bytes.length - 1) / 1480 + 1;
        final byte[][] data = new byte[dataLength][];
        final boolean[] marks = new boolean[dataLength];
        marks[marks.length - 1] = true;
        long[] seqNumbers = new long[dataLength];
        for (int i = 0;i < dataLength;i++){
            seqNumbers[i] = seqNumber;
            try{
                seqNumber++;
            }catch (Throwable t){
                seqNumber = 0;
            }
        }
        int num = 0;
        do{
            int length = bytes.length > 1480 ? 1480 : bytes.length;
            data[num] = Arrays.copyOf(bytes,length);
            num++;
            byte[] b = new byte[bytes.length - length];
            for(int i = length; i < bytes.length; i++){
                b[i - length] = bytes[i];
            }
            bytes = b;
        } while (bytes.length > 0);
        mInitSession.rtpSession.sendData(data, null, marks, System.currentTimeMillis(), null);
    }
```

在低配置手机上进行测试后，发现分包这里的多重循环会对数据的发送效率产生影响，故对着手对此段代码进行优化。

优化后代码如下：

```java
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
```

#### 合包问题
由于一开始未考虑到合包（o(╯□╰)o），导致视频接通后，一直都是上半部分画面可见，下半部分严重乱码。经过和同事探讨、分析，发现在接收到 rtp 包后，直接扔给 MediaCodec 进行解码了，并未对 rtp 包进行拼接。

正确的操作应为根据 rtp 包的 mark 标志位，判断该包是否为一帧的最后一包，是则一帧拼接完成，开始解码；否则继续拼接。将拼接好的一帧数据传给 MediaCodec 进行解码，顺利出现完整图像。

rtp 标志位的解释由有效负载类型决定。对于视频流,它标志一帧的结束.而对音频,则表示一次谈话的开始。

合包代码如下：

```java
@Override
	public void receiveData(DataFrame frame, Participant p){
		if (buf == null){
			buf = frame.getConcatenatedData();
		} else {
			buf = Util.merge(buf, frame.getConcatenatedData());
		}
		if (frame.marked()){
			decode(buf);
			buf = null;
		}
	}
```

# 在 UI 线程发送数据的问题（sendData 的耗时操作解决）
最棘手的问题出现在 sendData 方法上。由于前期的测试设备为moto x 和小米5，两台手机系统均为 Android7.1.1，视频通话正常。在切换到目标设备（系统 Android4.2）后，发现一个棘手问题，RTPSession 实例的 sendData()方法会一直报 NetworkOnMainThreadException 的异常。经测试 Android4.4, 5.0.1, 5.1设备有同样的现象出现，不知道是不是 Android7.0以上对于主线程访问网络有所变更？暂未解。

解决方法只能为将 sendData() 的动作放到子线程操作。

对于从 output buffer 缓冲区队列拿取数据并发送的代码放到子线程执行，代码如下：

```java
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
```

获取完一帧数据后，执行 wait()，在 camera 的下一次回调时进行唤醒：

```java
...
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
...
```

# Android 相机方向问题
Android 的 Activity 设为竖屏时，SurfaceView 预览图像将会颠倒90度。我们在使用 Android 相机进行拍照的时候会发现，手机方向无论怎么转动，手机上显示的画面总是朝上的，这是因为 Android 底层 API 已经为我们做过相应的转换了。

Android 相机的图像数据都是源于摄像头硬件的图像传感器，而这个传感器是有一个默认的取景方向的，它的取景方向并不会随着手机的转动而变，方向如图所示：

![](http://oj1pajfyu.bkt.clouddn.com/MediaCodec2.jpeg)

可以看到取景方向相当于是将屏幕方向逆时针旋转90度了，而当竖着拿手机时，Android 的 camera 已经默认为我们将取到的图像旋转90度，这样显示就正确了。

我们在视频通话时，由于本地预览的 surfaceview 会经系统的 MediaServer 服务为我们进行旋转，所以显示正常。而对方过来的画面未经处理直接显示的话，在 Activity 竖屏时，就会出现图像旋转90度的问题。

在我第一次从网上找的 MediaCodec demo 中，作者采用了通过代码进行旋转的方法，方法如下：

```java
/**
     * 将YUV420SP数据顺时针旋转90度
     *
     * @param data        要旋转的数据
     * @param imageWidth  要旋转的图片宽度
     * @param imageHeight 要旋转的图片高度
     * @return 旋转后的数据
     */
    public static byte[] rotateNV21Degree90(byte[] data, int imageWidth, int imageHeight) {
        byte[] yuv = new byte[imageWidth * imageHeight * 3 / 2];
        // Rotate the Y luma
        int i = 0;
        for (int x = 0; x < imageWidth; x++) {
            for (int y = imageHeight - 1; y >= 0; y--) {
                yuv[i] = data[y * imageWidth + x];
                i++;
            }
        }
        // Rotate the U and V color components
        i = imageWidth * imageHeight * 3 / 2 - 1;
        for (int x = imageWidth - 1; x > 0; x = x - 2) {
            for (int y = 0; y < imageHeight / 2; y++) {
                yuv[i] = data[(imageWidth * imageHeight) + (y * imageWidth) + x];
                i--;
                yuv[i] = data[(imageWidth * imageHeight) + (y * imageWidth) + (x - 1)];
                i--;
            }
        }
        return yuv;
    }
```

这样，对方的数据是可以正常显示了，但也引出了下面的一个性能问题，在视频初步调通之后，我们发现 Android 设备的 cpu 使用率一直维持在20%左右，在硬编硬解的情况下，这么高的数值显然是不正常的。经多重排查，查明 rotateNV21Degree90()方法中的嵌套循环也是导致 cpu 使用率高德原因之一。

故而只能尝试通过硬件进行旋转视频数据。
# 颜色模式问题(todo)
# 视频性能优化
性能问题一直是这个项目中的痛点，由于我们的目标设备如楼宇门口机、电梯广告机这些设备的配置都是很低的。所以视频的流畅度的优化就很重要了，这也是为什么放弃 Linphone 的原因，因为软编软解实在无法在低配置下流畅通话。

这次的视频流畅度优化问题主要从以下几方面入手：

分包、发包的速度
颜色模式转换的资源占用
视频方向转换的资源占用
1、分包、发包的速度很关键，跟不上的话会直接导致对方接收到的包很慢，显得卡顿。参考[日本开发者的这个 demo](https://github.com/pingu342/android-app-mediacodectest/blob/master/src/jp/saka/mediacodectest/MediaCodecTest.java)，这个 demo 使用了一个环形队列对于采集到的每一帧数据进行处理，之后再给 MediaCodec 进行编码、发包。需要的朋友也可以进行尝试。

2、一开始的颜色模式转换、视频方向转换我都是参考网上的例子采用代码进行转换，这两段代码中有大量的嵌套循环，是导致 cpu 使用率上升的重要原因，也是解决问题的关键点所在，大家可以关注下。

<br/><br/>

<center><img src="http://oj1pajfyu.bkt.clouddn.com/mygroup.png" style="zoom: 40%" />
<center>Android 开发资源共享群，只为共同的进步！
