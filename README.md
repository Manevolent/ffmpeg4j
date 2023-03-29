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
                       String formatName,
                       OutputStream outputStream)
        throws FFmpegException, IOException {
    FFmpegIO input;
    try {
        input = FFmpegIO.openInputStream(inputStream, FFmpegIO.DEFAULT_BUFFER_SIZE);
    } catch (Exception ex) {
        outputStream.close();

       throw new FFmpegException(ex);
   }

   FFmpegIO output;
   try {
       output = FFmpegIO.openOutputStream(outputStream, FFmpegIO.DEFAULT_BUFFER_SIZE);
   } catch (Exception ex) {
       outputStream.close();

       try {
           input.close();
       } catch (Exception e) {
           // Do nothing
       }

       throw new FFmpegException(ex);
   }

   try {
       // Open input
       AVInputFormat inputFormat = FFmpeg.getInputFormatByName(formatName);
       FFmpegSourceStream sourceStream = new FFmpegInput(input).open(inputFormat);
       
       // Read the file header, and register substreams in FFmpeg4j
       sourceStream.registerStreams();

       FFmpegTargetStream targetStream = new FFmpegTargetStream(
               downloadProperties.getFormat(), // Output format
               output,
               new FFmpegTargetStream.FFmpegNativeOutput()
       );

       // Audio
       AudioSourceSubstream defaultAudioSubstream =
               (AudioSourceSubstream)
                       sourceStream.getSubstreams().stream().filter(x -> x instanceof AudioSourceSubstream)
                               .findFirst().orElse(null);

       AudioFilter audioFilter = new FFmpegAudioResampleFilter(
               defaultAudioSubstream.getFormat(),
               targetAudioFormat,
               FFmpegAudioResampleFilter.DEFAULT_BUFFER_SIZE
       );

       // Video
       VideoSourceSubstream defaultVideoSubstream =
               (VideoSourceSubstream)
                       sourceStream.getSubstreams().stream().filter(x -> x instanceof VideoSourceSubstream)
                               .findFirst().orElse(null);
                                    
       VideoFilter videoFilter = new FFmpegVideoRescaleFilter(
               defaultVideoSubstream.getFormat(),
               targetVideoFormat,
               sourceStream.getPixelFormat()
       );

       if (targetStream.getSubstreams().size() <= 0)
           throw new FFmpegException("No substreams to convert");

       try {
           Transcoder.convert(sourceStream, targetStream, finalAudioFilter, finalVideoFilter, 2D);

           // The following is necessary to keep the resource object held in the database
           Logger.getGlobal().fine("Completed.");
       } catch (EOFException e) {
           // This SHOULD happen
       } catch (Exception e) {
           Logger.getGlobal().log(Level.SEVERE, "Problem transcoding media", e);
       } finally {
           try {
               inputStream.close();
           } catch (IOException e) {
               Logger.getGlobal().log(Level.WARNING, "Problem closing input stream", e);
           }

           try {
               outputStream.close();
           } catch (IOException e) {
               Logger.getGlobal().log(Level.WARNING, "Problem closing output stream", e);
           }
       }
   } catch (Exception ex) {
       try {
           input.close();
       } catch (Exception e) {
           // Do nothing
       }

       try {
           output.close();
       } catch (Exception e) {
           // Do nothing
       }

       try {
           outputStream.close();
       } catch (Exception e) {
           // Do nothing
       }

       throw new FFmpegException(ex);
   }
}       
