package com.github.manevolent.ffmpeg4j.transcoder;

import com.github.manevolent.ffmpeg4j.AudioFrame;
import com.github.manevolent.ffmpeg4j.Logging;
import com.github.manevolent.ffmpeg4j.VideoFrame;
import com.github.manevolent.ffmpeg4j.filter.audio.AudioFilter;
import com.github.manevolent.ffmpeg4j.filter.audio.AudioFilterNone;
import com.github.manevolent.ffmpeg4j.filter.video.VideoFilter;
import com.github.manevolent.ffmpeg4j.filter.video.VideoFilterNone;
import com.github.manevolent.ffmpeg4j.source.AudioSourceSubstream;
import com.github.manevolent.ffmpeg4j.source.MediaSourceSubstream;
import com.github.manevolent.ffmpeg4j.source.VideoSourceSubstream;
import com.github.manevolent.ffmpeg4j.stream.output.TargetStream;
import com.github.manevolent.ffmpeg4j.stream.source.SourceStream;

import java.io.EOFException;
import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;

public class Transcoder {
    private final SourceStream sourceStream;
    private final TargetStream targetStream;

    private final AudioFilter audioFilter;
    private final VideoFilter videoFilter;

    private final double speed;

    public Transcoder(SourceStream sourceStream,
                      TargetStream targetStream,
                      AudioFilter audioFilter,
                      VideoFilter videoFilter, double speed) {
        this.sourceStream = sourceStream;
        this.targetStream = targetStream;

        this.audioFilter = audioFilter;
        this.videoFilter = videoFilter;

        this.speed = speed;
    }

    public void transcode() throws Exception {
        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder.transcode() called");

        try {
            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "targetStream.writeHeader()...");
            // Write header to file
            targetStream.writeHeader();

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: converting...");
            // Actual conversion
            MediaSourceSubstream substream;
            long start = System.nanoTime();

            double maximumPosition;

            while (true) {
                try {
                    substream = handlePacket(sourceStream.readPacket());
                } catch (EOFException ex) {
                    break;
                }

                if (substream == null) continue;

                while (true) {
                    maximumPosition = (((double) System.nanoTime() - (double) start) / 1_000_000_000D) * speed;

                    if (substream.getPosition() > maximumPosition)
                        Thread.sleep((long) Math.ceil((substream.getPosition() - maximumPosition) * 1000D));
                    else
                        break;
                }
            }

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: flushing audio filters...");
            for (AudioFrame audioFrame : audioFilter.flush())
                targetStream.getAudioTargetStream().write(audioFrame);

            Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: flushing video filters...");
            for (VideoFrame videoFrame : videoFilter.flush())
                targetStream.getVideoTargetStream().write(videoFrame);
        } finally {
            // Close files
            try {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: closing source stream...");
                sourceStream.close();
            } catch (Exception ex) {
                Logging.LOGGER.log(Level.WARNING, "Problem closing sourceStream", ex);
            }

            try {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: closing target stream...");
                targetStream.close();
            } catch (Exception ex) {
                Logging.LOGGER.log(Level.WARNING, "Problem closing targetStream", ex);
            }

            // Close filters
            try {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: closing audio filter...");
                audioFilter.close();
            } catch (Exception ex) {
                Logging.LOGGER.log(Level.WARNING, "Problem closing audioFilter", ex);
            }

            try {
                Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder: closing video filter...");
                videoFilter.close();
            } catch (Exception ex) {
                Logging.LOGGER.log(Level.WARNING, "Problem closing videoFilter", ex);
            }
        }

        Logging.LOGGER.log(Logging.DEBUG_LOG_LEVEL, "Transcoder.transcode() completed");
    }

    private MediaSourceSubstream handlePacket(SourceStream.Packet packet) throws IOException {
        if (packet == null) return null;

        MediaSourceSubstream substream = packet.getSourceStream();

        switch (packet.getSourceStream().getMediaType()) {
            case AUDIO:
                handleSubstream((AudioSourceSubstream) substream);
                break;
            case VIDEO:
                handleSubstream((VideoSourceSubstream) substream);
                break;
        }

        return substream;
    }

    private void handleSubstream(AudioSourceSubstream substream) throws IOException {
        Collection<AudioFrame> audioFrames = substream.drain();
        if (targetStream.getAudioTargetStream() != null)
            for (AudioFrame frame : audioFrames)
                for (AudioFrame filteredFrame : audioFilter.apply(frame))
                    targetStream.getAudioTargetStream().write(filteredFrame);
    }

    private void handleSubstream(VideoSourceSubstream substream) throws IOException {
        Collection<VideoFrame> videoFrames = substream.drain();
        if (targetStream.getVideoTargetStream() != null)
            for (VideoFrame frame : videoFrames)
                for (VideoFrame filteredFrame : videoFilter.apply(frame))
                    targetStream.getVideoTargetStream().write(filteredFrame);
    }

    public static void convert(SourceStream sourceStream, TargetStream targetStream,
                               double speed) throws Exception {
        new Transcoder(sourceStream, targetStream, new AudioFilterNone(), new VideoFilterNone(), speed).transcode();
    }

    public static void convert(SourceStream sourceStream, TargetStream targetStream,
                               AudioFilter audioFilter, VideoFilter videoFilter,
                               double speed) throws Exception {
        new Transcoder(sourceStream, targetStream, audioFilter, videoFilter, speed).transcode();
    }
}
