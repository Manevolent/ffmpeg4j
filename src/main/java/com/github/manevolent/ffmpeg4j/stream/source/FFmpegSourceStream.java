package com.github.manevolent.ffmpeg4j.stream.source;

import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.source.FFmpegAudioSourceSubstream;
import com.github.manevolent.ffmpeg4j.source.FFmpegDecoderContext;
import com.github.manevolent.ffmpeg4j.source.FFmpegVideoSourceSubstream;
import com.github.manevolent.ffmpeg4j.source.MediaSourceSubstream;
import com.github.manevolent.ffmpeg4j.stream.FFmpegFormatContext;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.annotation.Cast;

import java.io.EOFException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class FFmpegSourceStream extends SourceStream implements FFmpegFormatContext {
    private final FFmpegInput input;
    private final List<MediaSourceSubstream> substreamList = new LinkedList<>();
    private final FFmpegDecoderContext[] substreams;
    private final Object readLock = new Object();

    private boolean registered = false;
    private volatile boolean closed = false;
    private final Object closeLock = new Object();

    private double position = -1D;

    private int pixelFormat = avutil.AV_PIX_FMT_RGB24;

    private final AVCodecContext.Get_format_AVCodecContext_IntPointer get_format_callback =
            new AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                @Override
                public int call(AVCodecContext var1, @Cast({"const AVPixelFormat*"}) IntPointer pix_fmt_list) {
                    Logging.LOGGER.log(
                            Logging.DEBUG_LOG_LEVEL,
                            "finding best pix_fmt match for decoder for " +
                                avutil.av_get_pix_fmt_name(getPixelFormat()).getString()
                    );

                    int pix_fmt = avcodec.avcodec_find_best_pix_fmt_of_list(pix_fmt_list, getPixelFormat(), 0, null);

                    if (pix_fmt >= 0)
                        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "offering pix_fmt " +
                            avutil.av_get_pix_fmt_name(pix_fmt).getString() + " to decoder");
                    else
                        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "couldn't find pix_fmt for decoder");

                    return pix_fmt;
                }
            };

    public FFmpegSourceStream(FFmpegInput input) {
        this.substreams = new FFmpegDecoderContext[input.getContext().nb_streams()];
        this.input = input;
    }

    public FFmpegSourceStream(FFmpegInput input, long startTimeMicrosends) {
        this(input);

        input.getContext().start_time_realtime(startTimeMicrosends);
    }

    public final AVCodecContext.Get_format_AVCodecContext_IntPointer getGet_format_callback() {
        return get_format_callback;
    }

    public Collection<MediaSourceSubstream> registerStreams() throws FFmpegException {
        synchronized (readLock) {
            if (!registered) {
                // Register low-level streams.
                int stream_count = input.getFormatContext().nb_streams();
                for (int stream_index = 0; stream_index < stream_count; stream_index++)
                    input.registerStream(this, stream_index);

                registered = true;
            }

            return Collections.unmodifiableCollection(substreamList);
        }
    }

    @Override
    public List<MediaSourceSubstream> getSubstreams() {
        return substreamList;
    }

    @Override
    public double getCreatedTime() {
        long start_time_realtime = input.getContext().start_time_realtime();
        if (start_time_realtime == avutil.AV_NOPTS_VALUE) return 0D;
        return (double)start_time_realtime / 1000000D;
    }

    public double getPosition() {
        return position;
    }

    @Override
    public double seek(double position) throws IOException {
        if (getPosition() > position) {
            throw new IllegalStateException("Cannot rewind");
        } else if (getPosition() == position) {
            return position;
        }

        Packet packet;
        while ((packet = readPacket()) != null) {
            if (packet.getPosition() + packet.getDuration() >= position) {
                return packet.getPosition();
            }
        }

        throw new EOFException();
    }

    @Override
    public void setCreatedTime(double createdTimeInSeconds) {
        input.getContext().start_time_realtime((long) (createdTimeInSeconds * 1000000D));
    }

    public int getPixelFormat() {
        return pixelFormat;
    }

    public void setPixelFormat(int pixelFormat) {
        if (registered) throw new IllegalStateException("already registered substreams");

        this.pixelFormat = pixelFormat;
    }

    @Override
    public Packet readPacket() throws IOException {
        try {
            while (true) {
                int result;
                AVPacket packet = avcodec.av_packet_alloc();

                // av_read_frame may not be thread safe
                synchronized (readLock) {
                    if (!registered) registerStreams();

                    for (; ; ) {
                        result = avformat.av_read_frame(input.getContext(), packet);
                        if (result != avutil.AVERROR_EAGAIN()) {
                            break;
                        }
                    }
                }

                try {
                    // Manual EOF checking here because an EOF is very important to the upper layers.
                    if (result == avutil.AVERROR_EOF) throw new EOFException("pos: " + getPosition() + "s");
                    else if (result == avutil.AVERROR_ENOMEM()) throw new OutOfMemoryError();

                    FFmpegError.checkError("av_read_frame", result);

                    // NOT USED: In case createdTime doesn't get set.
                    if ((packet.flags() & avcodec.AV_PKT_FLAG_KEY) == avcodec.AV_PKT_FLAG_KEY &&
                            getCreatedTime() <= 0D)
                        setCreatedTime(System.currentTimeMillis() / 1000D);

                    if ((packet.flags() & avcodec.AV_PKT_FLAG_CORRUPT) == avcodec.AV_PKT_FLAG_CORRUPT)
                        throw new IOException("read corrupt packet");

                    // Find the substream and its native context associated with this packet:
                    FFmpegDecoderContext substream = getSubstream(packet.stream_index());

                    // Handle any null contexts:
                    if (substream == null)
                        continue;

                    int size = packet.size();
                    if (size <= 0)
                        continue;

                    int finished;
                    if (substream.isDecoding()) {
                        finished = substream.decodePacket(packet);
                    } else {
                        finished = 0;
                    }

                    AVRational timebase = getFormatContext().streams(packet.stream_index()).time_base();
                    double position = FFmpeg.timestampToSeconds(timebase, packet.pts());
                    this.position = position;
                    double duration = FFmpeg.timestampToSeconds(timebase, packet.duration());
                    return new Packet((MediaSourceSubstream) substream, size, finished, position, duration);
                } finally {
                    // VLC media player does this
                    avcodec.av_packet_unref(packet);
                }
            }
        } catch (FFmpegException ex) {
            throw new IOException(ex);
        }
    }

    private static AVCodecContext newCodecContext(AVCodec codec, AVCodecParameters parameters) throws FFmpegException {
        AVCodecContext context = avcodec.avcodec_alloc_context3(codec);
        if (context == null) {
            throw new FFmpegException("Failed to allocate AVCodecContext");
        }
        avcodec.avcodec_parameters_to_context(context, parameters);
        avcodec.avcodec_open2(context, codec, (AVDictionary) null);
        return context;
    }

    public void registerSubstream(int stream_index,
                                  AVStream stream) throws FFmpegException {
        if (stream_index < 0 || stream_index >= substreams.length)
            throw new FFmpegException("substream ID invalid: " + stream_index);

        FFmpegDecoderContext decoderContext = substreams[stream_index];
        if (decoderContext != null)
            throw new FFmpegException("substream already registered: " + stream_index);

        AVCodec codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id());

        switch (stream.codecpar().codec_type()) {
            case avutil.AVMEDIA_TYPE_VIDEO:
                FFmpegVideoSourceSubstream videoSourceStream = new FFmpegVideoSourceSubstream(
                        this,
                        stream,
                        newCodecContext(codec, stream.codecpar()),
                        getPixelFormat()
                );

                substreamList.add(videoSourceStream);
                decoderContext = videoSourceStream;
                break;
            case avutil.AVMEDIA_TYPE_AUDIO:
                FFmpegAudioSourceSubstream audioSourceStream = new FFmpegAudioSourceSubstream(
                        this,
                        stream,
                        newCodecContext(codec, stream.codecpar())
                );

                substreamList.add(audioSourceStream);
                decoderContext = audioSourceStream;
                break;
        }

        if (decoderContext == null)
            throw new FFmpegException("unsupported codec type: " + stream.codecpar().codec_type());

        substreams[stream_index] = decoderContext;
    }

    public FFmpegDecoderContext getSubstream(int stream_index) {
        return substreams[stream_index];
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegSourceStream.close() called");

        synchronized (closeLock) {
            if (!closed) {
                for (MediaSourceSubstream substream : substreamList) {
                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "closing MediaSourceSubstream: " + substream.toString() + "...");
                    substream.close();
                }

                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "substreamList.clear()...");
                substreamList.clear();

                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "input.close()...");
                input.close();

                closed = true;

                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "getOnClosed().accept(this)...");
                getOnClosed().accept(this);
            }
        }

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegSourceStream.close() completed");
    }

    @Override
    public AVFormatContext getFormatContext() {
        return input.getContext();
    }
}
