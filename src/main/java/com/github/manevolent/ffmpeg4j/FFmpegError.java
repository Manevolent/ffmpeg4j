package com.github.manevolent.ffmpeg4j;


import org.bytedeco.ffmpeg.global.*;

import java.nio.ByteBuffer;

public class FFmpegError {
    public static int checkError(String function, int returnCode) throws FFmpegException {
        if (returnCode < 0) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            avutil.av_strerror(returnCode, byteBuffer, 1024);
            throw new FFmpegException("@" + Thread.currentThread().getName() + ": ffmpeg/" +
                    function + ": " + new String(byteBuffer.array()).trim() + " (code=" + returnCode + ")");
        }

        return returnCode;
    }

    public static int checkErrorMuted(String function, int returnCode) {
        if (returnCode < 0) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            avutil.av_strerror(returnCode, byteBuffer, 1024);
            System.err.println("@" + Thread.currentThread().getName() + ": ffmpeg/" +
                    function + ": " + new String(byteBuffer.array()).trim() + " (code=" + returnCode + ")");
        }

        return returnCode;
    }
}
