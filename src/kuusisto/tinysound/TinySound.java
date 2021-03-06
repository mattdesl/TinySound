/*
 * Copyright (c) 2012, Finn Kuusisto
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package kuusisto.tinysound;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import kuusisto.tinysound.internal.ByteList;
import kuusisto.tinysound.internal.Mixer;
import kuusisto.tinysound.internal.UpdateRunner;

/**
 * TinySound is the main class of the TinySound system.  In order to use the
 * TinySound system, it must be initialized.  After that, Music and Sound
 * objects can be loaded and used.  When finished with the TinySound system, it
 * must be shutdown.
 * 
 * @author Finn Kuusisto
 */
public class TinySound {

	/**
	 * The internal format used by TinySound.
	 */
	public static final AudioFormat FORMAT = new AudioFormat(
			AudioFormat.Encoding.PCM_SIGNED, //linear signed PCM
			44100, //44.1kHz sampling rate
			16, //16-bit
			2, //2 channels fool
			4, //frame size 4 bytes (16-bit, 2 channel)
			44100, //same as sampling rate
			false //little-endian
			);
	
	//the system has only one mixer for both music and sounds
	private static Mixer mixer;
	//need a line to the speakers
	private static SourceDataLine outLine;
	//see if the system has been initialized
	private static boolean inited = false;
	//auto-updater for the system
	private static UpdateRunner autoUpdater;
	
	/**
	 * Initialize Tinysound.  This must be called before loading audio.
	 */
	public static void init() {
		if (TinySound.inited) {
			return;
		}
		//try to open a line to the speakers
		DataLine.Info info = new DataLine.Info(SourceDataLine.class,
				TinySound.FORMAT);
		if (!AudioSystem.isLineSupported(info)) {
		    System.err.println("Unsupported output format!");
		    return;
		}
		TinySound.outLine = TinySound.tryGetLine();
		if (TinySound.outLine == null) {
		    System.err.println("Output line unavailable!");
		    return;
		}
		//start the line and finish initialization
		TinySound.outLine.start();
		TinySound.finishInit();
	}
	
