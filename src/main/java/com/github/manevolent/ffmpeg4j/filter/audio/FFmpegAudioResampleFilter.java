package com.github.manevolent.ffmpeg4j.filter.audio;

import com.github.manevolent.ffmpeg4j.*;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.ffmpeg.swresample.*;
import org.bytedeco.javacpp.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.Collections;

public class FFmpegAudioResampleFilter extends AudioFilter {
    private static final int SAMPLE_FORMAT = avutil.AV_SAMPLE_FMT_FLT;
    public static final int DEFAULT_BUFFER_SIZE = 16 * 1024;
    private volatile SwrContext swrContext;

    private final AudioFormat input, output;

    private final BytePointer[] samples_in;
    private final BytePointer[] samples_out;
    private final PointerPointer samples_in_ptr;
    private final PointerPointer samples_out_ptr;

    private final int outputBytesPerSample, inputBytesPerSample;
    private final int inputPlanes, outputPlanes;

    private ByteBuffer presampleOutputBuffer = ByteBuffer.allocate(0);
    private byte[] recvBuffer;

    public FFmpegAudioResampleFilter(AudioFormat input, AudioFormat output, int bufferSize) throws FFmpegException {
        this.input = input;
        this.output = output;

        if (input.getChannels() <= 0)
            throw new FFmpegException("invalid input channel count: " + input.getChannels());

        if (output.getChannels() <= 0)
            throw new FFmpegException("invalid output channel count: " + output.getChannels());

        try {
            // Configure input parameters
            int ffmpegInputFormat = SAMPLE_FORMAT;
            int inputChannels = input.getChannels();
            inputPlanes = avutil.av_sample_fmt_is_planar(ffmpegInputFormat) != 0 ? inputChannels : 1;
            int inputSampleRate = (int)input.getSampleRate();
            inputBytesPerSample = avutil.av_get_bytes_per_sample(ffmpegInputFormat);
            int inputFrameSize = bufferSize * (input.getChannels() / inputPlanes) * inputBytesPerSample; // x4 for float datatype

            // Configure output parameters
            int ffmpegOutputFormat = SAMPLE_FORMAT;
            int outputChannels = output.getChannels();
            outputPlanes = avutil.av_sample_fmt_is_planar(ffmpegOutputFormat) != 0 ? outputChannels : 1;
            int outputSampleRate = (int)output.getSampleRate();
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
                    output.getChannelLayout(),
                    ffmpegOutputFormat,
                    outputSampleRate,

                    // Input configuration
                    input.getChannelLayout(),
                    ffmpegInputFormat,
                    inputSampleRate,

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
        } catch (Throwable e) {
            if (swrContext != null) {
                swresample.swr_free(swrContext);
                swrContext = null;
            }

            throw new RuntimeException(e);
        }
    }

    @Override
    public Collection<AudioFrame> apply(AudioFrame source) {
        int outputCount =
                (int)Math.min(
                        (samples_out[0].limit() - samples_out[0].position()) / (output.getChannels() * outputBytesPerSample),
                        Integer.MAX_VALUE
                );

        int ffmpegNativeLength = source.getSamples().length * inputBytesPerSample;
        if (presampleOutputBuffer.capacity() < ffmpegNativeLength) {
            presampleOutputBuffer = ByteBuffer.allocate(ffmpegNativeLength);
            presampleOutputBuffer.order(ByteOrder.nativeOrder());
        }

        presampleOutputBuffer.position(0);
        presampleOutputBuffer.asFloatBuffer().put(source.getSamples());

        samples_in[0].position(0).put(presampleOutputBuffer.array(), 0, ffmpegNativeLength);

        // Returns number of samples output per channel, negative value on error
        int ret = swresample.swr_convert(
                swrContext,
                samples_out_ptr, outputCount,
                samples_in_ptr, source.getSamples().length / input.getChannels()
        );

        // Check return values
        try {
            FFmpegError.checkError("swr_convert", ret);
        } catch (FFmpegException e) {
            throw new RuntimeException(e);
        }

        if (ret == 0) return Collections.emptyList();

        // Read native sample buffer(s) into managed raw byte array
        // WARNING: This only works if the output format is non-planar (doesn't end with "P")

        int returnedSamples = ret * output.getChannels();
        int len = returnedSamples * 4;
        if (recvBuffer == null || recvBuffer.length < len)
            recvBuffer = new byte[len];

        samples_out[0].position(0).get(recvBuffer);

        // Convert raw data to bytes.
        // This is done by converting the raw samples to floats right out of ffmpeg to preserve the
        // original quality post-resample.

        FloatBuffer buffer = ByteBuffer.wrap(recvBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.position(0).limit(returnedSamples);

        float[] newBuffer = new float[returnedSamples];
        for (int i = 0; i < returnedSamples; i ++) newBuffer[i] = buffer.get();

        // Return total re-sampled bytes to the higher-level audio system.
        return Collections.singletonList(new AudioFrame(
                source.getTimestamp(),
                source.getPosition(),
                source.getTime(),
                newBuffer,
                output
        ));
    }

    public AudioFormat getInputFormat() {
        return input;
    }

    public AudioFormat getOutputFormat() {
        return output;
    }

    @Override
    public Collection<AudioFrame> flush() {
        return apply(new AudioFrame(0D, 0D, 0D, new float[0], null));
    }

    @Override
    public void close() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegAudioResampleFilter.close() called");

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

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "swr_free.close(swrContext)...");
        swresample.swr_free(swrContext);

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "FFmpegAudioResampleFilter.close() completed");
    }
}
