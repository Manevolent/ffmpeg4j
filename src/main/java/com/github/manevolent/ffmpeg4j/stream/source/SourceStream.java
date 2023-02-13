package com.github.manevolent.ffmpeg4j.stream.source;

import com.github.manevolent.ffmpeg4j.source.MediaSourceSubstream;
import com.github.manevolent.ffmpeg4j.stream.Stream;
import com.github.manevolent.ffmpeg4j.stream.event.EventChannel;

import java.io.IOException;
import java.util.List;

public abstract class SourceStream extends Stream<MediaSourceSubstream> {
    // Events
    private final EventChannel<SourceStream> onReady = new EventChannel<>();
    private final EventChannel<SourceStream> onClosed = new EventChannel<>();
    private double lastPacketTimestamp = 0D;

    /**
     * Gets the time the stream was created or started.
     * @return created time.
     */
    public abstract double getCreatedTime();

    public double getLastPacketTimestamp() {
        return lastPacketTimestamp;
    }

    public void updatePacketTimestamp(double newTimestamp) {
        lastPacketTimestamp = Math.max(newTimestamp, lastPacketTimestamp);
    }

    /**
     * Seeks to a specified point in the stream. The result of the seek operation may not be exact.
     * @param position position to seek to, in seconds.
     * @return final position of the seek operation, in seconds. Keep in mind due to the quantized nature of media streams this may not be equal to the
     * requested position, but is guaranteed to be as close as possible.
     */
    public abstract double seek(double position) throws IOException;

    public abstract Packet readPacket() throws IOException;

    @Override
    public abstract List<MediaSourceSubstream> getSubstreams();

    public EventChannel<SourceStream> getOnReady() {
        return onReady;
    }
    public EventChannel<SourceStream> getOnClosed() {
        return onClosed;
    }

    public abstract void setCreatedTime(double createdTimeInSeconds);

    public class Packet {
        private final MediaSourceSubstream sourceStream;
        private final int finished;
        private final long bytesProcessed;
        private final double position;
        private final double duration;

        public Packet(MediaSourceSubstream sourceSteram, long bytesProcessed, int finished, double position, double duration) {
            this.bytesProcessed = bytesProcessed;
            this.sourceStream = sourceSteram;
            this.finished = finished;
            this.position = position;
            this.duration = duration;
        }

        public MediaSourceSubstream getSourceStream() {
            return sourceStream;
        }

        public long getBytesProcessed() {
            return bytesProcessed;
        }

        public int getFinishedFrames() {
            return finished;
        }

        public double getPosition() {
            return position;
        }

        public double getDuration() {
            return duration;
        }
    }
}
