package com.github.manevolent.ffmpeg4j.source;
import com.github.manevolent.ffmpeg4j.FFmpegError;
import com.github.manevolent.ffmpeg4j.FFmpegException;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;

public interface FFmpegDecoderContext {
    avcodec.AVCodecContext getCodecContext();

    /**
     * Decode callback for the decoder.
     * @param frame Frame to decode.
     * @throws FFmpegException
     */
    void decode(avutil.AVFrame frame) throws FFmpegException;

    /**
     * Processes frames made available by decodePacket().  This calls decode(), which is typically fulfilled in a
     * superclass.
     * @return number of frames made available.
     * @throws FFmpegException
     */
    default int processAvailableFrames() throws FFmpegException {
        int ret = 0;

        int frames_finished = 0;

        avutil.AVFrame frame;
        while (ret >= 0) {
            frame = avutil.av_frame_alloc();
            if (frame == null)
                throw new NullPointerException("av_frame_alloc");

            try {
                ret = avcodec.avcodec_receive_frame(getCodecContext(), frame);
                if (ret == -11)
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
    default int decodePacket(avcodec.AVPacket packet) throws FFmpegException {
        int ret = -11, frames_finished = 0;

        while (ret == -11) {
            ret = avcodec.avcodec_send_packet(getCodecContext(), packet);
            if (ret != -11)
                FFmpegError.checkError("avcodec_send_packet", ret);

            frames_finished += processAvailableFrames();
        }

        return frames_finished;
    }
}
