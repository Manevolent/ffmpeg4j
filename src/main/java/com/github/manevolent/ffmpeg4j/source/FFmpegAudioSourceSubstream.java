package com.github.manevolent.ffmpeg4j.source;

import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.math.Rational;
import com.github.manevolent.ffmpeg4j.stream.source.FFmpegSourceStream;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.ffmpeg.swresample.*;
import org.bytedeco.javacpp.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class FFmpegAudioSourceSubstream
    extends AudioSourceSubstream
    implements FFmpegDecoderContext {
    private static final int OUTPUT_FORMAT = avutil.AV_SAMPLE_FMT_FLT;

    private final FFmpegSourceStream parentStream;
    private final AudioFormat audioFormat;

    // FFmpeg native stuff
    private final AVCodecContext codecContext;
    private final SwrContext swrContext;
    private final AVStream stream;

    private final int outputSampleRate;
    private final int outputChannels;
    private final int outputBytesPerSample;
    private final int audio_input_frame_size;

    private final BytePointer[] samples_out;
    private final PointerPointer samples_out_ptr;

    private boolean closed = false;

    private volatile byte[] recvBuffer;
    private volatile long totalDecoded = 0L;

    public FFmpegAudioSourceSubstream(FFmpegSourceStream parentStream, AVStream stream, AVCodecContext codecContext)
            throws FFmpegException {
        super(parentStream);

        this.stream = stream;
        this.parentStream = parentStream;
        this.codecContext = codecContext;

        int channels = stream.codecpar().channels();

        if (channels <= 0)
            channels = avutil.av_get_channel_layout_nb_channels(stream.codecpar().channel_layout());

        if (channels <= 0)
            throw new IllegalArgumentException("channel count not discernible");

        this.outputChannels = channels;

        this.outputBytesPerSample = avutil.av_get_bytes_per_sample(OUTPUT_FORMAT);
        this.outputSampleRate = stream.codecpar().sample_rate();
        this.audio_input_frame_size =  256 * 1024 / outputChannels;

        swrContext = swresample.swr_alloc_set_opts(
                null,

                // Output configuration
                stream.codecpar().channel_layout(),
                OUTPUT_FORMAT,
                stream.codecpar().sample_rate(),

                // Input configuration
                stream.codecpar().channel_layout(),
                stream.codecpar().format(),
                stream.codecpar().sample_rate(),

                0, null
        );

        FFmpegError.checkError("av_opt_set_int", avutil.av_opt_set_int(swrContext, "swr_flags", 1, 0));
        FFmpegError.checkError("swr_init", swresample.swr_init(swrContext));

        int data_size = avutil.av_samples_get_buffer_size(
                (IntPointer) null,
                outputChannels,
                audio_input_frame_size,
                OUTPUT_FORMAT,
                1                           // 	buffer size alignment (0 = default, 1 = no alignment)
        );

        samples_out = new BytePointer[avutil.av_sample_fmt_is_planar(OUTPUT_FORMAT) == 1 ? outputChannels : 1];
        for (int i = 0; i < samples_out.length; i++)
            samples_out[i] = new BytePointer(avutil.av_malloc(data_size)).capacity(data_size);
        samples_out_ptr = new PointerPointer(AVFrame.AV_NUM_DATA_POINTERS);

        for (int i = 0; i < samples_out.length; i++)
            samples_out_ptr.put(i, samples_out[i]);

        this.audioFormat = new AudioFormat(outputSampleRate, outputChannels, stream.codecpar().channel_layout());
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
    public AudioFormat getFormat() {
        return audioFormat;
    }

    @Override
    public AVCodecContext getCodecContext() {
        return codecContext;
    }

    @Override
    public void decode(AVFrame frame) throws FFmpegException {
        int outputCount =
                (int)Math.min(
                        (samples_out[0].limit() - samples_out[0].position()) / (outputChannels * outputBytesPerSample),
                        Integer.MAX_VALUE
                );

        int ret = FFmpegError.checkError("swr_convert", swresample.swr_convert(
                swrContext,
                samples_out_ptr, outputCount,
                frame.extended_data(), frame.nb_samples()
        ));

        if (ret == 0)
            return; // Do nothing.

        // Read unpacked sample buffers
        int bytesResampled = ret * outputBytesPerSample * outputChannels;
        if (recvBuffer == null || recvBuffer.length < bytesResampled)
            recvBuffer = new byte[bytesResampled];

        samples_out[0].position(0).get(recvBuffer);

        FloatBuffer buffer = ByteBuffer
                .wrap(recvBuffer)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        float[] floats = new float[ret * outputChannels];
        for (int i = 0; i < floats.length; i ++)
            floats[i] = buffer.get();

        // Add packet to queue
        totalDecoded += ret;
        double time = FFmpeg.timestampToSeconds(stream.time_base(), frame.pkt_duration());
        double position = FFmpeg.timestampToSeconds(stream.time_base(), frame.pkt_dts());
        setPosition(position);
        double timestamp = parentStream.getCreatedTime() + position;
        parentStream.updatePacketTimestamp(timestamp);
        put(new AudioFrame(
                timestamp,
                position,
                time,
                floats,
                getFormat()
        ));
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("already closed");
            }

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegAudioSourceSubstream.close() called");

            // see: https://ffmpeg.org/doxygen/2.1/doc_2examples_2resampling_audio_8c-example.html
            for (int i = 0; i < samples_out.length; i++) {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "deallocating samples_out[" + i + "])...");
                avutil.av_free(samples_out[i]);
                samples_out[i].deallocate();
                samples_out[i] = null;
            }
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "deallocating samples_out_ptr...");
            samples_out_ptr.deallocate();

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "swr_free(swrContext)...");
            swresample.swr_free(swrContext);

            // Close a given AVCodecContext and free all the data associated with it (but not the AVCodecContext itself).
            // So free it afterwards.

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "avcodec_close(codecContext)...");
            avcodec.avcodec_close(codecContext);
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(codecContext)...");
            avutil.av_free(codecContext);

            closed = true;
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegAudioSourceSubstream.close() complete");
        }
    }

    public static void main(String[] args) throws Exception {
        avutil.av_malloc(1);

        long phy_before_z = Pointer.physicalBytes();

        for (int z = 0; z < 5000; z ++) {
            FFmpegIO.openInputStream(new ByteArrayInputStream(new byte[0]), FFmpegIO.DEFAULT_BUFFER_SIZE).close();
        }

        System.err.println(Pointer.physicalBytes() - phy_before_z);

        try {
            Thread.sleep(100000L );
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
