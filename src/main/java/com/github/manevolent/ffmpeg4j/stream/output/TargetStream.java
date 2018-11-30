package com.github.manevolent.ffmpeg4j.stream.output;

import com.github.manevolent.ffmpeg4j.MediaType;
import com.github.manevolent.ffmpeg4j.output.AudioTargetSubstream;
import com.github.manevolent.ffmpeg4j.output.MediaTargetSubstream;
import com.github.manevolent.ffmpeg4j.output.VideoTargetSubstream;
import com.github.manevolent.ffmpeg4j.stream.Stream;
import com.github.manevolent.ffmpeg4j.stream.event.EventChannel;

import java.util.List;

public abstract class TargetStream extends Stream<MediaTargetSubstream> {
    private final EventChannel<TargetStream> onReady = new EventChannel<>();

    public final EventChannel<TargetStream> getOnReady() {
        return onReady;
    }

    public abstract void writeHeader();

    @Override
    public abstract List<MediaTargetSubstream> getSubstreams();

    public AudioTargetSubstream getAudioTargetStream() {
        return (AudioTargetSubstream)
                getSubstreams().stream()
                        .filter(x -> x.getType() == MediaType.AUDIO)
                        .findFirst().orElse(null);
    }

    public VideoTargetSubstream getVideoTargetStream() {
        return (VideoTargetSubstream)
                getSubstreams().stream()
                        .filter(x -> x.getType() == MediaType.VIDEO)
                        .findFirst().orElse(null);
    }
}
