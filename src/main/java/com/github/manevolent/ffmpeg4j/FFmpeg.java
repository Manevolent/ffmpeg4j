package com.github.manevolent.ffmpeg4j;

import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avutil;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

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
    public static void register() throws FFmpegException {
        avformat.av_register_all();
        avcodec.avcodec_register_all();
    }

    /**
     * Gets an FFmpeg codec instance by name.
     * @param name Name of the codec to search for.
     * @return static AVCodec reference.
     * @throws FFmpegException
     */
    public static avcodec.AVCodec getCodecByName(String name) throws FFmpegException {
        if (name == null) throw new NullPointerException();
        avcodec.AVCodec currentCodec = null;

        while ((currentCodec = avcodec.av_codec_next(currentCodec)) != null) {
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
    public static avformat.AVOutputFormat getOutputFormatByName(String name) throws FFmpegException {
        if (name == null) throw new NullPointerException();
        avformat.AVOutputFormat currentFormat = null;

        while ((currentFormat = avformat.av_oformat_next(currentFormat)) != null) {
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
    public static avformat.AVInputFormat getInputFormatByName(String name) throws FFmpegException {
        if (name == null) throw new NullPointerException();

        // Find the input format.
        avformat.AVInputFormat inputFormat = avformat.av_find_input_format(name);
        if (inputFormat == null) throw new FFmpegException("Unknown input format name: " + name);
        return inputFormat;
    }

    /**
     * Finds an output format by MIME type
     * @param mimeType Output format MIME type
     * @return static output format reference.
     * @throws FFmpegException
     */
    public static avformat.AVOutputFormat getOutputFormatByMime(String mimeType) throws FFmpegException {
        if (mimeType == null) throw new NullPointerException();

        avformat.AVOutputFormat currentFormat = null;

        while ((currentFormat = avformat.av_oformat_next(currentFormat)) != null) {
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
    public static avformat.AVInputFormat getInputFormatByMime(String mimeType) throws FFmpegException {
        if (mimeType == null) throw new NullPointerException();

        avformat.AVInputFormat currentFormat = null;

        while ((currentFormat = avformat.av_iformat_next(currentFormat)) != null) {
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
}
