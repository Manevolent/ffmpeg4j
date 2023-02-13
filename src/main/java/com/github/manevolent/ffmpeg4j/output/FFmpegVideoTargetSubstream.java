package com.github.manevolent.ffmpeg4j.output;

import com.github.manevolent.ffmpeg4j.FFmpegError;
import com.github.manevolent.ffmpeg4j.FFmpegException;
import com.github.manevolent.ffmpeg4j.Logging;
import com.github.manevolent.ffmpeg4j.VideoFrame;
import com.github.manevolent.ffmpeg4j.math.Rational;
import com.github.manevolent.ffmpeg4j.stream.output.FFmpegTargetStream;

import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.*;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.*;

import java.io.EOFException;
import java.io.IOException;

public class FFmpegVideoTargetSubstream
        extends VideoTargetSubstream
        implements FFmpegEncoderContext {
    private final FFmpegTargetStream targetStream;
    private final AVStream stream;
    private final AVCodecContext codecContext;
    private final Rational timeBase;

    // sws stuff
    private final BytePointer inputBuffer;
    private final BytePointer outputBuffer;
    private final AVFrame inputFrame;
    private final AVFrame outputFrame;
    private final SwsContext sws;

    private final int pixelFormat; // input pixel format

    private final double frameRate;

    private volatile long writtenFrames = 0L;

    public FFmpegVideoTargetSubstream(FFmpegTargetStream targetStream, AVStream stream, AVCodecContext codecContext, double fps)
            throws FFmpegException {
        this.targetStream = targetStream;
        this.stream = stream;
        this.codecContext = codecContext;

        this.timeBase = Rational.fromAVRational(stream.time_base());
        this.frameRate = fps;

        this.pixelFormat = targetStream.getPixelFormat();

        // SWScale
        outputFrame = avutil.av_frame_alloc();
        if (outputFrame == null) throw new RuntimeException("failed to allocate output frame");
        inputFrame = avutil.av_frame_alloc();
        if (inputFrame == null) throw new RuntimeException("failed to allocate input frame");

        int numBytesInput = avutil.av_image_get_buffer_size(
                pixelFormat,
                stream.codecpar().width(),
                stream.codecpar().height(),
                1 // used by some other methods in ffmpeg
        );
        inputBuffer = new BytePointer(avutil.av_malloc(numBytesInput));

        int numBytesOutput = avutil.av_image_get_buffer_size(
                stream.codecpar().format(),
                stream.codecpar().width(),
                stream.codecpar().height(),
                1
        );
        outputBuffer = new BytePointer(avutil.av_malloc(numBytesOutput));

         /*
            http://stackoverflow.com/questions/29743648/which-flag-to-use-for-better-quality-with-sws-scale

            The RGB24 to YUV420 conversation itself is lossy. The scaling algorithm is probably used in downscaling
            the color information. I'd say the quality is: point << bilinear < bicubic < lanczos/sinc/spline I don't
            really know the others. Under rare circumstances sinc is the ideal scaler and lossless, but those
            conditions are usually not met. Are you also scaling the video? Otherwise I'd go for bicubic.
         */

        sws = swscale.sws_getContext(
                stream.codecpar().width(), stream.codecpar().height(), pixelFormat, // source
                stream.codecpar().width(), stream.codecpar().height(), stream.codecpar().format(), // destination
                swscale.SWS_BILINEAR, // flags (see above)
                null, null, (DoublePointer) null // filters, params
        );

        // Assign appropriate parts of buffer to image planes in pFrameRGB
        // See: https://mail.gnome.org/archives/commits-list/2016-February/msg05531.html
        FFmpegError.checkError("av_image_fill_arrays", avutil.av_image_fill_arrays(
                inputFrame.data(),
                inputFrame.linesize(),
                inputBuffer,
                pixelFormat,
                stream.codecpar().width(),
                stream.codecpar().height(),
                1
        ));

        FFmpegError.checkError("av_image_fill_arrays", avutil.av_image_fill_arrays(
                outputFrame.data(),
                outputFrame.linesize(),
                outputBuffer,
                stream.codecpar().format(),
                stream.codecpar().width(),
                stream.codecpar().height(),
                1
        ));
    }

    @Override
    public void write(VideoFrame o) throws IOException {
        if (o.getFormat() != pixelFormat)
            throw new IOException(
                    new FFmpegException("frame has mismatched pixel format: " +
                            "expected " + o.getFormat() + " != " + pixelFormat)
            );

        inputFrame.data(0).put(o.getData());
        //inputPicture.linesize(0, o.getWidth());

        int ret = swscale.sws_scale(
                sws, // the scaling context previously created with sws_getContext()
                inputFrame.data(), // 	the array containing the pointers to the planes of the source slice
                inputFrame.linesize(), // the array containing the strides for each plane of the source image
                0, // the position in the source image of the slice to process, that is the number (counted starting from zero) in the image of the first row of the slice
                stream.codecpar().height(), // the height of the source slice, that is the number of rows in the slice
                outputFrame.data(), // the array containing the pointers to the planes of the destination image
                outputFrame.linesize() // the array containing the strides for each plane of the destination image
        );

        try {
            FFmpegError.checkError("sws_scale", ret);
        } catch (FFmpegException e) {
            throw new RuntimeException(e);
        }

        //outputFrame.pts(avutil.av_rescale_q(writtenFrames, nativeTimeBase, stream.codec().time_base()));

        //outputFrame.linesize(0, o.getWidth());
        outputFrame.width(o.getWidth());
        outputFrame.height(o.getHeight());
        outputFrame.format(stream.codecpar().format());

        try {
            outputFrame.pts(writtenFrames);
            encodeFrame(outputFrame);

            writtenFrames ++;
            setPosition((double) writtenFrames / (double) frameRate);
        } catch (FFmpegException e) {
            throw new IOException(e);
        }
    }

    // I don't think we have anything to flush here
    @Override
    public void flush() throws IOException {
        // Do nothing?
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoTargetSubstream.close() called");

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "sws_freeContext(sws)...");
        swscale.sws_freeContext(sws);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_frame_free(inputFrame)...");
        avutil.av_frame_free(inputFrame);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_frame_free(outputFrame)...");
        avutil.av_frame_free(outputFrame);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "avcodec_close(codecContext))...");
        avcodec.avcodec_close(codecContext);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(codecContext)...");
        avutil.av_free(codecContext);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoTargetSubstream.close() completed");
    }

    @Override
    public void writePacket(AVPacket packet) throws FFmpegException, EOFException {
        if (packet.pts() != avutil.AV_NOPTS_VALUE)
            avcodec.av_packet_rescale_ts(packet, codecContext.time_base(), stream.time_base());

        packet.stream_index(stream.index());

        getTargetStream().writePacket(packet);
    }

    @Override
    public AVCodecContext getCodecContext() {
        return codecContext;
    }

    @Override
    public FFmpegTargetStream getTargetStream() {
        return targetStream;
    }
}
