package com.github.manevolent.ffmpeg4j.source;

import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.math.Rational;
import com.github.manevolent.ffmpeg4j.stream.source.FFmpegSourceStream;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.javacpp.*;

import java.io.IOException;

public class FFmpegVideoSourceSubstream
        extends VideoSourceSubstream
        implements FFmpegDecoderContext {
    // FFmpeg native stuff (for video conversion)
    private final AVCodecContext codecContext;
    private final BytePointer buffer;
    private final AVFrame pFrameOut;
    private final SwsContext sws;

    // Managed stuff
    private final AVStream stream;
    private final FFmpegSourceStream parentStream;
    private final VideoFormat videoFormat;

    private volatile long totalDecoded;

    private final int frameSizeBytes;

    private int pixelFormat;

    private boolean closed = false;

    public FFmpegVideoSourceSubstream(FFmpegSourceStream parentStream,
                                      AVStream stream,
                                      AVCodecContext codecContext,
                                      int pixelFormat) throws FFmpegException {
        super(parentStream);

        this.pixelFormat = pixelFormat;
        this.stream = stream;

        this.parentStream = parentStream;
        this.codecContext = codecContext;

        pFrameOut = avutil.av_frame_alloc();
        if (pFrameOut == null) throw new RuntimeException("failed to allocate destination frame");

        this.frameSizeBytes = avutil.av_image_get_buffer_size(
                        pixelFormat,
                        stream.codecpar().width(),
                        stream.codecpar().height(),
                        1 // used by some other methods in ffmpeg
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
                stream.codecpar().width(), stream.codecpar().height(), stream.codecpar().format(), // source
                stream.codecpar().width(), stream.codecpar().height(), pixelFormat, // destination
                swscale.SWS_BILINEAR, // flags (see above)
                null, null, (DoublePointer) null // filters, params
        );

        // Assign appropriate parts of buffer to image planes in pFrameRGB
        // See: https://mail.gnome.org/archives/commits-list/2016-February/msg05531.html
        FFmpegError.checkError("av_image_fill_arrays", avutil.av_image_fill_arrays(
                pFrameOut.data(),
                pFrameOut.linesize(),
                buffer,
                pixelFormat,
                stream.codecpar().width(),
                stream.codecpar().height(),
                1
        ));

        Rational rational = Rational.fromAVRational(stream.r_frame_rate());

        this.videoFormat = new VideoFormat(
                stream.codecpar().width(),
                stream.codecpar().height(),
                rational.toDouble()
        );
    }

    @Override
    public int getBitRate() {
        return (int) stream.codecpar().bit_rate();
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
    public AVCodecContext getCodecContext() {
        return codecContext;
    }

    @Override
    public void decode(AVFrame frame) throws FFmpegException {
        int ret = swscale.sws_scale(
                sws, // the scaling context previously created with sws_getContext()
                frame.data(), // 	the array containing the pointers to the planes of the source slice
                frame.linesize(), // the array containing the strides for each plane of the source image
                0, // the position in the source image of the slice to process, that is the number (counted starting from zero) in the image of the first row of the slice
                stream.codecpar().height(), // the height of the source slice, that is the number of rows in the slice
                pFrameOut.data(), // the array containing the pointers to the planes of the destination image
                pFrameOut.linesize() // the array containing the strides for each plane of the destination image
        );

        FFmpegError.checkError("sws_scale", ret);

        BytePointer data = pFrameOut.data(0);
        int l = pFrameOut.linesize(0);

        // Allocate pixel data buffer:
        byte[] pixelData = new byte[frameSizeBytes];
        data.position(0).get(pixelData, 0, l * frame.height());

        double position = FFmpeg.timestampToSeconds(stream.time_base(), frame.pkt_dts());
        setPosition(position);
        double time = 1D / videoFormat.getFramesPerSecond();
        double timestamp = parentStream.getCreatedTime() + position;
        parentStream.updatePacketTimestamp(timestamp);

        put(new VideoFrame(
                timestamp,
                position,
                time,
                pixelFormat,
                stream.codecpar().width(),
                stream.codecpar().height(),
                pixelData
        ));

        totalDecoded ++;

        //(double) frame.pts() * (double) Rational.fromAVRational(stream.time_base()).toDouble()
        //setPosition((double)totalDecoded / videoFormat.getFramesPerSecond());
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("already closed");
            }

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

            closed = true;
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoSourceSubstream.close() completed");
        }
    }
}
