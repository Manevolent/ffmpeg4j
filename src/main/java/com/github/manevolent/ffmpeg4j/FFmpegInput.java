package com.github.manevolent.ffmpeg4j;

import com.github.manevolent.ffmpeg4j.stream.FFmpegFormatContext;
import com.github.manevolent.ffmpeg4j.stream.source.FFmpegSourceStream;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;

import java.io.InputStream;
import java.util.logging.Level;

/**
 * Represents the native input functionality for FFmpeg, at the container level (mp4, flv, etc).
 */
public class FFmpegInput implements AutoCloseable, FFmpegFormatContext {
    private static final Object openLock = new Object();
    private final AVFormatContext formatContext;
    private final FFmpegIO io;
    private volatile boolean opened = false;

    private final Object closeLock = new Object();
    private boolean closed = false;

    public FFmpegInput(FFmpegIO io) {
        this.formatContext = avformat.avformat_alloc_context();
        if (this.formatContext == null) throw new NullPointerException();

        this.io = io;
        setAVIOContext(io.getContext());
    }

    public FFmpegInput(InputStream inputStream) throws FFmpegException {
        this(FFmpegIO.openInputStream(inputStream, FFmpegIO.DEFAULT_BUFFER_SIZE));
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
        setFlag(FFmpegFormatContext.AVFormatFlag.AVFMT_FLAG_CUSTOM_IO, true);
    }

    /**
     * Opens the input for the format.
     * @param format Container or raw format name ("flv", "mp4", etc.)
     * @throws RuntimeException
     */
    public FFmpegSourceStream open(String format) throws FFmpegException {
        return open(FFmpeg.getInputFormatByName(format));
    }

    /**
     * Opens the input for the format.
     * @param inputFormat Input format context
     * @throws RuntimeException
     */
    public FFmpegSourceStream open(AVInputFormat inputFormat) throws FFmpegException {
        // Open the input format.
        FFmpegError.checkError("avformat_open_input",
                avformat.avformat_open_input(
                        formatContext,
                        (String)null,
                        inputFormat,
                        null
                )
        );

        opened = true;

        // Important: find & initialize stream information.
        // Contrary to belief, it doesn't seek. It just buffers up packets.
        FFmpegError.checkError(
                "avformat_find_stream_info",
                avformat.avformat_find_stream_info(formatContext, (AVDictionary) null)
        );

        //avformat.av_dump_format(formatContext, 0, "", 0);

        return new FFmpegSourceStream(this);
    }

    /**
     * Registers a stream.
     * @param stream_index stream index to register (0-indexed)
     * @return true if the stream was registered, false otherwise.
     */
    public boolean registerStream(FFmpegSourceStream sourceStream, int stream_index)
            throws FFmpegException {
        if (stream_index < 0)
            return false;

        // Find the stream in the format.
        AVStream stream = formatContext.streams(stream_index);

        // Find the codec ID of the stream.
        int codecId = stream.codecpar().codec_id();
        FFmpegError.checkError("codec_id", codecId);

        // Find the decoder based on the codec ID of the stream.
        AVCodec codec = avcodec.avcodec_find_decoder(codecId);
        if (codec == null) {
            Logging.LOGGER.log(Level.FINE,
                    "registerStream/avcodec_find_decoder: no decoder for codec id=" + codecId + " for stream index="
                            + stream_index + "."
            );

            return false;
        }

        // Open the codec. This was very tricky in the conceptual development phase, because I did not realize that
        // the 'ctx' object needs to have parameters (at least for HVC/264 video). It seems that these parameters are
        // available by retrieving the pointer for the AVStream's AVCodecContext (see ctx = stream.codec(); above).
        synchronized (openLock) { // avcodec_open2 is not thread-safe apparently.
            AVCodecContext codecContext = avcodec.avcodec_alloc_context3(codec);

            // https://stackoverflow.com/questions/9652760/how-to-set-decode-pixel-format-in-libavcodec
            //  P.S. the place to stick in the overriding callback would be before the
            //  avcodec_open. Mind you, it's been a while since I looked at this stuff.
            codecContext.get_format(sourceStream.getGet_format_callback());

            FFmpegError.checkError("avcodec_open2", avcodec.avcodec_open2(codecContext, codec, (AVDictionary) null));
        }

        // Assign the stream to the substream.
        sourceStream.registerSubstream(stream_index, stream);

        return true;
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