package com.github.manevolent.ffmpeg4j.output;

import com.github.manevolent.ffmpeg4j.FFmpegStreamContext;
import com.github.manevolent.ffmpeg4j.FFmpegError;
import com.github.manevolent.ffmpeg4j.FFmpegException;
import com.github.manevolent.ffmpeg4j.stream.output.FFmpegTargetStream;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.*;

import java.io.EOFException;

public interface FFmpegEncoderContext extends FFmpegStreamContext {

    void writePacket(AVPacket packet) throws FFmpegException, EOFException;

    FFmpegTargetStream getTargetStream();

    /**
     * Processes encoded frames made available by encodeFrame() in their packetized state and flushes them on to
     * writePacket(), which is typically fulfilled by a superclass
     * @return Number of packets written.
     * @throws FFmpegException
     * @throws EOFException
     */
    default int processAvailablePackets() throws FFmpegException, EOFException {
        int ret = 0;

        int packets_finished = 0;

        while (ret >= 0) {
            AVPacket packet = avcodec.av_packet_alloc();
            if (packet == null) throw new NullPointerException("av_packet_alloc()");

            try {
                ret = avcodec.avcodec_receive_packet(getCodecContext(), packet);
                if (ret == avutil.AVERROR_EAGAIN()) break; // output is not available right now - user must try to send new input

                // Check for misc. errors:
                FFmpegError.checkError("avcodec_receive_packet", ret);

                writePacket(packet);

                // If we made it this far:
                packets_finished++;
            } finally {
                avcodec.av_packet_free(packet);
            }
        }

        return packets_finished;
    }

    /**
     * Encodes a raw frame into a series of packets.
     * @param frame Frame to encode.
     * @return Number of packets made available.
     * @throws FFmpegException
     * @throws EOFException
     */
    default int encodeFrame(AVFrame frame) throws FFmpegException, EOFException {
        int ret = -11, packet_finished = 0;

        while (ret == -11) {
            ret = avcodec.avcodec_send_frame(getCodecContext(), frame);

            if (ret != -11)
                FFmpegError.checkError("avcodec_send_frame", ret);

            packet_finished += processAvailablePackets();
        }

        return packet_finished;
    }
}
