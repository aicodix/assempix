/*
Decoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

package com.aicodix.assempix;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.ShareActionProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuItemCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.aicodix.assempix.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
	private int audioSource;
	private short[] audioBuffer;
	private ActivityMainBinding binding;
	private Menu menu;
	private final int spectrumWidth = 640, spectrumHeight = 64;
	private final int spectrogramWidth = 640, spectrogramHeight = 256;
	private final int constellationWidth = 64, constellationHeight = 64;
	private final int peakMeterWidth = 16, peakMeterHeight = 1;
	private Bitmap spectrumBitmap, spectrogramBitmap, constellationBitmap, peakMeterBitmap;
	private int[] spectrumPixels, spectrogramPixels, constellationPixels, peakMeterPixels;
	private int[] symbolTimingOffset, operationMode;
	private float[] carrierFrequencyOffset;
	private byte[] callSign;
	private byte[] payload;
	private String callTrim;

	private native int processDecoder(int[] spectrumPixels, int[] spectrogramPixels, int[] constellationPixels, int[] peakMeterPixels, short[] audioBuffer);

	private native void syncedDecoder(int[] symbolTimingOffset, float[] carrierFrequencyOffset, int[] operationMode, byte[] callSign);

	private native boolean fetchDecoder(byte[] payload);

	private native void createDecoder(int sampleRate);

	private native void destroyDecoder();

	private final AudioRecord.OnRecordPositionUpdateListener audioListener = new AudioRecord.OnRecordPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioRecord ignore) {

		}

		@Override
		public void onPeriodicNotification(AudioRecord audioRecord) {
			audioRecord.read(audioBuffer, 0, audioBuffer.length);
			int status = processDecoder(spectrumPixels, spectrogramPixels, constellationPixels, peakMeterPixels, audioBuffer);
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
					binding.message.setText(getString(R.string.preamble_fail));
					break;
				case STATUS_NOPE:
					binding.message.setText(getString(R.string.preamble_nope));
					break;
				case STATUS_HEAP:
					binding.message.setText(getString(R.string.heap_error));
					break;
				case STATUS_SYNC:
					syncedDecoder(symbolTimingOffset, carrierFrequencyOffset, operationMode, callSign);
					callTrim = new String(callSign).trim();
					binding.message.setText(getString(R.string.preamble_sync, symbolTimingOffset[0], carrierFrequencyOffset[0], operationMode[0], callTrim));
					break;
				case STATUS_DONE:
					if (fetchDecoder(payload))
						decodePayload();
					else
						binding.message.setText(getString(R.string.decoding_failed));
					break;
			}
		}
	};

	private void storePayload(String mime, String suffix, Date date) {
		String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date);
		String title = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date);
		name += "_" + callTrim.replace(' ', '_') + suffix;
		title += " " + callTrim;
		ContentValues values = new ContentValues();
		File dir;
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
			dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
			if (!dir.exists() && !dir.mkdirs()) {
				binding.message.setText(R.string.creating_picture_directory_failed);
				return;
			}
			File file;
			try {
				file = new File(dir, name);
				FileOutputStream stream = new FileOutputStream(file);
				stream.write(payload);
				stream.close();
			} catch (IOException e) {
				binding.message.setText(R.string.creating_picture_file_failed);
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
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
			try {
				ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "w");
				FileOutputStream stream = new FileOutputStream(descriptor.getFileDescriptor());
				stream.write(payload);
				stream.close();
			} catch (IOException e) {
				binding.message.setText(R.string.storing_picture_failed);
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
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(payload, 0, payload.length, opt);
		if (opt.outMimeType == null) {
			binding.message.setText(getString(R.string.payload_unknown));
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
				binding.message.setText(getString(R.string.payload_unknown));
				return;
		}
		if (opt.outWidth < 16 || opt.outWidth > 1024 || opt.outHeight < 16 || opt.outHeight > 1024) {
			binding.message.setText(getString(R.string.payload_unknown));
			return;
		}
		Bitmap bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length);
		if (bitmap == null) {
			binding.message.setText(getString(R.string.decoding_failed));
			return;
		}
		binding.image.setImageBitmap(bitmap);
		Date date = new Date();
		String hour = new SimpleDateFormat("HH:mm:ss", Locale.US).format(date);
		setTitle(hour + " - " + callTrim + " - " + type);
		storePayload(opt.outMimeType, suffix, date);
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

	private void startListening() {
		if (audioRecord != null) {
			audioRecord.startRecording();
			if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				audioRecord.read(audioBuffer, 0, audioBuffer.length);
				binding.message.setText(getString(R.string.audio_recording_config, sampleRate, getAudioSourceString(audioSource)));
			} else {
				binding.message.setText(getString(R.string.audio_recording_error));
			}
		}
	}

	private void stopListening() {
		if (audioRecord != null) {
			audioRecord.stop();
			binding.message.setText(getString(R.string.audio_recording_paused));
		}
	}

	private void initAudioRecord(boolean restart) {
		if (audioRecord != null) {
			boolean rateChanged = audioRecord.getSampleRate() != sampleRate;
			boolean sourceChanged = audioRecord.getAudioSource() != audioSource;
			if (!rateChanged && !sourceChanged)
				return;
			stopListening();
			audioRecord.release();
			audioRecord = null;
		}
		int channelConfig = AudioFormat.CHANNEL_IN_MONO;
		int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
		int sampleSize = 2;
		int bufferSize = 2 * Integer.highestOneBit(3 * sampleRate);
		int symbolLength = (1280 * sampleRate) / 8000;
		int guardLength = symbolLength / 8;
		int extendedLength = symbolLength + guardLength;
		try {
			AudioRecord testAudioRecord = new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize * sampleSize);
			if (testAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
				audioRecord = testAudioRecord;
				audioBuffer = new short[extendedLength];
				createDecoder(sampleRate);
				audioRecord.setRecordPositionUpdateListener(audioListener);
				audioRecord.setPositionNotificationPeriod(extendedLength);
				if (restart)
					startListening();
			} else {
				testAudioRecord.release();
				binding.message.setText(getString(R.string.audio_init_failed));
			}
		} catch (IllegalArgumentException e) {
			binding.message.setText(getString(R.string.audio_setup_failed));
		} catch (SecurityException e) {
			binding.message.setText(getString(R.string.audio_permission_denied));
		}
	}

	private void setSampleRate(int newSampleRate) {
		if (sampleRate == newSampleRate)
			return;
		sampleRate = newSampleRate;
		updateSampleRateMenu();
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
		state.putInt("audioSource", audioSource);
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onCreate(Bundle state) {
		final int defaultSampleRate = 8000;
		final int defaultAudioSource = MediaRecorder.AudioSource.DEFAULT;
		if (state == null) {
			SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
			AppCompatDelegate.setDefaultNightMode(pref.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			sampleRate = pref.getInt("sampleRate", defaultSampleRate);
			audioSource = pref.getInt("audioSource", defaultAudioSource);
		} else {
			AppCompatDelegate.setDefaultNightMode(state.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			sampleRate = state.getInt("sampleRate", defaultSampleRate);
			audioSource = state.getInt("audioSource", defaultAudioSource);
		}
		super.onCreate(state);
		binding = ActivityMainBinding.inflate(getLayoutInflater());
		changeLayoutOrientation(getResources().getConfiguration());
		setContentView(binding.getRoot());
		ColorStateList tint = ContextCompat.getColorStateList(this, R.color.tint);
		binding.spectrum.setImageTintList(tint);
		binding.constellation.setImageTintList(tint);
		binding.spectrum.setImageTintMode(PorterDuff.Mode.SRC_IN);
		binding.constellation.setImageTintMode(PorterDuff.Mode.SRC_IN);
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
		symbolTimingOffset = new int[1];
		carrierFrequencyOffset = new float[1];
		operationMode = new int[1];
		callSign = new byte[9];
		payload = new byte[5380];

		List<String> permissions = new ArrayList<>();
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.RECORD_AUDIO);
			binding.message.setText(getString(R.string.audio_permission_denied));
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
			case 44100:
				menu.findItem(R.id.action_set_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_rate_48000).setChecked(true);
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
		updateAudioSourceMenu();
		return true;
	}

	private void storeSettings() {
		SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor edit = pref.edit();
		edit.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		edit.putInt("sampleRate", sampleRate);
		edit.putInt("audioSource", audioSource);
		edit.apply();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_set_rate_8000) {
			setSampleRate(8000);
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
			showTextPage(getString(R.string.about), getString(R.string.about_text));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showTextPage(String title, String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
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