# ffmpeg4j

FFmpeg4j is a Java library that wraps the functionality of the popular open-source multimedia library FFmpeg (https://www.ffmpeg.org/), whose JNI bindings are excellently exposed through JavaCPP (https://github.com/bytedeco/javacpp).  This preserves the cross-platform benefits of the JRE, while still delivering the full features and performance benefits of the FFmpeg library in an OOP fashion.

This library runs FFmpeg native routines within the JRE, via JNI.  You do not need a compiled executable (i.e. ffmpeg.exe) to use ffmpeg4j, only a series of static libraries which are part of the package.

# Maven

If you want the latest `-SNAPSHOT`:

```
<repositories>
	<repository>
	    <id>jitpack.io</id>
	    <url>https://jitpack.io</url>
	</repository>
</repositories>
<dependency>
    <groupId>com.github.manevolent</groupId>
    <artifactId>ffmpeg4j</artifactId>
    <version>-SNAPSHOT</version>
</dependency>
```

# Features

 - A (partly complete) wrapper around the core behaviors of the FFmpeg library: audio, video, and their containing file formats.
 - Full coverage of all formats supported by FFmpeg (there are many!)
 - Tested for stability and optimized for the least wrapper overhead
 - Capable of delivering great apps like music bots, video transcoders, and livestream propogation
 - Sensible structure to fit within a Java development environment; don't deal directly with the C-like constructs exposed by JavaCPP.
 - Use typical InputStream and OutputStream objects to read and write media.
 - Use Channels to read and write media when seeking is needed (i.e. MP4 muxing).
 - Write applications that don't touch the disk (filesystem) for better performance and lower resource cost of in-flight data.

# Examples

### Read an audio file
```java
InputStream inputStream = new FileInputStream("example.ogg");
FFmpegInput input = new FFmpegInput(inputStream);
FFmpegSourceStream stream = input.open(inputFormat);

// Read the file header, and register substreams in FFmpeg4j
stream.registerStreams();

AudioSourceSubstream audioSourceSubstream = null;
for (MediaSourceSubstream substream : stream.getSubstreams()) {
    if (substream.getMediaType() != MediaType.AUDIO) continue;

    audioSourceSubstream = (AudioSourceSubstream) substream;
}

if (audioSourceSubstream == null) throw new NullPointerException();

AudioFrame frame;

while (true) {
    try {
        frame = audioSourceSubstream.next();
        float[] interleaved_ABABAB_AudioSamples = frame.getSamples();
    } catch (EOFException ex) {
        break;
    }
}
```

### Transcode media
```java
private void transcode(InputStream inputStream,
                       String inputFormatName,
                       SeekableByteChannel outputChannel,
                       String outputFormatName) throws FFmpegException, IOException {
	try (FFmpegSourceStream sourceStream = FFmpegIO.openInputStream(inputStream, FFmpegIO.DEFAULT_BUFFER_SIZE).open(inputFormatName);
	     FFmpegTargetStream targetStream = FFmpegIO.openChannel(outputChannel, FFmpegIO.DEFAULT_BUFFER_SIZE).asOutput().open(outputFormatName)) {
	    sourceStream.registerStreams();
	    sourceStream.copyToTargetStream(targetStream);
	    Transcoder.convert(sourceStream, targetStream, Double.MAX_VALUE);
	}
}       
```
