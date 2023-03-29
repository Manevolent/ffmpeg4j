package com.github.manevolent.ffmpeg4j;

import com.github.manevolent.ffmpeg4j.stream.FFmpegFormatContext;
import com.github.manevolent.ffmpeg4j.stream.output.FFmpegTargetStream;
import com.github.manevolent.ffmpeg4j.stream.source.FFmpegSourceStream;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.logging.Level;

/**
 * Represents the native input functionality for FFmpeg, at the container level (mp4, flv, etc).
 */
public class FFmpegOutput implements AutoCloseable, FFmpegFormatContext {
    private static final Object openLock = new Object();
    private final AVFormatContext formatContext;
    private final FFmpegIO io;
    private volatile boolean opened = false;

    private final Object closeLock = new Object();
    private boolean closed = false;

    public FFmpegOutput(FFmpegIO io) {
        this.formatContext = avformat.avformat_alloc_context();
        if (this.formatContext == null) throw new NullPointerException();

        this.io = io;
        setAVIOContext(io.getContext());
    }

    public int substreamCount() {
        return formatContext.nb_streams();
    }

    public AVFormatContext getContext() {
        return formatContext;
    }

    private void setAVIOContext(AVIOContext context) {
        enableCustomIO();
        this.formatContext.pb(context);
    }

    private void enableCustomIO() {
        setFlag(AVFormatFlag.AVFMT_FLAG_CUSTOM_IO, true);
    }

    /**
     * Opens the output for the format.
     * @param formatName Container or raw format name ("flv", "mp4", etc.)
     * @throws RuntimeException
     */
    public FFmpegTargetStream open(String formatName) throws FFmpegException {
        return open(FFmpeg.getOutputFormatByName(formatName));
    }

    /**
     * Opens the output for the format.
     * @param outputFormat Input format context
     * @throws RuntimeException
     */
    public FFmpegTargetStream open(AVOutputFormat outputFormat) throws FFmpegException {
        // Open the input format.
        FFmpegError.checkError(
                "avformat_alloc_output_context2",
                avformat.avformat_alloc_output_context2(
                        formatContext, // is set to the created format context, or to NULL in case of failure
                        (AVOutputFormat) null, // 	format to use for allocating the context, if NULL format_name and filename are used instead
                        outputFormat.name(), // the name of output format to use for allocating the context, if NULL filename is used instead
                        null // the name of the filename to use for allocating the context, may be NULL
                )
        );

        opened = true;

        return new FFmpegTargetStream(formatContext, io, new FFmpegTargetStream.FFmpegNativeOutput());
    }

    /**
     * true if the context has opened (see open(String)).
     */
    public boolean isOpened() {
        return opened;
    }

    /**
     * Releases native resources held by this format context.
     * @throws Exception
     */
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegInput.close() called");

        synchronized (closeLock) {
            if (!closed) {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "io.close()...");
                io.close();
                closed = true;
            } else {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegInput already closed!");
            }
        }

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegInput.close() completed");
    }

    @Override
    public AVFormatContext getFormatContext() {
        return formatContext;
    }

}