package com.github.manevolent.ffmpeg4j.stream.event;

/**
 * Represents the streaming api's event listener
 * @param <T> Event object
 */
public interface EventListener<T> {
    void accept(T o) throws EventException;
}
