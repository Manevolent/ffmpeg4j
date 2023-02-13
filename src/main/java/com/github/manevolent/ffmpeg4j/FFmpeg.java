package com.github.manevolent.ffmpeg4j;

import com.github.manevolent.ffmpeg4j.math.*;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.*;

import java.util.*;
import java.util.function.*;

import static org.bytedeco.ffmpeg.global.avutil.av_q2d;

public final class FFmpeg {

    public static Collection<Integer> readPointer(IntPointer pointer) {
        List<Integer> list = new LinkedList<>();

        for (long i = 0;; i ++) {
            if (pointer.get(i) < 0)
                break;
            list.add(pointer.get(i));
        }

        return list;
    }

    /**
     * Registers FFmpeg codecs and formats
     * @throws FFmpegException
     */
    @Deprecated
    public static void register() throws FFmpegException {
        // Deprecated, this does nothing the version of FFmpeg we use now
    }

    /**
     * See: https://ffmpeg.org/pipermail/libav-user/2018-May/011160.html
     * @return
     */
    private static <T extends Pointer> Collection<T> iterate(Function<Pointer, T> iterateFunction) {
        Collection<T> outs = new ArrayList<>();
        try (Pointer opaque = new Pointer()) {
            T out;
            while ((out = iterateFunction.apply(opaque)) != null) {
                outs.add(out);
            }
        }
        return Collections.unmodifiableCollection(outs);
    }

    private static Collection<AVOutputFormat> iterateMuxers() {
        return iterate(avformat::av_muxer_iterate);
    }

    private static Collection<AVInputFormat> iterateDemuxers() {
        return iterate(avformat::av_demuxer_iterate);
    }

    private static Collection<AVCodec> iterateCodecs() {
        return iterate(avcodec::av_codec_iterate);
    }

    /**
     * Gets an FFmpeg codec instance by name.
     * @param name Name of the codec to search for.
     * @return static AVCodec reference.
     * @throws FFmpegException
     */
    public static AVCodec getCodecByName(String name) throws FFmpegException {
        if (name == null) throw new NullPointerException();

        for (AVCodec currentCodec : iterateCodecs()) {
            if (currentCodec.name() == null) continue;
            if (currentCodec.name().getString().equalsIgnoreCase(name))
                return currentCodec;
        }

        throw new FFmpegException("Unknown codec name: " + name);
    }

    /**
     * Finds an output format by name
     * @param name Output format name
     * @return static output format reference.
     * @throws FFmpegException
     */
    public static AVOutputFormat getOutputFormatByName(String name) throws FFmpegException {
        if (name == null) throw new NullPointerException();

        for (AVOutputFormat currentFormat : iterateMuxers()) {
            if (currentFormat.mime_type() == null) continue;
            if (currentFormat.name().getString().equalsIgnoreCase(name))
                return currentFormat;
        }

        throw new FFmpegException("Unknown output format name: " + name);
    }

    /**
     * Finds an input format by name
     * @param name Input format name
     * @return static output format reference.
     * @throws FFmpegException
     */
    public static AVInputFormat getInputFormatByName(String name) throws FFmpegException {
        if (name == null) throw new NullPointerException();

        // Find the input format.
        AVInputFormat inputFormat = avformat.av_find_input_format(name);
        if (inputFormat == null) throw new FFmpegException("Unknown input format name: " + name);
        return inputFormat;
    }

    /**
     * Finds an output format by MIME type
     * @param mimeType Output format MIME type
     * @return static output format reference.
     * @throws FFmpegException
     */
    public static AVOutputFormat getOutputFormatByMime(String mimeType) throws FFmpegException {
        if (mimeType == null) throw new NullPointerException();

        for (AVOutputFormat currentFormat : iterateMuxers()) {
            if (currentFormat.mime_type() == null) continue;
            String currentMimeType = currentFormat.mime_type().getString();
            if (currentMimeType != null && currentMimeType.equalsIgnoreCase(mimeType))
                return currentFormat;
        }

        throw new FFmpegException("Unknown output format MIME type: " + mimeType);
    }


    /**
     * Finds an input format by MIME type
     * @param mimeType Input format MIME type
     * @return static input format reference.
     * @throws FFmpegException
     */
    public static AVInputFormat getInputFormatByMime(String mimeType) throws FFmpegException {
        if (mimeType == null) throw new NullPointerException();

        for (AVInputFormat currentFormat : iterateDemuxers()) {
            if (currentFormat.mime_type() == null) continue;
            String currentMimeType = currentFormat.mime_type().getString();
            if (currentMimeType != null && currentMimeType.equalsIgnoreCase(mimeType))
                return currentFormat;
        }

        throw new FFmpegException("Unknown input format MIME type: " + mimeType);
    }

    @Deprecated
    public static int guessFFMpegChannelLayout(int channels) throws FFmpegException {
        switch (channels) {
            case 1:
                return (int) avutil.AV_CH_LAYOUT_MONO;
            case 2:
                return (int) avutil.AV_CH_LAYOUT_STEREO;
            case 4:
                return (int) avutil.AV_CH_LAYOUT_3POINT1;
            case 5:
                return (int) avutil.AV_CH_LAYOUT_5POINT0;
            case 6:
                return (int) avutil.AV_CH_LAYOUT_5POINT1;
            case 7:
                return (int) avutil.AV_CH_LAYOUT_7POINT0;
            case 8:
                return (int) avutil.AV_CH_LAYOUT_7POINT1;
            default:
                throw new FFmpegException("Unsupported channel count: " + channels);
        }
    }

    /**
     * Finds a specific pixel format by name
     * @param pixelFormat Pixel format name
     * @return static pixel format instance
     * @throws FFmpegException
     */
    public static int getPixelFormatByName(String pixelFormat) throws FFmpegException {
        int pix_fmt = avutil.av_get_pix_fmt(pixelFormat);

        if (pix_fmt < 0)
            throw new FFmpegException("Unknown pixel format: " + pixelFormat);

        return pix_fmt;
    }

    public static double timestampToSeconds(AVRational timebase, long timestamp) {
        return (double) timestamp * Rational.fromAVRational(timebase).toDouble();
    }
}
