package com.github.manevolent.ffmpeg4j.stream.event;

import java.util.LinkedList;
import java.util.List;

public class EventChannel<T> implements EventListener<T> {
    private final List<EventListener<T>> list = new LinkedList<>();
    public boolean addListener(EventListener<T> listener) {
        return list.add(listener);
    }
    public boolean removeListener(EventListener<T> listener) {
        return list.remove(listener);
    }
    public void accept(T o) throws EventException {
        for (EventListener<T> listener : list) listener.accept(o);
    }
}
