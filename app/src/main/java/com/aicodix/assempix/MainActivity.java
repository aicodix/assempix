/*
Decoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

package com.aicodix.assempix;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.ShareActionProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuItemCompat;

import com.aicodix.assempix.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

	// Used to load the 'assempix' library on application startup.
	static {
		System.loadLibrary("assempix");
	}

	private final int permissionID = 1;
	private ShareActionProvider share;
	private AudioRecord audioRecord;
	private int sampleRate;
	private int channelSelect;
	private int audioSource;
	private int bitFlips;
	private int colorTint;
	private int currentBlockCount;
	private int currentImageBytes;
	private long currentImageCRC32;
	private long messageMillis;
	private short[] audioBuffer;
	private ActivityMainBinding binding;
	private Menu menu;
	private Handler handler;
	private final int spectrumWidth = 640, spectrumHeight = 64;
	private final int spectrogramWidth = 640, spectrogramHeight = 64;
	private final int constellationWidth = 64, constellationHeight = 64;
	private final int peakMeterWidth = 16, peakMeterHeight = 1;
	private Bitmap spectrumBitmap, spectrogramBitmap, constellationBitmap, peakMeterBitmap;
	private int[] spectrumPixels, spectrogramPixels, constellationPixels, peakMeterPixels;
	private int[] operationMode;
	private float[] carrierFrequencyOffset;
	private byte[] callSign;
	private byte[] payload;
	private String callTrim;
	private HashSet<Integer> identList;

	private native boolean createCRSEC();

	private native boolean chunkCRSEC(byte[] payload, int blockIndex, int blockIdent);

	private native long recoverCRSEC(byte[] payload, int blockCount);

	private native int processDecoder(int[] spectrumPixels, int[] spectrogramPixels, int[] constellationPixels, int[] peakMeterPixels, short[] audioBuffer, int channelSelect, int colorTint);

	private native void cachedDecoder(float[] carrierFrequencyOffset, int[] operationMode, byte[] callSign);

	private native int fetchDecoder(byte[] payload);

	private native boolean createDecoder(int sampleRate);

	private native void destroyDecoder();

	private final AudioRecord.OnRecordPositionUpdateListener audioListener = new AudioRecord.OnRecordPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioRecord ignore) {

		}

		@Override
		public void onPeriodicNotification(AudioRecord audioRecord) {
			audioRecord.read(audioBuffer, 0, audioBuffer.length);
			int status = processDecoder(spectrumPixels, spectrogramPixels, constellationPixels, peakMeterPixels, audioBuffer, channelSelect, colorTint);
			spectrumBitmap.setPixels(spectrumPixels, 0, spectrumWidth, 0, 0, spectrumWidth, spectrumHeight);
			spectrogramBitmap.setPixels(spectrogramPixels, 0, spectrogramWidth, 0, 0, spectrogramWidth, spectrogramHeight);
			constellationBitmap.setPixels(constellationPixels, 0, constellationWidth, 0, 0, constellationWidth, constellationHeight);
			peakMeterBitmap.setPixels(peakMeterPixels, 0, peakMeterWidth, 0, 0, peakMeterWidth, peakMeterHeight);
			binding.spectrum.invalidate();
			binding.spectrogram.invalidate();
			binding.constellation.invalidate();
			binding.peakMeter.invalidate();
			final int STATUS_OKAY = 0;
			final int STATUS_FAIL = 1;
			final int STATUS_SYNC = 2;
			final int STATUS_DONE = 3;
			final int STATUS_HEAP = 4;
			final int STATUS_NOPE = 5;
			switch (status) {
				case STATUS_OKAY:
					break;
				case STATUS_FAIL:
					statusMessage(R.string.preamble_fail);
					break;
				case STATUS_NOPE:
					float[] cfo = new float[1];
					int[] mode = new int[1];
					byte[] call = new byte[9];
					cachedDecoder(cfo, mode, call);
					String trim = new String(call).trim();
					String info = getString(mode[0] == 0 ? R.string.received_ping : R.string.preamble_nope);
					stringMessage(getString(R.string.status_message, cfo[0], modeString(mode[0]), trim, info));
					break;
				case STATUS_HEAP:
					stringMessage(getString(R.string.heap_error));
					break;
				case STATUS_SYNC:
					cachedDecoder(carrierFrequencyOffset, operationMode, callSign);
					callTrim = new String(callSign).trim();
					statusMessage(R.string.preamble_sync);
					break;
				case STATUS_DONE:
					bitFlips = fetchDecoder(payload);
					if (bitFlips >= 0)
						decodePayload();
					else
						statusMessage(R.string.decoding_failed);
					break;
			}
		}
	};

	private String modeString(int mode) {
		if (mode >= 0 && mode <= 13)
			return getResources().getStringArray(R.array.operation_modes)[mode];
		return getString(R.string.mode_unsupported, mode);
	}

	private void stringMessage(String text) {
		long currentMillis = SystemClock.elapsedRealtime();
		long difference = currentMillis - messageMillis;
		long interval = 3000;
		if (difference < interval) {
			messageMillis += interval;
			long delayMillis = messageMillis - currentMillis;
			handler.postDelayed(() -> binding.message.setText(text), delayMillis);
		} else {
			messageMillis = currentMillis;
			binding.message.setText(text);
		}
	}

	private void statusMessage(int status) {
		String statMsg;
		if (status == R.string.image_received)
			statMsg = getString(status, bitFlips);
		else if (status == R.string.chunk_received)
			statMsg = getString(status, identList.size(), currentBlockCount);
		else
			statMsg = getString(status);
		if (callTrim != null)
			stringMessage(getString(R.string.status_message, carrierFrequencyOffset[0], modeString(operationMode[0]), callTrim, statMsg));
		else
			stringMessage(getString(status));
	}

	private void storeImage(byte[] data, String mime, String suffix, Date date) {
		String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date);
		String title = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date);
		name += "_" + callTrim.replace(' ', '_') + suffix;
		title += " " + callTrim;
		ContentValues values = new ContentValues();
		File dir;
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
			dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
			if (!dir.exists() && !dir.mkdirs()) {
				statusMessage(R.string.creating_picture_directory_failed);
				return;
			}
			File file;
			try {
				file = new File(dir, name);
				FileOutputStream stream = new FileOutputStream(file);
				stream.write(data);
				stream.close();
			} catch (IOException e) {
				statusMessage(R.string.creating_picture_file_failed);
				return;
			}
			values.put(MediaStore.Images.ImageColumns.DATA, file.toString());
		} else {
			values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
			values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/");
			values.put(MediaStore.Images.Media.IS_PENDING, 1);
		}
		values.put(MediaStore.Images.ImageColumns.TITLE, title);
		values.put(MediaStore.Images.ImageColumns.MIME_TYPE, mime);
		ContentResolver resolver = getContentResolver();
		Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
		if (uri == null) {
			statusMessage(R.string.storing_picture_failed);
			return;
		}
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
			try {
				ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "w");
				if (descriptor == null) {
					statusMessage(R.string.storing_picture_failed);
					return;
				}
				FileOutputStream stream = new FileOutputStream(descriptor.getFileDescriptor());
				stream.write(data);
				stream.close();
				descriptor.close();
			} catch (IOException e) {
				statusMessage(R.string.storing_picture_failed);
				return;
			}
			values.clear();
			values.put(MediaStore.Images.Media.IS_PENDING, 0);
			resolver.update(uri, values, null, null);
		}
		Intent intent = new Intent(Intent.ACTION_SEND);
		intent.putExtra(Intent.EXTRA_STREAM, uri);
		intent.setType(mime);
		share.setShareIntent(intent);
		Toast toast = Toast.makeText(getApplicationContext(), name, Toast.LENGTH_LONG);
		toast.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL, 0, 0);
		toast.show();
	}

	private void decodePayload() {
		byte[] data = payload;
		if (payload[0] == 'C' && payload[1] == 'R' && payload[2] == 'S') {
			int overhead = 14;
			int chunksMax = 12;
			int availBytes = 5380;
			int bytesMax = (availBytes - overhead) * chunksMax;
			int blockCount = ((payload[4] & 255) << 8) + (payload[3] & 255) + 1;
			int blockIdent = ((payload[6] & 255) << 8) + (payload[5] & 255);
			int imageBytes = ((payload[9] & 255) << 16) + ((payload[8] & 255) << 8) + (payload[7] & 255) + 1;
			long imageCRC32 = ((payload[13] & 255L) << 24) + ((payload[12] & 255L) << 16) + ((payload[11] & 255L) << 8) + (payload[10] & 255L);
			if (blockCount > chunksMax || blockIdent < blockCount || imageBytes > bytesMax) {
				statusMessage(R.string.chunk_unsupported);
				return;
			}
			if (currentBlockCount != blockCount || currentImageBytes != imageBytes || currentImageCRC32 != imageCRC32) {
				identList = new HashSet<>();
				currentBlockCount = blockCount;
				currentImageBytes = imageBytes;
				currentImageCRC32 = imageCRC32;
			}
			if (identList.contains(blockIdent)) {
				statusMessage(R.string.chunk_duplicate);
				return;
			}
			if (identList.size() == blockCount) {
				statusMessage(R.string.chunk_redundant);
				return;
			}
			if (!chunkCRSEC(payload, identList.size(), blockIdent)) {
				statusMessage(R.string.heap_error);
				currentBlockCount = 0;
				currentImageBytes = 0;
				currentImageCRC32 = 0;
				return;
			}
			identList.add(blockIdent);
			statusMessage(R.string.chunk_received);
			if (identList.size() < blockCount) {
				return;
			}
			data = new byte[currentImageBytes];
			if (currentImageCRC32 != recoverCRSEC(data, identList.size())) {
				statusMessage(R.string.chunk_corrupted);
				currentBlockCount = 0;
				currentImageBytes = 0;
				currentImageCRC32 = 0;
				return;
			}
		}
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, opt);
		if (opt.outMimeType == null) {
			statusMessage(R.string.payload_unknown);
			return;
		}
		String suffix;
		String type;
		switch (opt.outMimeType) {
			case "image/jpeg":
				suffix = ".jpg";
				type = "JPEG";
				break;
			case "image/png":
				suffix = ".png";
				type = "PNG";
				break;
			case "image/webp":
				suffix = ".webp";
				type = "WebP";
				break;
			default:
				statusMessage(R.string.payload_unknown);
				return;
		}
		if (opt.outWidth < 16 || opt.outWidth > 1024 || opt.outHeight < 16 || opt.outHeight > 1024) {
			statusMessage(R.string.payload_unknown);
			return;
		}
		Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
		if (bitmap == null) {
			statusMessage(R.string.decoding_failed);
			return;
		}
		statusMessage(R.string.image_received);
		binding.image.setImageBitmap(bitmap);
		Date date = new Date();
		String hour = new SimpleDateFormat("HH:mm:ss", Locale.US).format(date);
		setTitle(hour + " - " + callTrim + " - " + type);
		storeImage(data, opt.outMimeType, suffix, date);
	}

	private String getAudioSourceString(int audioSource) {
		switch (audioSource) {
			case MediaRecorder.AudioSource.DEFAULT:
				return getString(R.string.source_default);
			case MediaRecorder.AudioSource.MIC:
				return getString(R.string.source_microphone);
			case MediaRecorder.AudioSource.CAMCORDER:
				return getString(R.string.source_camcorder);
			case MediaRecorder.AudioSource.VOICE_RECOGNITION:
				return getString(R.string.source_voice_recognition);
			case MediaRecorder.AudioSource.UNPROCESSED:
				return getString(R.string.source_unprocessed);
		}
		return "";
	}

	private String getChannelSelectString(int channelSelect) {
		switch (channelSelect) {
			case 0:
				return getString(R.string.channel_default);
			case 1:
				return getString(R.string.channel_first);
			case 2:
				return getString(R.string.channel_second);
			case 3:
				return getString(R.string.channel_summation);
			case 4:
				return getString(R.string.channel_analytic);
		}
		return "";
	}

	private void startListening() {
		if (audioRecord != null) {
			audioRecord.startRecording();
			if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				audioRecord.read(audioBuffer, 0, audioBuffer.length);
				stringMessage(getString(R.string.audio_recording_config, sampleRate, getChannelSelectString(channelSelect), getAudioSourceString(audioSource)));
			} else {
				stringMessage(getString(R.string.audio_recording_error));
			}
		}
	}

	private void stopListening() {
		if (audioRecord != null) {
			audioRecord.stop();
			stringMessage(getString(R.string.audio_recording_paused));
		}
	}

	private void initAudioRecord(boolean restart) {
		if (audioRecord != null) {
			boolean rateChanged = audioRecord.getSampleRate() != sampleRate;
			boolean channelChanged = audioRecord.getChannelCount() != (channelSelect == 0 ? 1 : 2);
			boolean sourceChanged = audioRecord.getAudioSource() != audioSource;
			if (!rateChanged && !channelChanged && !sourceChanged)
				return;
			stopListening();
			audioRecord.release();
			audioRecord = null;
		}
		int channelConfig = AudioFormat.CHANNEL_IN_MONO;
		int channelCount = 1;
		if (channelSelect != 0) {
			channelCount = 2;
			channelConfig = AudioFormat.CHANNEL_IN_STEREO;
		}
		int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
		int sampleSize = 2;
		int frameSize = sampleSize * channelCount;
		int bufferSize = 2 * Integer.highestOneBit(3 * sampleRate) * frameSize;
		int symbolLength = (1280 * sampleRate) / 8000;
		int guardLength = symbolLength / 8;
		int extendedLength = symbolLength + guardLength;
		try {
			AudioRecord testAudioRecord = new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize);
			if (testAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
				if (createDecoder(sampleRate)) {
					audioRecord = testAudioRecord;
					audioBuffer = new short[extendedLength * channelCount];
					audioRecord.setRecordPositionUpdateListener(audioListener);
					audioRecord.setPositionNotificationPeriod(extendedLength);
					if (restart)
						startListening();
				} else {
					testAudioRecord.release();
					stringMessage(getString(R.string.heap_error));
				}
			} else {
				testAudioRecord.release();
				stringMessage(getString(R.string.audio_init_failed));
			}
		} catch (IllegalArgumentException e) {
			stringMessage(getString(R.string.audio_setup_failed));
		} catch (SecurityException e) {
			stringMessage(getString(R.string.audio_permission_denied));
		}
	}

	private void setSampleRate(int newSampleRate) {
		if (sampleRate == newSampleRate)
			return;
		sampleRate = newSampleRate;
		updateSampleRateMenu();
		initAudioRecord(true);
	}

	private void setChannelSelect(int newChannelSelect) {
		if (channelSelect == newChannelSelect)
			return;
		channelSelect = newChannelSelect;
		updateChannelSelectMenu();
		initAudioRecord(true);
	}

	private void setAudioSource(int newAudioSource) {
		if (audioSource == newAudioSource)
			return;
		audioSource = newAudioSource;
		updateAudioSourceMenu();
		initAudioRecord(true);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != permissionID)
			return;
		for (int i = 0; i < permissions.length; ++i)
			if (permissions[i].equals(Manifest.permission.RECORD_AUDIO) && grantResults[i] == PackageManager.PERMISSION_GRANTED)
				initAudioRecord(false);
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration config) {
		super.onConfigurationChanged(config);
		changeLayoutOrientation(config);
	}

	private void changeLayoutOrientation(@NonNull Configuration config) {
		if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
			binding.content.setOrientation(LinearLayout.HORIZONTAL);
		else
			binding.content.setOrientation(LinearLayout.VERTICAL);
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		state.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		state.putInt("sampleRate", sampleRate);
		state.putInt("channelSelect", channelSelect);
		state.putInt("audioSource", audioSource);
		super.onSaveInstanceState(state);
	}

	private void storeSettings() {
		SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor edit = pref.edit();
		edit.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		edit.putInt("sampleRate", sampleRate);
		edit.putInt("channelSelect", channelSelect);
		edit.putInt("audioSource", audioSource);
		edit.apply();
	}

	@Override
	protected void onCreate(Bundle state) {
		final int defaultSampleRate = 8000;
		final int defaultChannelSelect = 0;
		final int defaultAudioSource = MediaRecorder.AudioSource.DEFAULT;
		if (state == null) {
			SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
			AppCompatDelegate.setDefaultNightMode(pref.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			sampleRate = pref.getInt("sampleRate", defaultSampleRate);
			channelSelect = pref.getInt("channelSelect", defaultChannelSelect);
			audioSource = pref.getInt("audioSource", defaultAudioSource);
		} else {
			AppCompatDelegate.setDefaultNightMode(state.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			sampleRate = state.getInt("sampleRate", defaultSampleRate);
			channelSelect = state.getInt("channelSelect", defaultChannelSelect);
			audioSource = state.getInt("audioSource", defaultAudioSource);
		}
		super.onCreate(state);
		handler = new Handler(getMainLooper());
		binding = ActivityMainBinding.inflate(getLayoutInflater());
		changeLayoutOrientation(getResources().getConfiguration());
		setContentView(binding.getRoot());
		colorTint = ContextCompat.getColor(this, R.color.tint);
		spectrumBitmap = Bitmap.createBitmap(spectrumWidth, spectrumHeight, Bitmap.Config.ARGB_8888);
		spectrogramBitmap = Bitmap.createBitmap(spectrogramWidth, spectrogramHeight, Bitmap.Config.ARGB_8888);
		constellationBitmap = Bitmap.createBitmap(constellationWidth, constellationHeight, Bitmap.Config.ARGB_8888);
		peakMeterBitmap = Bitmap.createBitmap(peakMeterWidth, peakMeterHeight, Bitmap.Config.ARGB_8888);
		binding.spectrum.setImageBitmap(spectrumBitmap);
		binding.spectrogram.setImageBitmap(spectrogramBitmap);
		binding.constellation.setImageBitmap(constellationBitmap);
		binding.peakMeter.setImageBitmap(peakMeterBitmap);
		binding.constellation.setScaleType(ImageView.ScaleType.FIT_CENTER);
		binding.spectrum.setScaleType(ImageView.ScaleType.FIT_XY);
		binding.spectrogram.setScaleType(ImageView.ScaleType.FIT_XY);
		binding.peakMeter.setScaleType(ImageView.ScaleType.FIT_XY);
		constellationPixels = new int[constellationWidth * constellationHeight];
		spectrumPixels = new int[spectrumWidth * spectrumHeight];
		spectrogramPixels = new int[spectrogramWidth * spectrogramHeight];
		peakMeterPixels = new int[peakMeterWidth * peakMeterHeight];
		carrierFrequencyOffset = new float[1];
		operationMode = new int[1];
		callSign = new byte[9];
		payload = new byte[5380];
		if (!createCRSEC())
			stringMessage(getString(R.string.heap_error));

		List<String> permissions = new ArrayList<>();
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.RECORD_AUDIO);
			stringMessage(getString(R.string.audio_permission_denied));
		} else {
			initAudioRecord(false);
		}
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
			permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
		if (!permissions.isEmpty())
			ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), permissionID);
	}

	private void updateSampleRateMenu() {
		switch (sampleRate) {
			case 8000:
				menu.findItem(R.id.action_set_rate_8000).setChecked(true);
				break;
			case 16000:
				menu.findItem(R.id.action_set_rate_16000).setChecked(true);
				break;
			case 32000:
				menu.findItem(R.id.action_set_rate_32000).setChecked(true);
				break;
			case 44100:
				menu.findItem(R.id.action_set_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_rate_48000).setChecked(true);
				break;
		}
	}

	private void updateChannelSelectMenu() {
		switch (channelSelect) {
			case 0:
				menu.findItem(R.id.action_set_channel_default).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_channel_first).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_channel_second).setChecked(true);
				break;
			case 3:
				menu.findItem(R.id.action_set_channel_summation).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_channel_analytic).setChecked(true);
				break;
		}
	}

	private void updateAudioSourceMenu() {
		switch (audioSource) {
			case MediaRecorder.AudioSource.DEFAULT:
				menu.findItem(R.id.action_set_source_default).setChecked(true);
				break;
			case MediaRecorder.AudioSource.MIC:
				menu.findItem(R.id.action_set_source_microphone).setChecked(true);
				break;
			case MediaRecorder.AudioSource.CAMCORDER:
				menu.findItem(R.id.action_set_source_camcorder).setChecked(true);
				break;
			case MediaRecorder.AudioSource.VOICE_RECOGNITION:
				menu.findItem(R.id.action_set_source_voice_recognition).setChecked(true);
				break;
			case MediaRecorder.AudioSource.UNPROCESSED:
				menu.findItem(R.id.action_set_source_unprocessed).setChecked(true);
				break;
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		menu.findItem(R.id.action_set_source_unprocessed).setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
		share = (ShareActionProvider) MenuItemCompat.getActionProvider(menu.findItem(R.id.menu_item_share));
		this.menu = menu;
		updateSampleRateMenu();
		updateChannelSelectMenu();
		updateAudioSourceMenu();
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_set_rate_8000) {
			setSampleRate(8000);
			return true;
		}
		if (id == R.id.action_set_rate_16000) {
			setSampleRate(16000);
			return true;
		}
		if (id == R.id.action_set_rate_32000) {
			setSampleRate(32000);
			return true;
		}
		if (id == R.id.action_set_rate_44100) {
			setSampleRate(44100);
			return true;
		}
		if (id == R.id.action_set_rate_48000) {
			setSampleRate(48000);
			return true;
		}
		if (id == R.id.action_set_channel_default) {
			setChannelSelect(0);
			return true;
		}
		if (id == R.id.action_set_channel_first) {
			setChannelSelect(1);
			return true;
		}
		if (id == R.id.action_set_channel_second) {
			setChannelSelect(2);
			return true;
		}
		if (id == R.id.action_set_channel_summation) {
			setChannelSelect(3);
			return true;
		}
		if (id == R.id.action_set_channel_analytic) {
			setChannelSelect(4);
			return true;
		}
		if (id == R.id.action_set_source_default) {
			setAudioSource(MediaRecorder.AudioSource.DEFAULT);
			return true;
		}
		if (id == R.id.action_set_source_microphone) {
			setAudioSource(MediaRecorder.AudioSource.MIC);
			return true;
		}
		if (id == R.id.action_set_source_camcorder) {
			setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
			return true;
		}
		if (id == R.id.action_set_source_voice_recognition) {
			setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
			return true;
		}
		if (id == R.id.action_set_source_unprocessed) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				setAudioSource(MediaRecorder.AudioSource.UNPROCESSED);
				return true;
			}
			return false;
		}
		if (id == R.id.action_enable_night_mode) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
			return true;
		}
		if (id == R.id.action_disable_night_mode) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
			return true;
		}
		if (id == R.id.action_force_quit) {
			storeSettings();
			System.exit(0);
			return true;
		}
		if (id == R.id.action_privacy_policy) {
			showTextPage(getString(R.string.privacy_policy), getString(R.string.privacy_policy_text));
			return true;
		}
		if (id == R.id.action_about) {
			showTextPage(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showTextPage(String title, String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setNeutralButton(R.string.close, null);
		builder.setTitle(title);
		builder.setMessage(message);
		builder.show();
	}

	@Override
	protected void onResume() {
		startListening();
		super.onResume();
	}

	@Override
	protected void onPause() {
		stopListening();
		storeSettings();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		destroyDecoder();
		super.onDestroy();
	}
}
