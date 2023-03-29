package com.github.manevolent.ffmpeg4j.source;
import com.github.manevolent.ffmpeg4j.FFmpegError;
import com.github.manevolent.ffmpeg4j.FFmpegException;
import com.github.manevolent.ffmpeg4j.FFmpegStreamContext;
import com.github.manevolent.ffmpeg4j.output.FFmpegEncoderContext;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;

public interface FFmpegDecoderContext extends FFmpegStreamContext {

    /**
     * Finds if the decoder is currently decoding, or if it is instead discarding packets.
     * @return true if decoding is taking place, false otherwise.
     */
    boolean isDecoding();

    /**
     * Decode callback for the decoder.
     * @param frame Frame to decode.
     * @throws FFmpegException
     */
    void decode(AVFrame frame) throws FFmpegException;

    /**
     * Processes frames made available by decodePacket().  This calls decode(), which is typically fulfilled in a
     * superclass.
     * @return number of frames made available.
     * @throws FFmpegException
     */
    default int processAvailableFrames() throws FFmpegException {
        int ret = 0;

        int frames_finished = 0;

        AVFrame frame;
        while (ret >= 0) {
            frame = avutil.av_frame_alloc();
            if (frame == null)
                throw new NullPointerException("av_frame_alloc");

            try {
                ret = avcodec.avcodec_receive_frame(getCodecContext(), frame);
                if (ret == avutil.AVERROR_EAGAIN())
                    break; // output is not available right now - user must try to send new input

                // Check for misc. errors:
                FFmpegError.checkError("avcodec_receive_frame", ret);

                try {
                    decode(frame);

                    // If we made it this far:
                    frames_finished++;
                } finally {
                    // Do nothing here
                }
            } finally {
                // VLC does this
                avutil.av_frame_free(frame);

                // Encourage JVM to de-allocate the object
                frame = null;
            }
        }

        return frames_finished;
    }

    /**
     * Decodes a given received packet.  Typically the packet is received from the format stream (e.g. webm)
     * @param packet Packet to decode frames from.
     * @return Number of raw frames decoded.
     * @throws FFmpegException
     */
    default int decodePacket(AVPacket packet) throws FFmpegException {
        int ret = avutil.AVERROR_EAGAIN(), frames_finished = 0;

        while (ret == avutil.AVERROR_EAGAIN()) {
            ret = avcodec.avcodec_send_packet(getCodecContext(), packet);
            if (ret != avutil.AVERROR_EAGAIN())
                FFmpegError.checkError("avcodec_send_packet", ret);

            frames_finished += processAvailableFrames();
        }

        return frames_finished;
    }
}
