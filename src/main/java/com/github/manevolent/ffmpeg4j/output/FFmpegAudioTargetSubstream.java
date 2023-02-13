package com.github.manevolent.ffmpeg4j.output;

import com.github.manevolent.ffmpeg4j.*;
import com.github.manevolent.ffmpeg4j.stream.output.FFmpegTargetStream;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.ffmpeg.swresample.*;
import org.bytedeco.javacpp.*;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FFmpegAudioTargetSubstream
        extends AudioTargetSubstream
        implements FFmpegEncoderContext
{
    private static final int SAMPLE_FORMAT = avutil.AV_SAMPLE_FMT_FLT;

    private final FFmpegTargetStream targetStream;
    private final AVStream stream;
    private final AVCodecContext codecContext;
    private final AVPacket packet;

    //swresample
    private volatile SwrContext swrContext;
    private final int outputBytesPerSample, inputBytesPerSample, inputPlanes, outputPlanes;
    private ByteBuffer presampleOutputBuffer = ByteBuffer.allocate(0);
    private final BytePointer[] samples_in;
    private final BytePointer[] samples_out;
    private final PointerPointer samples_in_ptr;
    private final PointerPointer samples_out_ptr;

    private int sampleBufferPosition = 0;
    private final float[] sampleBuffer;
    private final AVRational nativeTimeBase;
    private volatile long writtenSamples = 0L;

    public FFmpegAudioTargetSubstream(FFmpegTargetStream targetStream, AVStream stream, AVCodecContext codecContext) throws FFmpegException {
        this.packet = new AVPacket();
        this.targetStream = targetStream;
        this.stream = stream;
        this.codecContext = codecContext;

        // Configure input parameters
        int ffmpegInputFormat = SAMPLE_FORMAT;
        int inputChannels = stream.codecpar().channels();
        inputPlanes = avutil.av_sample_fmt_is_planar(ffmpegInputFormat) != 0 ? inputChannels : 1;
        int inputSampleRate = stream.codecpar().sample_rate();
        inputBytesPerSample = avutil.av_get_bytes_per_sample(ffmpegInputFormat);
        int inputFrameSize = (16*1024) * inputPlanes * inputBytesPerSample;

        // Configure output parameters
        int ffmpegOutputFormat = stream.codecpar().format();
        int outputChannels = stream.codecpar().channels();
        outputPlanes = avutil.av_sample_fmt_is_planar(ffmpegOutputFormat) != 0 ? outputChannels : 1;
        int outputSampleRate = stream.codecpar().sample_rate();
        outputBytesPerSample = avutil.av_get_bytes_per_sample(ffmpegOutputFormat);
        int outputFrameSize = avutil.av_samples_get_buffer_size(
                (IntPointer) null,
                outputChannels,
                inputFrameSize, // Input frame size neccessary
                ffmpegOutputFormat,
                1
        ) / outputPlanes;

        swrContext = swresample.swr_alloc_set_opts(
                null,

                // Output configuration
                stream.codecpar().channel_layout(),
                ffmpegOutputFormat,
                stream.codecpar().sample_rate(),

                // Input configuration
                stream.codecpar().channel_layout(),
                ffmpegInputFormat,
                stream.codecpar().sample_rate(),

                0, null
        );

        // Force resampler to always resample regardless of the sample rates.
        // This forces the output to always be floats.
        avutil.av_opt_set_int(swrContext, "swr_flags", 1, 0);

        FFmpegError.checkError("swr_init", swresample.swr_init(swrContext));

        // Create input buffers
        samples_in = new BytePointer[inputPlanes];
        for (int i = 0; i < inputPlanes; i++) {
            samples_in[i] = new BytePointer(avutil.av_malloc(inputFrameSize)).capacity(inputFrameSize);
        }

        // Create output buffers
        samples_out = new BytePointer[outputPlanes];
        for (int i = 0; i < outputPlanes; i++) {
            samples_out[i] = new BytePointer(avutil.av_malloc(outputFrameSize)).capacity(outputFrameSize);
        }

        // Initialize input and output sample buffers;
        samples_in_ptr = new PointerPointer(inputPlanes);
        samples_out_ptr = new PointerPointer(outputPlanes);

        for (int i = 0; i < samples_out.length; i++)
            samples_out_ptr.put(i, samples_out[i]);

        for (int i = 0; i < samples_in.length; i++)
            samples_in_ptr.put(i, samples_in[i]);

        this.nativeTimeBase = new AVRational();
        nativeTimeBase.num(1);
        nativeTimeBase.den(outputSampleRate);

        // Smp buffer is 2 frames long, always
        this.sampleBuffer = new float[(stream.codecpar().frame_size() * outputChannels) * 2];
    }

    @Override
    public MediaType getType() {
        return MediaType.AUDIO;
    }

    /**
     * Writes a single (or partial) frame to the stream.  Cannot ingest more than one frame.
     * @param samples Buffer of samples to write, can be any length >= len
     * @param len Samples per channel (may need to be equal to frame_size).  Sending 0 will flush the stream. Cannot be
     *            > frame_size().  Can be 0.  Must be positive.
     * @throws FFmpegException
     */
    private int writeFrame(float[] samples, int len)
            throws FFmpegException, EOFException {
        if (len < 0)
            throw new FFmpegException(new ArrayIndexOutOfBoundsException(len));

        if (len > getCodecContext().frame_size())
            throw new FFmpegException("invalid frame size: " + len + " > " + getCodecContext().frame_size());

        int ffmpegNativeLength = len * getCodecContext().channels() * inputBytesPerSample;
        if (presampleOutputBuffer.capacity() < ffmpegNativeLength) {
            presampleOutputBuffer = ByteBuffer.allocate(ffmpegNativeLength);
            presampleOutputBuffer.order(ByteOrder.nativeOrder());
        }

        presampleOutputBuffer.position(0);

        // Clip audio between -1F,1F
        for (int i = 0; i < len; i ++)
            samples[i] = Math.min(1F, Math.max(-1F, samples[i]));

        // Obtain a 'samplesToRead' worth (chunk) of frames from the sample buffer
        presampleOutputBuffer.asFloatBuffer().put(samples, 0, len * stream.codecpar().channels());

        samples_in[0].position(0).put(presampleOutputBuffer.array(), 0, ffmpegNativeLength);

        int outputCount =
                (int) Math.min(
                        (samples_out[0].limit() - samples_out[0].position()) /
                                (stream.codecpar().channels() * outputBytesPerSample),
                        Integer.MAX_VALUE
                );

        //Returns number of samples output per channel, negative value on error
        int ret = swresample.swr_convert(
                swrContext,
                samples_out_ptr, outputCount,
                samples_in_ptr, len
        );

        // Check return values
        FFmpegError.checkError("swr_convert", ret);

        if (ret == 0) return 0;

        AVFrame frame = avutil.av_frame_alloc();
        if (frame == null) throw new NullPointerException("av_frame_alloc");

        try {
            frame.nb_samples(ret);
            frame.format(stream.codecpar().format());
            frame.channels(stream.codecpar().channels());
            frame.channel_layout(stream.codecpar().channel_layout());
            frame.pts(avutil.av_rescale_q(writtenSamples, nativeTimeBase, getCodecContext().time_base()));

            for (int plane = 0; plane < outputPlanes; plane++)
                frame.data(plane, samples_out[plane]);

            encodeFrame(frame);
        } finally {
            avutil.av_frame_free(frame);
        }

        writtenSamples += ret;
        setPosition((double) writtenSamples / (double) stream.codecpar().sample_rate());

        return ret;
    }

    /**
     * Drains the internal buffer of samples.  This method typically drains full frames instead of partial frames,
     * as some (most) codecs do not support "variable" frame sizes, such as OPUS, which supports 960 samples per frame
     * typically.  In this case, we want to flush/drain down in chunks.
     * @param flush Overrides the chunking code -- this will flush down ANY variable frame size, it set to true by
     *              the flush() function when bookending the stream.  Unwise to do this elsewhere.
     * @return
     * @throws FFmpegException
     * @throws EOFException
     */
    private int drainInternalBuffer(boolean flush) throws FFmpegException, EOFException {
        int totalFrameSize = stream.codecpar().frame_size() * stream.codecpar().channels();
        int minimumFrameSize = flush ? 1 : totalFrameSize;
        int written = 0, encoded = 0, toWrite;

        int channels = stream.codecpar().channels();
        if (channels <= 0) throw new IllegalArgumentException("channels <= 0: " + channels);

        while (sampleBufferPosition >= minimumFrameSize) {
            toWrite = flush ?
                    Math.min(totalFrameSize, sampleBufferPosition) :
                    totalFrameSize;

            encoded += writeFrame(sampleBuffer, toWrite / channels);

            System.arraycopy(
                    sampleBuffer, toWrite,
                    sampleBuffer, 0, sampleBufferPosition - toWrite
            );

            sampleBufferPosition -= toWrite;
            written += toWrite;
        }

        return written;
    }

    /**
     * Flushes all available internal samples from the buffer, effectively emptying any waiting data.
     * @throws IOException
     */
    public void flush() throws IOException {
        try {
            drainInternalBuffer(true);
            writeFrame(new float[0], 0);
        } catch (FFmpegException e) {
            throw new IOException(e);
        }
    }

    /**
     * Frontend encoder.  This accepts any arbitrarily sized audio frame (0-n samples) and will automatically drain
     * it down the stream correctly for you.
     * @param o Audio Frame object to encode into sub-frames and, subsequently, packets.
     * @throws IOException
     */
    @Override
    public void write(AudioFrame o) throws IOException {
        int size = o.getLength();

        if (o == null || size <= 0) {
            try {
                drainInternalBuffer(false);
            } catch (FFmpegException e) {
                throw new IOException(e);
            }
            return;
        }

        int position = 0;
        int read;

        while (position < size) {
            // Fill sample buffer with remaining samples
            read = Math.min(size - position, sampleBuffer.length - sampleBufferPosition);
            if (read <= 0) throw new ArrayIndexOutOfBoundsException(read);

            System.arraycopy(o.getSamples(), position, sampleBuffer, sampleBufferPosition, read);
            sampleBufferPosition += read;
            position += read;

            // Drain the internal buffer
            try {
                drainInternalBuffer(false);
            } catch (FFmpegException e) {
                throw new IOException(e);
            }

        }
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegAudioTargetSubstream.close() called");

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "avcodec_close(stream.codec())...");
        avcodec.avcodec_close(getCodecContext());
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free(stream.codec())...");
        avutil.av_free(getCodecContext());

        // see: https://ffmpeg.org/doxygen/2.1/doc_2examples_2resampling_audio_8c-example.html
        for (int i = 0; i < samples_in.length; i++) {
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "deallocating samples_in[" + i + "])...");
            avutil.av_free(samples_in[i]);
            samples_in[i].deallocate();
            samples_in[i] = null;
        }
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "deallocating samples_in_ptr...");
        samples_in_ptr.deallocate();

        // see: https://ffmpeg.org/doxygen/2.1/doc_2examples_2resampling_audio_8c-example.html
        for (int i = 0; i < samples_out.length; i++) {
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "deallocating samples_out[" + i + "])...");
            avutil.av_free(samples_out[i]);
            samples_out[i].deallocate();
            samples_out[i] = null;
        }
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "deallocating samples_out_ptr...");
        samples_out_ptr.deallocate();

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "nativeTimeBase.deallocate()...");
        nativeTimeBase.deallocate();
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "swr_free(swrContext)...");
        swresample.swr_free(swrContext);
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "av_free_packet(packet)...");
        avcodec.av_packet_free(packet);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegAudioTargetSubstream.close() completed");
    }

    /**
     * Called by the encoder context -- writes an audio packet out to the parent target stream.
     * @param packet Encoded packet to write
     * @throws FFmpegException
     * @throws EOFException
     */
    @Override
    public void writePacket(AVPacket packet) throws FFmpegException, EOFException {
        packet.pts(writtenSamples);
        avcodec.av_packet_rescale_ts(packet, getCodecContext().time_base(), stream.time_base());
        packet.stream_index(stream.index());
        getTargetStream().writePacket(packet);
    }

    @Override
    public AVCodecContext getCodecContext() {
        return codecContext;
    }

    @Override
    public FFmpegTargetStream getTargetStream() {
        return targetStream;
    }
}
