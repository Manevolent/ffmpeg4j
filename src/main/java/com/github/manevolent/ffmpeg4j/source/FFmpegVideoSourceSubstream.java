package com.github.manevolent.ffmpeg4j.source;

import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.math.Rational;
import com.github.manevolent.ffmpeg4j.stream.source.FFmpegSourceStream;
import org.bytedeco.javacpp.*;

import java.io.IOException;

public class FFmpegVideoSourceSubstream
        extends VideoSourceSubstream
        implements FFmpegDecoderContext {
    // FFmpeg native stuff (for video conversion)
    private final avcodec.AVCodecContext codecContext;
    private final BytePointer buffer;
    private final avutil.AVFrame pFrameOut;
    private final swscale.SwsContext sws;

    // Managed stuff
    private final avformat.AVStream stream;
    private final FFmpegSourceStream parentStream;
    private final VideoFormat videoFormat;

    private volatile long totalDecoded;

    private final int frameSizeBytes;

    private int pixelFormat;

    public FFmpegVideoSourceSubstream(FFmpegSourceStream parentStream,
                                      avformat.AVStream stream,
                                      int pixelFormat) throws FFmpegException {
        super(parentStream);

        this.pixelFormat = pixelFormat;
        this.stream = stream;

        avcodec.AVCodecContext codecContext = stream.codec();

        this.parentStream = parentStream;
        this.codecContext = codecContext;

        pFrameOut = avutil.av_frame_alloc();
        if (pFrameOut == null) throw new RuntimeException("failed to allocate destination frame");

        this.frameSizeBytes = avcodec.avpicture_get_size(
                pixelFormat,
                stream.codec().width(),
                stream.codec().height()
        );

        buffer = new BytePointer(avutil.av_malloc(frameSizeBytes));

         /*
            http://stackoverflow.com/questions/29743648/which-flag-to-use-for-better-quality-with-sws-scale

            The RGB24 to YUV420 conversation itself is lossy. The scaling algorithm is probably used in downscaling
            the color information. I'd say the quality is: point << bilinear < bicubic < lanczos/sinc/spline I don't
            really know the others. Under rare circumstances sinc is the ideal scaler and lossless, but those
            conditions are usually not met. Are you also scaling the video? Otherwise I'd go for bicubic.
         */

        sws = swscale.sws_getContext(
                stream.codec().width(), stream.codec().height(), stream.codec().pix_fmt(), // source
                stream.codec().width(), stream.codec().height(), pixelFormat, // destination
                swscale.SWS_BILINEAR, // flags (see above)
                null, null, (DoublePointer) null // filters, params
        );

        // Assign appropriate parts of buffer to image planes in pFrameRGB
        // Note that pFrameRGB is an AVFrame, but AVFrame is a superset
        // of AVPicture
        int ret = avcodec.avpicture_fill(
                new avcodec.AVPicture(pFrameOut),
                buffer,
                pixelFormat,
                stream.codec().width(),
                stream.codec().height()
        );

        FFmpegError.checkError("avpicture_fill", ret);

        Rational rational = Rational.fromAVRational(stream.r_frame_rate());

        this.videoFormat = new VideoFormat(
                stream.codec().width(),
                stream.codec().height(),
                rational.toDouble()
        );
    }

    @Override
    public int getBitRate() {
        return (int) stream.codec().bit_rate();
    }

    @Override
    public boolean read() throws IOException {
        return parentStream.readPacket() != null;
    }

    @Override
    public VideoFormat getFormat() {
        return videoFormat;
    }

    @Override
    public avcodec.AVCodecContext getCodecContext() {
        return codecContext;
    }

    @Override
    public void decode(avutil.AVFrame frame) throws FFmpegException {
        int ret = swscale.sws_scale(
                sws, // the scaling context previously created with sws_getContext()
                frame.data(), // 	the array containing the pointers to the planes of the source slice
                frame.linesize(), // the array containing the strides for each plane of the source image
                0, // the position in the source image of the slice to process, that is the number (counted starting from zero) in the image of the first row of the slice
                stream.codec().height(), // the height of the source slice, that is the number of rows in the slice
                pFrameOut.data(), // the array containing the pointers to the planes of the destination image
                pFrameOut.linesize() // the array containing the strides for each plane of the destination image
        );

        FFmpegError.checkError("sws_scale", ret);

        BytePointer data = pFrameOut.data(0);
        int l = pFrameOut.linesize(0);

        // Allocate pixel data buffer:
        byte[] pixelData = new byte[frameSizeBytes];
        data.position(0).get(pixelData, 0, l * frame.height());

        setPosition((double) frame.pkt_dts() * Rational.fromAVRational(stream.time_base()).toDouble());
        double position = getPosition();
        double time = 1D / videoFormat.getFramesPerSecond();
        double timestamp = parentStream.getCreatedTime() + position;
        parentStream.updatePacketTimestamp(timestamp);

        put(new VideoFrame(
                timestamp,
                position,
                time,
                pixelFormat,
                stream.codec().width(),
                stream.codec().height(),
                pixelData
        ));

        totalDecoded ++;

        //(double) frame.pts() * (double) Rational.fromAVRational(stream.time_base()).toDouble()
        //setPosition((double)totalDecoded / videoFormat.getFramesPerSecond());
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoSourceSubstream.close() called");

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "sws_freeContext(sws)...");
        swscale.sws_freeContext(sws);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(buffer)...");
        avutil.av_free(buffer);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(pFrameOut)...");
        avutil.av_free(pFrameOut);

        // Close a given AVCodecContext and free all the data associated with it (but not the AVCodecContext itself).
        // So free it afterwards.
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "avcodec_close(codecContext)...");
        avcodec.avcodec_close(codecContext);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(codecContext)...");
        avutil.av_free(codecContext);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoSourceSubstream.close() completed");
    }
}
