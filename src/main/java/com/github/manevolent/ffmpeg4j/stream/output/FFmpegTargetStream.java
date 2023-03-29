package com.github.manevolent.ffmpeg4j.stream.output;

import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.math.Rational;
import com.github.manevolent.ffmpeg4j.output.FFmpegAudioTargetSubstream;
import com.github.manevolent.ffmpeg4j.output.FFmpegVideoTargetSubstream;
import com.github.manevolent.ffmpeg4j.output.MediaTargetSubstream;
import com.github.manevolent.ffmpeg4j.stream.FFmpegFormatContext;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.*;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class FFmpegTargetStream extends TargetStream implements FFmpegFormatContext {
    private final AVFormatContext formatContext;
    private final FFmpegIO io;
    private final List<MediaTargetSubstream> substreams = new ArrayList<>();
    private final FFmpegPacketOutput packetOutput;

    private final Object closeLock = new Object();

    private int pixelFormat = org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24;

    private boolean closed;

    public FFmpegTargetStream(String formatName, FFmpegIO io, FFmpegPacketOutput packetOutput) throws FFmpegException {
        this.io = io;

        AVOutputFormat outputFormat = avformat.av_guess_format(formatName, (String)null, (String)null);
        if (outputFormat == null) throw new IllegalArgumentException("unknown output format");

        this.formatContext = avformat.avformat_alloc_context();
        if (formatContext == null) throw new NullPointerException();

        FFmpegError.checkError(
                "avformat_alloc_output_context2",
                avformat.avformat_alloc_output_context2(formatContext, outputFormat, (String)null, (String)null)
        );

        setAVIOContext(io.getContext());

        this.packetOutput = packetOutput;
    }

    public FFmpegTargetStream(String formatName, String url, FFmpegPacketOutput packetOutput) throws FFmpegException {
        this.io = null;

        this.formatContext = avformat.avformat_alloc_context();
        if (formatContext == null) throw new NullPointerException();

        FFmpegError.checkError(
                "avformat_alloc_output_context2",
                avformat.avformat_alloc_output_context2(
                        formatContext, // is set to the created format context, or to NULL in case of failure
                        (AVOutputFormat) null, // 	format to use for allocating the context, if NULL format_name and filename are used instead
                        formatName, // the name of output format to use for allocating the context, if NULL filename is used instead
                        url // the name of the filename to use for allocating the context, may be NULL
                )
        );

        if (formatContext.isNull()) throw new NullPointerException();
        if (formatContext.oformat().isNull()) throw new NullPointerException();

        formatContext.pb(new AVIOContext());

        if (!isFlagSet(avformat.AVFMT_NOFILE)) {
            FFmpegError.checkError(
                    "avio_open",
                    avformat.avio_open(
                            new PointerPointer(formatContext),
                            new BytePointer(url),
                            avformat.AVIO_FLAG_WRITE
                    )
            );
        }

        this.packetOutput = packetOutput;
    }

    public int substreamCount() {
        return formatContext.nb_streams();
    }

    private AVIOContext getAVIOContext() {
        return this.formatContext.pb();
    }

    private void setAVIOContext(AVIOContext context) {
        enableCustomIO();
        this.formatContext.pb(context);
    }

    private void enableCustomIO() {
        setFlag(FFmpegFormatContext.AVFormatFlag.AVFMT_FLAG_CUSTOM_IO, true);
    }

    public void writeFFmpegHeader() throws FFmpegException {
        //avformat.av_dump_format(formatContext, 0, (String) null, 1);

        FFmpegError.checkError(
                "avformat_write_header",
                avformat.avformat_write_header(formatContext, (AVDictionary) null)
        );
    }

    @Override
    public void writeHeader() {
        try {
            writeFFmpegHeader();

            getOnReady().accept(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public FFmpegVideoTargetSubstream registerVideoSubstream(String codecName,
                                                             VideoFormat format,
                                                             Map<String, String> options) throws FFmpegException {
        return registerVideoSubstream(
                codecName,
                format.getWidth(), format.getHeight(), format.getFramesPerSecond(),
                options
        );
    }

    public FFmpegVideoTargetSubstream registerVideoSubstream(String codecName,
                                                             int width, int height, double fps,
                                                             Map<String, String> options) throws FFmpegException {
        AVCodec codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name(codecName);
        if (codec == null) throw new FFmpegException("unrecognized video codec: " + codecName);

        return registerVideoSubstream(codec, width, height, fps, options);
    }

    public FFmpegVideoTargetSubstream registerVideoSubstream(AVCodec codec,
                                                             int width, int height, double fps,
                                                             Map<String, String> options) throws FFmpegException {
        if (codec.type() != avutil.AVMEDIA_TYPE_VIDEO)
            throw new FFmpegException("codec is not video: " + codec.name().getString());

        codec = avcodec.avcodec_find_encoder(codec.id());
        if (codec == null) throw new FFmpegException("video codec does not have encoder");

        AVStream stream = avformat.avformat_new_stream(formatContext, codec);
        if (stream == null) throw new FFmpegException("could not create video substream");

        // Assign a stream ID to this encoder.
        stream.id(formatContext.nb_streams() - 1);

        AVCodecContext codecContext = avcodec.avcodec_alloc_context3(codec);

        // Set up appropriate pixel format target
        Collection<Integer> supported_formats = FFmpeg.readPointer(codec.pix_fmts());
        boolean is_pixel_format_supported = supported_formats.contains(getPixelFormat());
        int best_pix_fmt = is_pixel_format_supported ?
                getPixelFormat() :
                VideoFormat.getBestPixelFormat(codec, getPixelFormat());
        if (best_pix_fmt < 0) throw new FFmpegException("couldn't find comparable pixel format for encoder");
        codecContext.pix_fmt(best_pix_fmt);

        codecContext.width(width);
        codecContext.height(height);

        stream.codecpar().codec_id(codec.id());
        stream.codecpar().width(width);
        stream.codecpar().height(height);
        stream.codecpar().format(best_pix_fmt);

        Rational timeBase = Rational.toRational(1D/fps);
        codecContext.time_base(avutil.av_make_q((int) timeBase.getNumerator(), (int) timeBase.getDenominator()));
        Rational framerate = Rational.toRational(fps);
        codecContext.framerate(avutil.av_make_q((int) framerate.getNumerator(), (int) framerate.getDenominator()));

        // some formats want stream headers to be separate
        if ((formatContext.oformat().flags() & avformat.AVFMT_GLOBALHEADER) == avformat.AVFMT_GLOBALHEADER)
            codecContext.flags(codecContext.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);

        // pull in options
        AVDictionary optionDictionary = new AVDictionary();
        for (Map.Entry<String,String> option : options.entrySet()) {
            FFmpegError.checkError(
                    "av_dict_set/" + option.getKey(),
                    avutil.av_dict_set(optionDictionary, option.getKey(), option.getValue(), 0)
            );
        }

        FFmpegError.checkError(
                "avcodec_open2",
                avcodec.avcodec_open2(codecContext, codec, optionDictionary)
        );

        FFmpegVideoTargetSubstream videoTargetSubstream = new FFmpegVideoTargetSubstream(
                this,
                stream,
                codecContext,
                fps
        );

        substreams.add(videoTargetSubstream);

        return videoTargetSubstream;
    }

    public FFmpegAudioTargetSubstream registerAudioSubstream(String codecName,
                                                             AudioFormat audioFormat,
                                                             Map<String, String> options) throws FFmpegException {
        return registerAudioSubstream(
                codecName,
                audioFormat.getSampleRate(), audioFormat.getChannels(), audioFormat.getChannelLayout(),
                options
        );
    }


    public FFmpegAudioTargetSubstream registerAudioSubstream(String codecName,
                                                             int sample_rate, int channels, long channel_layout,
                                                             Map<String, String> options) throws FFmpegException {
        AVCodec codec = avcodec.avcodec_find_encoder_by_name(codecName);
        if (codec == null) throw new FFmpegException("unrecognized audio codec: " + codecName);

        return registerAudioSubstream(
                codec,
                sample_rate, channels, channel_layout,
                options
        );
    }

    public FFmpegAudioTargetSubstream registerAudioSubstream(AVCodec codec,
                                                             int sample_rate, int channels, long channel_layout,
                                                             Map<String, String> options) throws FFmpegException {
        if (codec.type() != avutil.AVMEDIA_TYPE_AUDIO)
            throw new FFmpegException("codec is not audio: " + codec.name().getString());

        codec = avcodec.avcodec_find_encoder(codec.id());
        if (codec == null) throw new FFmpegException("audio codec does not have encoder");

        AVStream stream = avformat.avformat_new_stream(formatContext, codec);
        if (stream == null) throw new RuntimeException("could not create audio substream");

        stream.id(formatContext.nb_streams() - 1);

        AVCodecContext codecContext = avcodec.avcodec_alloc_context3(codec);

        int sampleFormat = -1;
        for (int i = 0; ; i ++) {
            int newSampleFormatId = codec.sample_fmts().get(i);
            if (newSampleFormatId < 0) break;
            sampleFormat = newSampleFormatId;
        }
        if (sampleFormat < 0) throw new FFmpegException("could not pick audio sample format for codec");

        if (codecContext.codec().supported_samplerates() != null &&
                !codecContext.codec().supported_samplerates().isNull()) {
            boolean sampleRateSupported = false;
            for (int i = 0; !sampleRateSupported; i++) {
                int sampleRate = codecContext.codec().supported_samplerates().get(i);
                if (sampleRate == sample_rate)
                    sampleRateSupported = true;
                else if (sampleRate <= 0)
                    break;
            }
            if (!sampleRateSupported)
                throw new FFmpegException("codec does not support sample rate: " + sample_rate);
        }

        if (codecContext.codec().channel_layouts() != null && !codecContext.codec().channel_layouts().isNull()) {
            boolean channelLayoutSupported = false;
            for (int i = 0; !channelLayoutSupported; i++) {
                long channelLayout = codecContext.codec().channel_layouts().get(i);
                if (channelLayout == channel_layout)
                    channelLayoutSupported = true;
                else if (channelLayout <= 0)
                    break;
            }
            if (!channelLayoutSupported)
                throw new FFmpegException("codec does not support channel layout: " + channel_layout);
        }

        //codecContext.bit_rate(bit_rate);
        //codecContext.bit_rate_tolerance(0);
        //codecContext.qmin(10);
        //codecContext.qmax(51);
        //codecContext.global_quality(10);
        codecContext.sample_fmt(sampleFormat);
        codecContext.sample_rate(sample_rate);
        codecContext.channels(channels);
        codecContext.channel_layout(channel_layout);

        // some formats want stream headers to be separate
        if ((formatContext.oformat().flags() & avformat.AVFMT_GLOBALHEADER) == avformat.AVFMT_GLOBALHEADER)
            codecContext.flags(codecContext.flags() | avcodec.AV_CODEC_FLAG_GLOBAL_HEADER);


        // pull in options
        AVDictionary optionDictionary = new AVDictionary();
        for (Map.Entry<String,String> option : options.entrySet()) {
            FFmpegError.checkError(
                    "av_dict_set/" + option.getKey(),
                    avutil.av_dict_set(optionDictionary, option.getKey(), option.getValue(), 0)
            );
        }

        FFmpegError.checkError(
                "avcodec_open2",
                avcodec.avcodec_open2(codecContext, codec, optionDictionary)
        );

        FFmpegAudioTargetSubstream audioTargetSubstream = new FFmpegAudioTargetSubstream(
                this,
                stream,
                codecContext
        );

        substreams.add(audioTargetSubstream);

        return audioTargetSubstream;
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegTargetStream.close() called");

        synchronized (closeLock) {
            if (!closed) {
                try {
                    // Flush underlying streams
                    for (MediaTargetSubstream targetSubstream : substreams) {
                        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "flushing MediaTargetSubstream: " +
                                targetSubstream.toString() + "...");
                        targetSubstream.flush();
                    }

                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "packetOutput.flush(formatContext)...");
                    // Flush packet buffer (I/O done at this point)
                    packetOutput.flush(formatContext);

                    // Write trailer to file
                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_write_trailer...");
                    avformat.av_write_trailer(getFormatContext());

                    // Close output connection/file (may do nothing)
                    Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "packetOutput.close()...");
                    packetOutput.close();
                } finally { // Make sure native stuff is freed properly
                    // Close all substreams (free memory)
                    for (MediaTargetSubstream substream : substreams) {
                        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "closing MediaTargetSubstream:" + substream.toString() + "...");
                        substream.close();
                    }

                    // Close native I/O handles
                    if (io != null) { // managed IO (we close a lot of stuff on our own here)
                        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "io.close()...");
                        io.close();
                    } else { // native IO, let ffmpeg handle it
                        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "avformat_free_context(formatContext)...");
                        avformat.avformat_free_context(formatContext);
                    }

                    closed = true;
                }
            } else {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegTargetStream already closed!");
            }
        }

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegTargetStream.close() completed");
    }

    public AVFormatContext getFormatContext() {
        return formatContext;
    }

    @Override
    public List<MediaTargetSubstream> getSubstreams() {
        return substreams;
    }

    public void writePacket(AVPacket packet) throws FFmpegException, EOFException {
        if (packet == null || packet.isNull())
        {
            return; // Null packet -- ignore!
        }
        else if (packet.size() < 0)
            throw new FFmpegException("failed to write packet: size < 0: " + packet.size());
        else if ((packet.flags() & avcodec.AV_PKT_FLAG_CORRUPT) == avcodec.AV_PKT_FLAG_CORRUPT)
            throw new FFmpegException("failed to write packet: corrupt flag is set");

        if (!packetOutput.writePacket(formatContext, packet))
            return; // Packet wasn't written.

        // Calculate duration and stream position, etc
        getFormatContext().duration((int) Math.max(getFormatContext().duration(), packet.pts() + packet.duration()));
    }

    public void flush() throws FFmpegException {
        packetOutput.flush(formatContext);
    }

    public int getPixelFormat() {
        return pixelFormat;
    }

    public void setPixelFormat(int pixelFormat) {
        this.pixelFormat = pixelFormat;
    }

    public interface FFmpegPacketOutput extends AutoCloseable {
        boolean writePacket(AVFormatContext formatContext, AVPacket packet) throws FFmpegException, EOFException;

        default void flush(AVFormatContext formatContext) throws FFmpegException {
            // Do nothing
        }

        default void close() throws Exception {

        }
    }

    public static class FFmpegNativeOutput implements FFmpegPacketOutput {
        @Override
        public boolean writePacket(AVFormatContext formatContext, AVPacket packet)
                throws FFmpegException, EOFException {
            if (packet.size() == 0) return false; // Skip packet.

            if (packet.dts() < 0) packet.dts(0);

            int ret = avformat.av_interleaved_write_frame(formatContext, packet);
            if (ret == -31) // Broken pipe
                throw new EOFException();

            FFmpegError.checkError("av_interleaved_write_frame(formatContext, packet)", ret);

            return ret >= 0;
        }

        @Override
        public void flush(AVFormatContext formatContext) throws FFmpegException {
            FFmpegError.checkError(
                    "av_interleaved_write_frame(formatContext, null)",
                    avformat.av_interleaved_write_frame(formatContext, null)
            );
        }
    }
}
