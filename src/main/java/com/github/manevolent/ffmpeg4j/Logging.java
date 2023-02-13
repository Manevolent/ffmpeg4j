package com.github.manevolent.ffmpeg4j;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Logging {
    public static Level DEBUG_LOG_LEVEL = Level.FINEST;
    public static final Logger LOGGER = Logger.getLogger("Media");

    static {
        LOGGER.setParent(Logger.getGlobal());
        LOGGER.setUseParentHandlers(true);
    }
}