	/**
	 * Alternative function to initialize TinySound which should only be used by
	 * those very familiar with the Java Sound API.  This function allows the
	 * line that is used for audio playback to be opened on a specific Mixer.
	 * @param info the Mixer.Info representing the desired Mixer
	 * @throws LineUnavailableException if a Line is not available from the
	 * specified Mixer
	 * @throws SecurityException if the specified Mixer or Line are unavailable
	 * due to security restrictions
	 * @throws IllegalArgumentException if the specified Mixer is not installed
	 * on the system
	 */
	public static void init(javax.sound.sampled.Mixer.Info info) 
			throws LineUnavailableException, SecurityException,
			IllegalArgumentException {
		if (TinySound.inited) {
			return;
		}
		//try to open a line to the speakers
		javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(info);
		DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class,
				TinySound.FORMAT);
		TinySound.outLine = (SourceDataLine)mixer.getLine(lineInfo);
		TinySound.outLine.open(TinySound.FORMAT);
		//start the line and finish initialization
		TinySound.outLine.start();
		TinySound.finishInit();
	}
	
	/**
	 * Initializes the mixer and updater, and marks TinySound as initialized.
	 */
	private static void finishInit() {
		//now initialize the mixer
		TinySound.mixer = new Mixer();
		//initialize and start the updater
		TinySound.autoUpdater = new UpdateRunner(TinySound.mixer,
				TinySound.outLine);
		Thread updateThread = new Thread(TinySound.autoUpdater);
		try {
			updateThread.setDaemon(true);
			updateThread.setPriority(Thread.MAX_PRIORITY);
		} catch (Exception e) {}
		TinySound.inited = true;
		updateThread.start();
		//yield to potentially give the updater a chance
		Thread.yield();
	}
	
	/**
	 * Shutdown TinySound.
	 */
	public static void shutdown() {
		if (!TinySound.inited) {
			return;
		}
		TinySound.inited = false;
		//stop the auto-updater if running
		TinySound.autoUpdater.stop();
		TinySound.autoUpdater = null;
		TinySound.outLine.stop();
		TinySound.outLine.flush();
		TinySound.mixer.clearMusic();
		TinySound.mixer.clearSounds();
		TinySound.mixer = null;
	}
	
	/**
	 * Determine if TinySound is initialized and ready for use.
	 * @return true if TinySound is initialized, false if TinySound has not been
	 * initialized or has subsequently been shutdown
	 */
	public static boolean isInitialized() {
		return TinySound.inited;
	}
	
	/**
	 * Load a Music by a resource name.  The resource must be on the classpath
	 * for this to work.
	 * @param name name of the Music resource
	 * @return Music resource as specified, null if not found/loaded
	 */
	public static Music loadMusic(String name) {
		//check if the system is initialized
		if (!TinySound.inited) {
			System.err.println("TinySound not initialized!");
			return null;
		}
		//check for failure
		if (name == null) {
			return null;
		}
		//check for correct naming
		if (!name.startsWith("/")) {
			name = "/" + name;
		}
		URL url = TinySound.class.getResource(name);
		//check for failure to find resource
		if (url == null) {
			System.err.println("Unable to find resource " + name + "!");
			return null;
		}
		return TinySound.loadMusic(url);
	}
	
	/**
	 * Load a Music by a File.
	 * @param file the Music file to load
	 * @return Music from file as specified, null if not found/loaded
	 */
	public static Music loadMusic(File file) {
		//check if the system is initialized
		if (!TinySound.inited) {
			System.err.println("TinySound not initialized!");
			return null;
		}
		//check for failure
		if (file == null) {
			return null;
		}
		URL url = null;
		try {
			url = file.toURI().toURL();
		} catch (MalformedURLException e) {
			System.err.println("Unable to find file " + file + "!");
			return null;
		}
		return TinySound.loadMusic(url);
	}
	
	/**
	 * Load a Music by a URL.
	 * @param url the URL of the Music
	 * @return Music from URL as specified, null if not found/loaded
	 */
	public static Music loadMusic(URL url) {
		//check if the system is initialized
		if (!TinySound.inited) {
			System.err.println("TinySound not initialized!");
			return null;
		}
		//check for failure
		if (url == null) {
			return null;
		}
		AudioInputStream audioStream = TinySound.getValidAudioStream(url);
		//check for failure
		if (audioStream == null) {
			return null;
		}
		//try to read all the bytes
		byte[][] data = TinySound.readAllBytes(audioStream);
		//check for failure
		if (data == null) {
			return null;
		}
		//construct the Music object and register it with the mixer
		return new Music(data[0], data[1], TinySound.mixer);
	}
	
	/**
	 * Load a Sound by a resource name.  The resource must be on the classpath
	 * for this to work.
	 * @param name name of the Sound resource
	 * @return Sound resource as specified, null if not found/loaded
	 */
	public static Sound loadSound(String name) {
		//check if the system is initialized
		if (!TinySound.inited) {
			System.err.println("TinySound not initialized!");
			return null;
		}
		//check for failure
		if (name == null) {
			return null;
		}
		//check for correct naming
		if (!name.startsWith("/")) {
			name = "/" + name;
		}
		URL url = TinySound.class.getResource(name);
		//check for failure to find resource
		if (url == null) {
			System.err.println("Unable to find resource " + name + "!");
			return null;
		}
		return TinySound.loadSound(url);

	}
	
	/**
	 * Load a Sound by a File.
	 * @param file the Sound file to load
	 * @return Sound from file as specified, null if not found/loaded
	 */
	public static Sound loadSound(File file) {
		//check if the system is initialized
		if (!TinySound.inited) {
			System.err.println("TinySound not initialized!");
			return null;
		}
		//check for failure
		if (file == null) {
			return null;
		}
		URL url = null;
		try {
			url = file.toURI().toURL();
		} catch (MalformedURLException e) {
			System.err.println("Unable to find file " + file + "!");
			return null;
		}
		return TinySound.loadSound(url);
	}
	
	/**
	 * Load a Sound by a URL.
	 * @param url the URL of the Sound
	 * @return Sound from URL as specified, null if not found/loaded
	 */
	public static Sound loadSound(URL url) {
		//check if the system is initialized
		if (!TinySound.inited) {
			System.err.println("TinySound not initialized!");
			return null;
		}
		//check for failure
		if (url == null) {
			return null;
		}
		AudioInputStream audioStream = TinySound.getValidAudioStream(url);
		//check for failure
		if (audioStream == null) {
			return null;
		}
		//try to read all the bytes
		byte[][] data = TinySound.readAllBytes(audioStream);
		//check for failure
		if (data == null) {
			return null;
		}
		//construct the Sound object
		return new Sound(data[0], data[1], TinySound.mixer);
	}
	
	/**
	 * Reads all of the bytes from an AudioInputStream.
	 * @param stream the stream to read
	 * @return all bytes from the stream, null if error
	 */
	private static byte[][] readAllBytes(AudioInputStream stream) {
		//left and right channels
		byte[][] data = null;
		int numChannels = stream.getFormat().getChannels();
		//handle 1-channel
		if (numChannels == 1) {
			byte[] left = TinySound.readAllBytesOneChannel(stream);
			//check failure
			if (left == null) {
				return null;
			}
			data = new byte[2][];
			data[0] = left;
			data[1] = left; //don't copy for the right channel
		} //handle 2-channel
		else if (numChannels == 2) {
			data = TinySound.readAllBytesTwoChannel(stream);
		}
		else { //wtf?
			System.err.println("Unable to read " + numChannels + " channels!");
		}
		return data;
	}
	
	/**
	 * Reads all of the bytes from a 1-channel AudioInputStream.
	 * @param stream the stream to read
	 * @return all bytes from the stream, null if error
	 */
	private static byte[] readAllBytesOneChannel(AudioInputStream stream) {
		//read all the bytes (assuming 1-channel)
		byte[] data = null;
		try {
			data = TinySound.getBytes(stream);
		}
		catch (IOException e) {
			System.err.println("Error reading all bytes from stream!");
			return null;
		}
		finally {
			try { stream.close(); } catch (IOException e) {}
		}
		return data;
	}
	
	/**
	 * Reads all of the bytes from a 2-channel AudioInputStream.
	 * @param stream the stream to read
	 * @return all bytes from the stream, null if error
	 */
	private static byte[][] readAllBytesTwoChannel(AudioInputStream stream) {
		//read all the bytes (assuming 16-bit, 2-channel)
		byte[][] data = null;
		try {
			byte[] allBytes = TinySound.getBytes(stream);
			byte[] left = new byte[allBytes.length / 2];
			byte[] right = new byte[allBytes.length / 2];
			for (int i = 0, j = 0; i < allBytes.length; i += 4, j += 2) {
				//interleaved left then right
				left[j] = allBytes[i];
				left[j + 1] = allBytes[i + 1];
				right[j] = allBytes[i + 2];
				right[j + 1] = allBytes[i + 3];
			}
			data = new byte[2][];
			data[0] = left;
			data[1] = right;
		}
		catch (IOException e) {
			System.err.println("Error reading all bytes from stream!");
			return null;
		}
		finally {
			try { stream.close(); } catch (IOException e) {}
		}
		return data;
	}
	
	/**
	 * Gets and AudioInputStream in the TinySound system format.
	 * @param url URL of the resource
	 * @return the specified stream as an AudioInputStream stream, null if
	 * failure
	 */
	private static AudioInputStream getValidAudioStream(URL url) {
		AudioInputStream audioStream = null;
		try {
			audioStream = AudioSystem.getAudioInputStream(url);
			AudioFormat streamFormat = audioStream.getFormat();
			//1-channel can also be treated as stereo
			AudioFormat mono16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
					44100, 16, 1, 2, 44100, false);
			//1 or 2 channel 8-bit may be easy to convert
			AudioFormat mono8 =	new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
					44100, 8, 1, 1, 44100, false);
			AudioFormat stereo8 =
				new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100, 8, 2, 2,
					44100, false);
			//now check formats (attempt conversion as needed)
			if (streamFormat.matches(TinySound.FORMAT) ||
					streamFormat.matches(mono16)) {
				return audioStream;
			} //check conversion to TinySound format
			else if (AudioSystem.isConversionSupported(TinySound.FORMAT,
					streamFormat)) {
				audioStream = AudioSystem.getAudioInputStream(TinySound.FORMAT,
						audioStream);
			} //check conversion to mono alternate
			else if (AudioSystem.isConversionSupported(mono16, streamFormat)) {
				audioStream = AudioSystem.getAudioInputStream(mono16,
						audioStream);
			} //try convert from 8-bit, 2-channel
			else if (streamFormat.matches(stereo8) ||
					AudioSystem.isConversionSupported(stereo8, streamFormat)) {
				//convert to 8-bit stereo first?
				if (!streamFormat.matches(stereo8)) {
					audioStream = AudioSystem.getAudioInputStream(stereo8,
							audioStream);
				}
				audioStream = TinySound.convertStereo8Bit(audioStream);
			} //try convert from 8-bit, 1-channel
			else if (streamFormat.matches(mono8) ||
					AudioSystem.isConversionSupported(mono8, streamFormat)) {
				//convert to 8-bit mono first?
				if (!streamFormat.matches(mono8)) {
					audioStream = AudioSystem.getAudioInputStream(mono8,
							audioStream);
				}
				audioStream = TinySound.convertMono8Bit(audioStream);
			} //it's time to give up
			else {
				System.err.println("Unable to convert audio resource!");
				System.err.println(url);
				System.err.println(streamFormat);
				audioStream.close();
				return null;
			}
			//check the frame length
			long frameLength = audioStream.getFrameLength();
			//too long
			if (frameLength > Integer.MAX_VALUE) {
				System.err.println("Audio resource too long!");
				return null;
			}
		}
		catch (UnsupportedAudioFileException e) {
			System.err.println("Unsupported audio resource!\n" +
					e.getMessage());
			return null;
		}
		catch (IOException e) {
			System.err.println("Error getting resource stream!\n" +
					e.getMessage());
			return null;
		}
		return audioStream;
	}
	
	/**
	 * Converts an 8-bit, signed, 1-channel AudioInputStream to 16-bit, signed,
	 * 1-channel.
	 * @param stream stream to convert
	 * @return converted stream
	 */
	private static AudioInputStream convertMono8Bit(AudioInputStream stream) {
		//assuming 8-bit, 1-channel to 16-bit, 1-channel
		byte[] newData = null;
		try {
			byte[] data = TinySound.getBytes(stream);
			int newNumBytes = data.length * 2;
			//check if size overflowed
			if (newNumBytes < 0) {
				System.err.println("Audio resource too long!");
				return null;
			}
			newData = new byte[newNumBytes];
			//convert bytes one-by-one to int, and then to 16-bit
			for (int i = 0, j = 0; i < data.length; i++, j += 2) {
				//convert it to a double
				double floatVal = (double)data[i];
				floatVal /= (floatVal < 0) ? 128 : 127;
				if (floatVal < -1.0) { //just in case
					floatVal = -1.0;
				}
				else if (floatVal > 1.0) {
					floatVal = 1.0;
				}
				//convert it to an int and then to 2 bytes
				int val = (int)(floatVal * Short.MAX_VALUE);
				newData[j + 1] = (byte)((val >> 8) & 0xFF); //MSB
				newData[j] = (byte)(val & 0xFF); //LSB
			}
		}
		catch (IOException e) {
			System.err.println("Error reading all bytes from stream!");
			return null;
		}
		finally {
			try { stream.close(); } catch (IOException e) {}
		}
		AudioFormat mono16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
				44100, 16, 1, 2, 44100, false);
		return new AudioInputStream(new ByteArrayInputStream(newData), mono16,
				newData.length / 2);
	}
	
	/**
	 * Converts an 8-bit, signed, 2-channel AudioInputStream to 16-bit, signed,
	 * 2-channel.
	 * @param stream stream to convert
	 * @return converted stream
	 */
	private static AudioInputStream convertStereo8Bit(AudioInputStream stream) {
		//assuming 8-bit, 2-channel to 16-bit, 2-channel
		byte[] newData = null;
		try {
			byte[] data = TinySound.getBytes(stream);
			int newNumBytes = data.length * 2 * 2;
			//check if size overflowed
			if (newNumBytes < 0) {
				System.err.println("Audio resource too long!");
				return null;
			}
			newData = new byte[newNumBytes];
			for (int i = 0, j = 0; i < data.length; i += 2, j += 4) {
				//convert them to doubles
				double leftFloatVal = (double)data[i];
				double rightFloatVal = (double)data[i + 1];
				leftFloatVal /= (leftFloatVal < 0) ? 128 : 127;
				rightFloatVal /= (rightFloatVal < 0) ? 128 : 127;
				if (leftFloatVal < -1.0) { //just in case
					leftFloatVal = -1.0;
				}
				else if (leftFloatVal > 1.0) {
					leftFloatVal = 1.0;
				}
				if (rightFloatVal < -1.0) { //just in case
					rightFloatVal = -1.0;
				}
				else if (rightFloatVal > 1.0) {
					rightFloatVal = 1.0;
				}
				//convert them to ints and then to 2 bytes each
				int leftVal = (int)(leftFloatVal * Short.MAX_VALUE);
				int rightVal = (int)(rightFloatVal * Short.MAX_VALUE);
				//left channel bytes
				newData[j + 1] = (byte)((leftVal >> 8) & 0xFF); //MSB
				newData[j] = (byte)(leftVal & 0xFF); //LSB
				//then right channel bytes
				newData[j + 3] = (byte)((rightVal >> 8) & 0xFF); //MSB
				newData[j + 2] = (byte)(rightVal & 0xFF); //LSB
			}
		}
		catch (IOException e) {
			System.err.println("Error reading all bytes from stream!");
			return null;
		}
		finally {
			try { stream.close(); } catch (IOException e) {}
		}
		AudioFormat stereo16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
				44100, 16, 2, 4, 44100, false);
		return new AudioInputStream(new ByteArrayInputStream(newData), stereo16,
				newData.length / 4);
	}
	
	/**
	 * Read all of the bytes from an AudioInputStream.
	 * @param stream the stream from which to read bytes
	 * @return all bytes read from the AudioInputStream
	 * @throws IOException 
	 */
	private static byte[] getBytes(AudioInputStream stream)
			throws IOException {
		//buffer 1-sec at a time
		int bufSize = (int)TinySound.FORMAT.getSampleRate() *
			TinySound.FORMAT.getChannels() * TinySound.FORMAT.getFrameSize();
		byte[] buf = new byte[bufSize];
		ByteList list = new ByteList(bufSize);
		int numRead = 0;
		while ((numRead = stream.read(buf)) > -1)  {
			for (int i = 0; i < numRead; i++) {
				list.add(buf[i]);
			}
		}
		return list.asArray();
	}
	
	/**
	 * Iterates through available JavaSound Mixers looking for one that can
	 * provide a line to the speakers.
	 * @return an opened SourceDataLine to the speakers
	 */
	private static SourceDataLine tryGetLine() {
		//first build our line info and get all available mixers
		DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class,
				TinySound.FORMAT);
		javax.sound.sampled.Mixer.Info[] mixerInfos =
			AudioSystem.getMixerInfo();
		//iterate through the mixers trying to find a line
		for (int i = 0; i < mixerInfos.length; i++) {
			javax.sound.sampled.Mixer mixer = null;
			try {
				//first try to actually get the mixer
				mixer = AudioSystem.getMixer(mixerInfos[i]);
			}
			catch (SecurityException e) {
				//not much we can do here
			}
			catch (IllegalArgumentException e) {
				//this should never happen since we were told the mixer exists
			}
			//check if we got a mixer and our line is supported
			if (mixer == null || !mixer.isLineSupported(lineInfo)) {
				continue;
			}
			//see if we can actually get a line
			SourceDataLine line = null;
			try {
				line = (SourceDataLine)mixer.getLine(lineInfo);
				//don't try to open if already open
				if (!line.isOpen()) {
					line.open(TinySound.FORMAT);
				}
			}
			catch (LineUnavailableException e) {
				//we either failed to get or open
				//should we do anything here?
			}
			catch (SecurityException e) {
				//not much we can do here
			}
			//check if we succeeded
			if (line != null && line.isOpen()) {			
				return line;
			}
		}
		//no good
		return null;
	}
	
}
