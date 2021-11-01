/*
Java native interface to C++ decoder

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#include <jni.h>
#include "decoder.hh"

static Interface *decoder;

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_assempix_MainActivity_destroyDecoder(
	JNIEnv *,
	jobject) {
	delete decoder;
	decoder = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_assempix_MainActivity_createDecoder(
	JNIEnv *,
	jobject,
	jint sampleRate) {
	if (decoder && decoder->rate() == sampleRate)
		return;
	delete decoder;
	switch (sampleRate) {
		case 8000:
			decoder = new(std::nothrow) Decoder<8000>();
			break;
		case 44100:
			decoder = new(std::nothrow) Decoder<44100>();
			break;
		case 48000:
			decoder = new(std::nothrow) Decoder<48000>();
			break;
		default:
			decoder = nullptr;
	}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_assempix_MainActivity_fetchDecoder(
	JNIEnv *env,
	jobject,
	jbyteArray JNI_payload) {
	jboolean status = false;
	if (decoder) {
		jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
		if (payload)
			status = decoder->fetch(reinterpret_cast<uint8_t *>(payload));
		env->ReleaseByteArrayElements(JNI_payload, payload, 0);
	}
	return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_assempix_MainActivity_syncedDecoder(
	JNIEnv *env,
	jobject,
	jintArray JNI_symbolTimingOffset,
	jfloatArray JNI_carrierFrequencyOffset,
	jintArray JNI_operationMode,
	jbyteArray JNI_callSign) {

	if (!decoder)
		return;

	jint *symbolTimingOffset, *operationMode;
	jfloat *carrierFrequencyOffset;
	jbyte *callSign;
	symbolTimingOffset = env->GetIntArrayElements(JNI_symbolTimingOffset, nullptr);
	if (!symbolTimingOffset)
		goto symbolTimingOffsetFail;
	carrierFrequencyOffset = env->GetFloatArrayElements(JNI_carrierFrequencyOffset, nullptr);
	if (!carrierFrequencyOffset)
		goto carrierFrequencyOffsetFail;
	operationMode = env->GetIntArrayElements(JNI_operationMode, nullptr);
	if (!operationMode)
		goto operationModeFail;
	callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
	if (!callSign)
		goto callSignFail;

	decoder->synced(
		reinterpret_cast<int32_t *>(symbolTimingOffset),
		reinterpret_cast<float *>(carrierFrequencyOffset),
		reinterpret_cast<int32_t *>(operationMode),
		reinterpret_cast<int8_t *>(callSign));

	env->ReleaseByteArrayElements(JNI_callSign, callSign, 0);
	callSignFail:
	env->ReleaseIntArrayElements(JNI_operationMode, operationMode, 0);
	operationModeFail:
	env->ReleaseFloatArrayElements(JNI_carrierFrequencyOffset, carrierFrequencyOffset, 0);
	carrierFrequencyOffsetFail:
	env->ReleaseIntArrayElements(JNI_symbolTimingOffset, symbolTimingOffset, 0);
	symbolTimingOffsetFail:;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aicodix_assempix_MainActivity_processDecoder(
	JNIEnv *env,
	jobject,
	jintArray JNI_spectrumPixels,
	jintArray JNI_spectrogramPixels,
	jintArray JNI_constellationPixels,
	jintArray JNI_peakMeterPixels,
	jshortArray JNI_audioBuffer) {

	jint status = STATUS_HEAP;

	if (!decoder)
		return status;

	jint *spectrumPixels, *spectrogramPixels, *constellationPixels, *peakMeterPixels;
	jshort *audioBuffer;
	spectrumPixels = env->GetIntArrayElements(JNI_spectrumPixels, nullptr);
	if (!spectrumPixels)
		goto spectrumFail;
	spectrogramPixels = env->GetIntArrayElements(JNI_spectrogramPixels, nullptr);
	if (!spectrogramPixels)
		goto spectrogramFail;
	constellationPixels = env->GetIntArrayElements(JNI_constellationPixels, nullptr);
	if (!constellationPixels)
		goto constellationFail;
	peakMeterPixels = env->GetIntArrayElements(JNI_peakMeterPixels, nullptr);
	if (!peakMeterPixels)
		goto peakMeterFail;
	audioBuffer = env->GetShortArrayElements(JNI_audioBuffer, nullptr);
	if (!audioBuffer)
		goto audioBufferFail;

	status = decoder->process(
		reinterpret_cast<uint32_t *>(spectrumPixels),
		reinterpret_cast<uint32_t *>(spectrogramPixels),
		reinterpret_cast<uint32_t *>(constellationPixels),
		reinterpret_cast<uint32_t *>(peakMeterPixels),
		reinterpret_cast<int16_t *>(audioBuffer));

	env->ReleaseShortArrayElements(JNI_audioBuffer, audioBuffer, JNI_ABORT);
	audioBufferFail:
	env->ReleaseIntArrayElements(JNI_peakMeterPixels, peakMeterPixels, 0);
	peakMeterFail:
	env->ReleaseIntArrayElements(JNI_constellationPixels, constellationPixels, 0);
	constellationFail:
	env->ReleaseIntArrayElements(JNI_spectrogramPixels, spectrogramPixels, 0);
	spectrogramFail:
	env->ReleaseIntArrayElements(JNI_spectrumPixels, spectrumPixels, 0);
	spectrumFail:

	return status;
}