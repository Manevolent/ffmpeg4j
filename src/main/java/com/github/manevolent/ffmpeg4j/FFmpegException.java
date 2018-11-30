package com.github.manevolent.ffmpeg4j;

public class FFmpegException extends Exception {
    public FFmpegException(String cause) {
        super(cause);
    }
    public FFmpegException(Exception cause) {
        super(cause);
    }
}
