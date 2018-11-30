package com.github.manevolent.ffmpeg4j.stream.event;

public class EventException extends Exception {
    public EventException(Exception cause) {
        super(cause);
    }
    public EventException(String message) {
        super(message);
    }
}
