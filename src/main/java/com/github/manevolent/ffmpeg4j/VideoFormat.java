package com.github.manevolent.ffmpeg4j;

import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.IntPointer;

import java.nio.*;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class VideoFormat {
    private final int width, height;
    private final double framesPerSecond;

    public VideoFormat(int width, int height, double framesPerSecond) {
        this.width = width;
        this.height = height;
        this.framesPerSecond = framesPerSecond;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Fractional representation of frames per second (seconds per frame basically)
     * @return 1 / FPS
     */
    public double getFramesPerSecond() {
        return framesPerSecond;
    }

    public double getAspectRatio() {
        return (double) getWidth() / (double) getHeight();
    }

    @Override
    public String toString() {
        return Integer.toString(width) + "x" + Integer.toString(height) + " @" + (Double.toString(framesPerSecond)) + "FPS";
    }

    public static int getBestPixelFormat(String codecName, int pixelFormat) {
        AVCodec codec = avcodec.avcodec_find_encoder_by_name(codecName);
        if (codec == null) throw new RuntimeException(codecName);
        return getBestPixelFormat(codec, pixelFormat);
    }

    public static int getBestPixelFormat(AVCodec codec, int pixelFormat) {
        Collection<Integer> formats = FFmpeg.readPointer(codec.pix_fmts());

        /*
         [in] 	dst_pix_fmt1 	One of the two destination pixel formats to choose from
         [in] 	dst_pix_fmt2 	The other of the two destination pixel formats to choose from
         [in] 	src_pix_fmt 	Source pixel format
         [in] 	has_alpha 	Whether the source pixel format alpha channel is used.
         [in,out] 	loss_ptr 	Combination of loss flags. In: selects which of the losses to ignore, i.e.
            NULL or value of zero means we care about all losses.
            Out: the loss that occurs when converting from src to selected dst pixel format.
         */

        int best_format = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_best_pix_fmt_of_list(
                        codec.pix_fmts(),
                        pixelFormat,
                        0,
                        (IntPointer) null
        );

        return best_format;
    }

    public static int getBestPixelFormat(AVCodec a, AVCodec b, int pixelFormat) {
        Collection<Integer> a_formats = FFmpeg.readPointer(a.pix_fmts());
        Collection<Integer> b_formats = FFmpeg.readPointer(b.pix_fmts());

        IntBuffer buffer = IntBuffer.allocate(Math.min(a_formats.size(), b_formats.size()));
        a_formats.stream().filter(x -> b_formats.stream().anyMatch(y -> y.intValue() == x.intValue())).forEach(buffer::put);

        /*
         [in] 	dst_pix_fmt1 	One of the two destination pixel formats to choose from
         [in] 	dst_pix_fmt2 	The other of the two destination pixel formats to choose from
         [in] 	src_pix_fmt 	Source pixel format
         [in] 	has_alpha 	Whether the source pixel format alpha channel is used.
         [in,out] 	loss_ptr 	Combination of loss flags. In: selects which of the losses to ignore, i.e.
            NULL or value of zero means we care about all losses.
            Out: the loss that occurs when converting from src to selected dst pixel format.
         */

        int best_format = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_best_pix_fmt_of_list(
                        buffer,
                        pixelFormat,
                        0,
                        null
        );

        return best_format;
    }

    public static int getBestPixelFormat(String encoderName, String decoderName, int pixelFormat) {
        AVCodec encoder = avcodec.avcodec_find_encoder_by_name(encoderName);
        if (encoder == null) throw new RuntimeException("unrecognized video encoder: " + encoderName);

        AVCodec decoder = avcodec.avcodec_find_decoder_by_name(decoderName);
        if (decoder == null) throw new RuntimeException("unrecognized video decoder: " + decoderName);

        return getBestPixelFormat(encoder, decoder, pixelFormat);
    }
}
