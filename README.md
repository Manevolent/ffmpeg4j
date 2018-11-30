# ffmpeg4j

FFmpeg4j is a Java library that wraps the functionality of the popular open-source multimedia library FFmpeg (https://www.ffmpeg.org/), whose JNI bindings are excellently exposed through JavaCPP (https://github.com/bytedeco/javacpp).  This preserves the cross-platform benefits of the JRE, while still delivering the full features and performance benefits of the FFmpeg library in an OOP fashion.

This library runs FFmpeg native routines within the JRE, via JNI.  You do not need a compiled executable (i.e. ffmpeg.exe) to use ffmpeg4j, only a series of static libraries which are part of the package.

# Features

 - A (partly complete) wrapper around the core behaviors of the FFmpeg library: audio, video, and their containing file formats.
 - Full coverage of all formats supported by FFmpeg (there are many!)
 - Tested for stability and optimized for the least wrapper overhead
 - Capable of delivering great apps like music bots, video transcoders, and livestream propogation
 - Sensible structure to fit within a Java development environment; don't deal directly with the C-like constructs exposed by JavaCPP.
 - Utilize standard OutputStream and InputStream objects to read and write media, something I desparately needed and couldn't find in other Java FFmpeg wrappers.  Avoid hitting the disk altogether!
