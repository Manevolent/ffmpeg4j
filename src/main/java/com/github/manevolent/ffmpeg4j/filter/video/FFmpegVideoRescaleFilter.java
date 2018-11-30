package com.github.manevolent.ffmpeg4j.filter.video;

import com.github.manevolent.ffmpeg4j.*;
import org.bytedeco.javacpp.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FFmpegVideoRescaleFilter extends VideoFilter {
    private final VideoFormat inputFormat, outputFormat;

    // FFmpeg native stuff (for video conversion)
    private final BytePointer inputBuffer;
    private final BytePointer outputBuffer;
    private final avcodec.AVPicture inputPicture;
    private final avutil.AVFrame outputFrame;
    private final swscale.SwsContext sws;

    private final double outputFrameDuration;
    private final double inputFrameDuration;

    private final int pixelFormat;

    private VideoFrame lastFrame;
    private long count = 0L;

    public FFmpegVideoRescaleFilter(VideoFormat input, VideoFormat output, int pixelFormat) throws FFmpegException {
         /*
            http://stackoverflow.com/questions/29743648/which-flag-to-use-for-better-quality-with-sws-scale

            The RGB24 to YUV420 conversation itself is lossy. The scaling algorithm is probably used in downscaling
            the color information. I'd say the quality is: point << bilinear < bicubic < lanczos/sinc/spline I don't
            really know the others. Under rare circumstances sinc is the ideal scaler and lossless, but those
            conditions are usually not met. Are you also scaling the video? Otherwise I'd go for bicubic.
         */

        this(input, output, pixelFormat, swscale.SWS_BILINEAR);
    }

    public FFmpegVideoRescaleFilter(VideoFormat input, VideoFormat output, int pixelFormat, int scaleFilter)
            throws FFmpegException {
        this.pixelFormat = pixelFormat;

        this.inputFormat = input;
        this.outputFormat = output;

        this.inputFrameDuration = 1D / input.getFramesPerSecond();
        this.outputFrameDuration = 1D / output.getFramesPerSecond();

        if (input.getHeight() != output.getHeight() || input.getWidth() != output.getWidth()) {
            outputFrame = avutil.av_frame_alloc();
            if (outputFrame == null) throw new RuntimeException("failed to allocate output frame");

            int numBytesInput = avcodec.avpicture_get_size(
                    pixelFormat,
                    input.getWidth(),
                    input.getHeight()
            );

            int numBytesOutput = avcodec.avpicture_get_size(
                    pixelFormat,
                    output.getWidth(),
                    output.getHeight()
            );

            inputBuffer = new BytePointer(avutil.av_malloc(numBytesInput));
            outputBuffer = new BytePointer(avutil.av_malloc(numBytesOutput));

            sws = swscale.sws_getContext(
                    input.getWidth(), input.getHeight(), pixelFormat, // source
                    output.getWidth(), output.getHeight(), pixelFormat, // destination
                    scaleFilter, // flags (see above)
                    null, null, (DoublePointer) null // filters, params
            );

            // Assign appropriate parts of buffer to image planes in pFrameRGB
            // Note that pFrameRGB is an AVFrame, but AVFrame is a superset
            // of AVPicture
            this.inputPicture = new avcodec.AVPicture();

            int ret = avcodec.avpicture_fill(
                    inputPicture,
                    inputBuffer,
                    pixelFormat,
                    input.getWidth(),
                    input.getHeight()
            );

            FFmpegError.checkError("avpicture_fill", ret);

            ret = avcodec.avpicture_fill(
                    new avcodec.AVPicture(outputFrame),
                    outputBuffer,
                    pixelFormat,
                    output.getWidth(),
                    output.getHeight()
            );

            FFmpegError.checkError("avpicture_fill", ret);
        } else {
            inputBuffer = null;
            outputBuffer = null;
            inputPicture = null;
            outputFrame = null;
            sws = null;
        }
    }

    @Override
    public Collection<VideoFrame> apply(VideoFrame source) {
        List<VideoFrame> videoFrames = new ArrayList<>();
        if (source == null || source.getTime() <= 0D) return videoFrames;

        // Decide to drop or keep the frame
        double outputPositionInSeconds = (double)count * outputFrameDuration;

        double newInputPositionInSeconds = source.getPosition() + inputFrameDuration;
        double newOutputPositionInSeconds = outputPositionInSeconds + outputFrameDuration;

        // FPS conversions
        if (inputFrameDuration != outputFrameDuration) {
            if (newOutputPositionInSeconds > newInputPositionInSeconds) // 60FPS -> 30FPS
                return videoFrames; // drop the frame
            else if (lastFrame != null) { // 30FPS -> 60FPS
                while (newOutputPositionInSeconds + outputFrameDuration < newInputPositionInSeconds) {
                    videoFrames.add(lastFrame);
                    newOutputPositionInSeconds += outputFrameDuration;
                }
            }
        }

        VideoFrame frame;

        if (inputFormat.getHeight() != outputFormat.getHeight() || inputFormat.getWidth() != outputFormat.getWidth()) {
            inputPicture.data(0).put(source.getData());

            int ret = swscale.sws_scale(
                    sws, // the scaling context previously created with sws_getContext()
                    inputPicture.data(), // 	the array containing the pointers to the planes of the source slice
                    inputPicture.linesize(), // the array containing the strides for each plane of the source image
                    0, // the position in the source image of the slice to process, that is the number (counted starting from zero) in the image of the first row of the slice
                    source.getHeight(), // the height of the source slice, that is the number of rows in the slice
                    outputFrame.data(), // the array containing the pointers to the planes of the destination image
                    outputFrame.linesize() // the array containing the strides for each plane of the destination image
            );

            try {
                FFmpegError.checkError("sws_scale", ret);
            } catch (FFmpegException e) {
                throw new RuntimeException(e);
            }

            BytePointer data = outputFrame.data(0);
            int l = outputFormat.getWidth();

            // Allocate pixel data buffer:
            byte[] pixelData = new byte[l * ret * 3];
            data.position(0).get(pixelData, 0, l * ret * 3);

            frame = new VideoFrame(
                    source.getTimestamp() - source.getPosition() + newOutputPositionInSeconds, // adjust for realworld ts
                    newOutputPositionInSeconds,
                    outputFrameDuration,
                    pixelFormat,
                    l,
                    ret,
                    pixelData
            );
        } else {
            frame = source;
        }

        lastFrame = frame;
        videoFrames.add(frame);
        count += videoFrames.size();

        return videoFrames;
    }

    public VideoFormat getInputFormat() {
        return inputFormat;
    }

    public VideoFormat getOutputFormat() {
        return outputFormat;
    }

    @Override
    public void close() {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoRescaleFilter.close() called");

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "sws_freeContext(sws)...");
        swscale.sws_freeContext(sws);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(inputPicture)...");
        avutil.av_free(inputPicture);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_frame_free(outputFrame)...");
        avutil.av_frame_free(outputFrame);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegVideoRescaleFilter.close() completed");
    }
}
