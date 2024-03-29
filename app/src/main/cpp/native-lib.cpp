/*
Java native interface to C++ decoder

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#include <jni.h>
#define assert(expr)
#include "crsec.hh"
#include "decoder.hh"

static CauchyReedSolomonErasureCoding *crsec;
static Interface *decoder;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_assempix_MainActivity_createCRSEC(
	JNIEnv *,
	jobject) {
	if (crsec == nullptr)
		crsec = new(std::nothrow) CauchyReedSolomonErasureCoding();
	return crsec != nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_assempix_MainActivity_chunkCRSEC(
	JNIEnv *env,
	jobject,
	jbyteArray JNI_payload,
	jint JNI_blockIndex,
	jint JNI_blockIdent) {
	jboolean status = false;
	if (decoder) {
		jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
		if (payload)
			status = crsec->chunk(reinterpret_cast<const uint8_t *>(payload), JNI_blockIndex, JNI_blockIdent);
		env->ReleaseByteArrayElements(JNI_payload, payload,JNI_ABORT);
	}
	return status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aicodix_assempix_MainActivity_recoverCRSEC(
	JNIEnv *env,
	jobject,
	jbyteArray JNI_payload,
	jint JNI_blockCount) {
	jlong status = -1;
	if (decoder) {
		jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
		if (payload)
			status = crsec->recover(reinterpret_cast<uint8_t *>(payload), env->GetArrayLength(JNI_payload), JNI_blockCount);
		env->ReleaseByteArrayElements(JNI_payload, payload,0);
	}
	return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_assempix_MainActivity_destroyDecoder(
	JNIEnv *,
	jobject) {
	delete decoder;
	decoder = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_assempix_MainActivity_createDecoder(
	JNIEnv *,
	jobject,
	jint sampleRate) {
	if (decoder && decoder->rate() == sampleRate)
		return true;
	delete decoder;
	switch (sampleRate) {
		case 8000:
			decoder = new(std::nothrow) Decoder<8000>();
			break;
		case 16000:
			decoder = new(std::nothrow) Decoder<16000>();
			break;
		case 32000:
			decoder = new(std::nothrow) Decoder<32000>();
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
	return decoder != nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aicodix_assempix_MainActivity_fetchDecoder(
	JNIEnv *env,
	jobject,
	jbyteArray JNI_payload) {
	jint status = -1;
	if (decoder) {
		jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
		if (payload)
			status = decoder->fetch(reinterpret_cast<uint8_t *>(payload));
		env->ReleaseByteArrayElements(JNI_payload, payload, 0);
	}
	return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_assempix_MainActivity_cachedDecoder(
	JNIEnv *env,
	jobject,
	jfloatArray JNI_carrierFrequencyOffset,
	jintArray JNI_operationMode,
	jbyteArray JNI_callSign) {

	if (!decoder)
		return;

	jint *operationMode;
	jfloat *carrierFrequencyOffset;
	jbyte *callSign;
	carrierFrequencyOffset = env->GetFloatArrayElements(JNI_carrierFrequencyOffset, nullptr);
	if (!carrierFrequencyOffset)
		goto carrierFrequencyOffsetFail;
	operationMode = env->GetIntArrayElements(JNI_operationMode, nullptr);
	if (!operationMode)
		goto operationModeFail;
	callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
	if (!callSign)
		goto callSignFail;

	decoder->cached(
		reinterpret_cast<float *>(carrierFrequencyOffset),
		reinterpret_cast<int32_t *>(operationMode),
		reinterpret_cast<int8_t *>(callSign));

	env->ReleaseByteArrayElements(JNI_callSign, callSign, 0);
	callSignFail:
	env->ReleaseIntArrayElements(JNI_operationMode, operationMode, 0);
	operationModeFail:
	env->ReleaseFloatArrayElements(JNI_carrierFrequencyOffset, carrierFrequencyOffset, 0);
	carrierFrequencyOffsetFail:;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aicodix_assempix_MainActivity_processDecoder(
	JNIEnv *env,
	jobject,
	jintArray JNI_spectrumPixels,
	jintArray JNI_spectrogramPixels,
	jintArray JNI_constellationPixels,
	jintArray JNI_peakMeterPixels,
	jshortArray JNI_audioBuffer,
	jint channelSelect,
	jint colorTint) {

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
		reinterpret_cast<int16_t *>(audioBuffer),
		channelSelect, colorTint);

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
