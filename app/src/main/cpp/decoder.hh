/*
Decoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#pragma once

#include <cmath>
#include <iostream>
#include <algorithm>

namespace DSP { using std::abs; using std::min; using std::cos; using std::sin; }

#include "schmidl_cox.hh"
#include "bip_buffer.hh"
#include "theil_sen.hh"
#include "xorshift.hh"
#include "decibel.hh"
#include "complex.hh"
#include "hilbert.hh"
#include "blockdc.hh"
#include "bitman.hh"
#include "phasor.hh"
#include "const.hh"
#include "image.hh"
#include "polar.hh"
#include "fft.hh"
#include "mls.hh"
#include "crc.hh"
#include "osd.hh"
#include "psk.hh"

#define STATUS_OKAY 0
#define STATUS_FAIL 1
#define STATUS_SYNC 2
#define STATUS_DONE 3
#define STATUS_HEAP 4
#define STATUS_NOPE 5

struct Interface {
	virtual int process(uint32_t *, uint32_t *, uint32_t *, uint32_t *, const int16_t *, int) = 0;

	virtual void cached(float *, int32_t *, int8_t *) = 0;

	virtual int fetch(uint8_t *) = 0;

	virtual int rate() = 0;

	virtual ~Interface() = default;
};

template<int RATE>
class Decoder : public Interface {
	typedef DSP::Complex<float> cmplx;
	typedef DSP::Const<float> Const;
	typedef float code_type;
	static const int spectrum_width = 640, spectrum_height = 64;
	static const int spectrogram_width = 640, spectrogram_height = 256;
	static const int constellation_width = 64, constellation_height = 64;
	static const int peak_meter_width = 16;//, peak_meter_height = 1;
	static const int symbol_length = (1280 * RATE) / 8000;
	static const int guard_length = symbol_length / 8;
	static const int extended_length = symbol_length + guard_length;
	static const int filter_length = (((21 * RATE) / 8000) & ~3) | 1;
	static const int dB_min = -96, dB_max = 0;
	static const int carrier_count_max = 512;
	static const int data_bits = 43040;
	static const int cor_seq_len = 127;
	static const int cor_seq_off = 1 - cor_seq_len;
	static const int cor_seq_poly = 0b10001001;
	static const int pre_seq_len = 255;
	static const int pre_seq_off = -pre_seq_len / 2;
	static const int pre_seq_poly = 0b100101011;
	static const int buffer_length = 4 * extended_length;
	static const int search_position = extended_length;
	static const int mod_bits_max = 3;
	DSP::FastFourierTransform<symbol_length, cmplx, -1> fwd;
	SchmidlCox<float, cmplx, search_position, symbol_length / 2, guard_length> correlator;
	DSP::BlockDC<float, float> block_dc;
	DSP::Hilbert<cmplx, filter_length> hilbert;
	DSP::BipBuffer<cmplx, buffer_length> buffer;
	DSP::TheilSenEstimator<float, carrier_count_max> tse;
	DSP::Phasor<cmplx> osc;
	CODE::CRC<uint16_t> crc;
	CODE::OrderedStatisticsDecoder<255, 71, 2> osd;
	Polar<code_type> polar;
	cmplx temp[extended_length], freq[symbol_length], prev[carrier_count_max], cons[carrier_count_max];
	float power[spectrum_width]{}, index[carrier_count_max]{}, phase[carrier_count_max]{};
	code_type code[65536];
	int8_t generator[255 * 71];
	int8_t soft[pre_seq_len];
	uint8_t data[(pre_seq_len + 7) / 8];
	int prev_peak = 0;
	int carrier_count = 0;
	int symbol_count = 0;
	int symbol_number = 0;
	int carrier_offset = 0;
	int mod_bits = 0;
	int symbol_position = search_position + 2 * extended_length;
	int cached_mode = 0;
	int operation_mode = 0;
	uint64_t cached_call = 0;
	uint64_t call_sign = 0;

	static uint32_t argb(float a, float r, float g, float b) {
		a = std::clamp<float>(a, 0, 1);
		r = std::clamp<float>(r, 0, 1);
		g = std::clamp<float>(g, 0, 1);
		b = std::clamp<float>(b, 0, 1);
		r = std::sqrt(r);
		g = std::sqrt(g);
		b = std::sqrt(b);
		int A = (int) std::nearbyint(255 * a);
		int R = (int) std::nearbyint(255 * r);
		int G = (int) std::nearbyint(255 * g);
		int B = (int) std::nearbyint(255 * b);
		return (A << 24) | (R << 16) | (G << 8) | (B << 0);
	}

	static uint32_t rainbow(float v) {
		v = std::clamp<float>(v, 0, 1);
		float t = 4 * v - 2;
		return argb(4 * v, t, 1 - std::abs(t), -t);
	}

	static int bin(int carrier) {
		return (carrier + symbol_length) % symbol_length;
	}

	static int nrz(bool bit) {
		return 1 - 2 * bit;
	}

	static void base37(int8_t *str, uint64_t val, int len) {
		for (int i = len - 1; i >= 0; --i, val /= 37)
			str[i] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[val % 37];
	}

	static cmplx demod_or_erase(cmplx curr, cmplx prev) {
		if (norm(prev) <= 0)
			return 0;
		cmplx cons = curr / prev;
		if (norm(cons) > 4)
			return 0;
		return cons;
	}

	const cmplx *corSeq() {
		CODE::MLS seq(cor_seq_poly);
		for (int i = 0; i < symbol_length / 2; ++i)
			freq[i] = 0;
		for (int i = 0; i < cor_seq_len; ++i)
			freq[(i + cor_seq_off / 2 + symbol_length / 2) % (symbol_length / 2)] = nrz(seq());
		return freq;
	}

	void update_peak_meter(uint32_t *pixels, const int16_t *samples, int channel) {
		int peak = 0;
		switch (channel) {
			case 1:
			case 2:
				for (int i = channel - 1; i < 2 * extended_length; i += 2)
					peak = std::max(peak, std::abs((int) samples[i]));
				break;
			case 3:
			case 4:
				for (int i = 0; i < 2 * extended_length; ++i)
					peak = std::max(peak, std::abs((int) samples[i]));
				break;
			default:
				for (int i = 0; i < extended_length; ++i)
					peak = std::max(peak, std::abs((int) samples[i]));
		}
		int num = (peak * peak_meter_width + 16384) / 32768;
		int cnt = std::max(prev_peak, num);
		prev_peak = num;
		for (int i = 0; i < peak_meter_width; ++i) {
			uint32_t color = 0x20000000;
			if (i < cnt)
				color |= 0xff000000;
			if (i >= (peak_meter_width * 9) / 10)
				color |= 0x00ff0000;
			else if (i < peak_meter_width / 4)
				color |= 0x00ffff00;
			else
				color |= 0x0000ff00;
			pixels[i] = color;
		}
	}

	void update_spectrum(uint32_t *pixels) {
		Image<uint32_t, spectrum_width, spectrum_height> img(pixels);
		img.fill(0);
		auto pos = [this, img](int i) {
			return (int) std::nearbyint((1 - power[i]) * (img.height - 1));
		};
		for (int i = 1, j = pos(0), k; i < img.width; ++i, j = k)
			img.line(i - 1, j, i, k = pos(i), -1);
	}

	void update_spectrogram(uint32_t *pixels) {
		std::memmove(pixels + spectrogram_width, pixels, sizeof(uint32_t) * spectrogram_width * (spectrogram_height - 1));
		for (int i = 0; i < spectrogram_width; ++i)
			pixels[i] = rainbow(power[i]);
	}

	void update_constellation(uint32_t *pixels) {
		Image<uint32_t, constellation_width, constellation_height> img(pixels);
		img.fill(0);
		for (int i = 0; i < carrier_count; ++i) {
			float real = cons[i].real();
			float imag = cons[i].imag();
			if (real != 0 && imag != 0)
				img.set((real + 2) * img.width / 4, (imag + 2) * img.height / 4, -1);
		}
	}

	void update_oscilloscope(uint32_t *pixels) {
		Image<uint32_t, constellation_width, constellation_height> img(pixels);
		img.fill(0);
		for (int i = 0; i < extended_length; ++i)
			img.set((temp[i].real() + 1) * img.width / 2, (temp[i].imag() + 1) * img.height / 2, -1);
	}

	cmplx analytic(float real) {
		return hilbert(block_dc(real));
	}

	const cmplx *next_sample(const int16_t *samples, int channel, int i) {
		switch (channel) {
			case 1:
				return buffer(analytic(samples[2 * i] / 32768.f));
			case 2:
				return buffer(analytic(samples[2 * i + 1] / 32768.f));
			case 3:
				return buffer(analytic(((int)samples[2 * i] + (int)samples[2 * i + 1]) / 65536.f));
			case 4:
				return buffer(cmplx(samples[2 * i], samples[2 * i + 1]) / 32768.f);
		}
		return buffer(analytic(samples[i] / 32768.f));
	}

	cmplx mod_map(code_type *b) {
		switch (mod_bits) {
			case 2:
				return PhaseShiftKeying<4, cmplx, code_type>::map(b);
			case 3:
				return PhaseShiftKeying<8, cmplx, code_type>::map(b);
		}
		return 0;
	}

	void mod_hard(code_type *b, cmplx c) {
		switch (mod_bits) {
			case 2:
				PhaseShiftKeying<4, cmplx, code_type>::hard(b, c);
				break;
			case 3:
				PhaseShiftKeying<8, cmplx, code_type>::hard(b, c);
				break;
		}
	}

	void mod_soft(code_type *b, cmplx c, float precision) {
		switch (mod_bits) {
			case 2:
				PhaseShiftKeying<4, cmplx, code_type>::soft(b, c, precision);
				break;
			case 3:
				PhaseShiftKeying<8, cmplx, code_type>::soft(b, c, precision);
				break;
		}
	}

	void compensate() {
		int count = 0;
		for (int i = 0; i < carrier_count; ++i) {
			cmplx con = cons[i];
			if (con.real() != 0 && con.imag() != 0) {
				code_type tmp[mod_bits_max];
				mod_hard(tmp, con);
				index[count] = i + carrier_offset;
				phase[count] = arg(con * conj(mod_map(tmp)));
				++count;
			}
		}
		tse.compute(index, phase, count);
		for (int i = 0; i < carrier_count; ++i)
			cons[i] *= DSP::polar<float>(1, -tse(i + carrier_offset));
	}

	float precision() {
		float sp = 0, np = 0;
		for (int i = 0; i < carrier_count; ++i) {
			code_type tmp[mod_bits_max];
			mod_hard(tmp, cons[i]);
			cmplx hard = mod_map(tmp);
			cmplx error = cons[i] - hard;
			sp += norm(hard);
			np += norm(error);
		}
		// $LLR=log(\frac{p(x=+1|y)}{p(x=-1|y)})$
		// $p(x|\mu,\sigma)=\frac{1}{\sqrt{2\pi}\sigma}}e^{-\frac{(x-\mu)^2}{2\sigma^2}}$
		float sigma = std::sqrt(np / (2 * sp));
		return 1 / (sigma * sigma);
	}

	void demap() {
		float prec = precision();
		for (int i = 0; i < carrier_count; ++i)
			mod_soft(code + mod_bits * (symbol_number * carrier_count + i), cons[i], prec);
	}

	int preamble(const cmplx *buf) {
		DSP::Phasor<cmplx> nco;
		nco.omega(-correlator.cfo_rad);
		for (int i = 0; i < symbol_length; ++i)
			temp[i] = buf[correlator.symbol_pos + extended_length + i] * nco();
		fwd(freq, temp);
		CODE::MLS seq(pre_seq_poly);
		for (int i = 0; i < pre_seq_len; ++i)
			freq[bin(i + pre_seq_off)] *= nrz(seq());
		for (int i = 0; i < pre_seq_len; ++i)
			PhaseShiftKeying<2, cmplx, int8_t>::soft(soft + i, demod_or_erase(freq[bin(i + pre_seq_off)], freq[bin(i - 1 + pre_seq_off)]), 32);
		if (!osd(data, soft, generator))
			return STATUS_FAIL;
		uint64_t md = 0;
		for (int i = 0; i < 55; ++i)
			md |= (uint64_t) CODE::get_be_bit(data, i) << i;
		uint16_t cs = 0;
		for (int i = 0; i < 16; ++i)
			cs |= (uint16_t) CODE::get_be_bit(data, i + 55) << i;
		crc.reset();
		if (crc(md << 9) != cs)
			return STATUS_FAIL;
		cached_mode = md & 255;
		cached_call = md >> 8;
		if (cached_mode < 6 || cached_mode > 13)
			return STATUS_NOPE;
		if (cached_call == 0 || cached_call >= 129961739795077L) {
			cached_call = 0;
			return STATUS_NOPE;
		}
		operation_mode = cached_mode;
		call_sign = cached_call;
		return STATUS_OKAY;
	}

	void prepare() {
		switch (operation_mode) {
			case 6:
				carrier_count = 432;
				symbol_count = 50;
				mod_bits = 3;
				break;
			case 7:
				carrier_count = 400;
				symbol_count = 54;
				mod_bits = 3;
				break;
			case 8:
				carrier_count = 400;
				symbol_count = 81;
				mod_bits = 2;
				break;
			case 9:
				carrier_count = 360;
				symbol_count = 90;
				mod_bits = 2;
				break;
			case 10:
				carrier_count = 512;
				symbol_count = 42;
				mod_bits = 3;
				break;
			case 11:
				carrier_count = 384;
				symbol_count = 56;
				mod_bits = 3;
				break;
			case 12:
				carrier_count = 384;
				symbol_count = 84;
				mod_bits = 2;
				break;
			case 13:
				carrier_count = 256;
				symbol_count = 126;
				mod_bits = 2;
				break;
		}
		carrier_offset = -carrier_count / 2;
		symbol_number = 0;
	}

public:
	Decoder() : correlator(corSeq()), crc(0xA8F4) {
		CODE::BoseChaudhuriHocquenghemGenerator<255, 71>::matrix(generator, true, {
			0b100011101, 0b101110111, 0b111110011, 0b101101001,
			0b110111101, 0b111100111, 0b100101011, 0b111010111,
			0b000010011, 0b101100101, 0b110001011, 0b101100011,
			0b100011011, 0b100111111, 0b110001101, 0b100101101,
			0b101011111, 0b111111001, 0b111000011, 0b100111001,
			0b110101001, 0b000011111, 0b110000111, 0b110110001});
		block_dc.samples(2 * extended_length);
		osc.omega(-2000, RATE);
	}

	int rate() final {
		return RATE;
	}

	void cached(float *cfo, int32_t *mode, int8_t *call) final {
		*cfo = correlator.cfo_rad * (RATE / Const::TwoPi());
		*mode = cached_mode;
		base37(call, cached_call, 9);
	}

	int fetch(uint8_t *payload) final {
		int result = polar(payload, code, operation_mode);
		CODE::Xorshift32 scrambler;
		for (int i = 0; i < data_bits / 8; ++i)
			payload[i] ^= scrambler();
		return result;
	}

	int process(uint32_t *spectrum_pixels, uint32_t *spectrogram_pixels, uint32_t *constellation_pixels, uint32_t *peak_meter_pixels, const int16_t *audio_buffer, int channel_select) final {
		update_peak_meter(peak_meter_pixels, audio_buffer, channel_select);
		int status = STATUS_OKAY;
		const cmplx *buf;
		for (int i = 0; i < extended_length; ++i) {
			buf = next_sample(audio_buffer, channel_select, i);
			if (correlator(buf)) {
				status = preamble(buf);
				if (status == STATUS_OKAY) {
					osc.omega(-correlator.cfo_rad);
					symbol_position = correlator.symbol_pos + extended_length + i;
					prepare();
					status = STATUS_SYNC;
				}
			}
		}
		for (int i = 0; i < extended_length; ++i)
			temp[i] = buf[symbol_position + i] * osc();
		fwd(freq, temp);
		for (int i = 0; i < spectrum_width; ++i)
			power[i] = std::clamp<float>((DSP::decibel(norm(freq[bin(i - spectrum_width / 2)] / float(symbol_length))) - dB_min) / (dB_max - dB_min), 0, 1);
		update_spectrum(spectrum_pixels);
		update_spectrogram(spectrogram_pixels);
		if (status != STATUS_SYNC && symbol_number < symbol_count) {
			for (int i = 0; i < carrier_count; ++i)
				cons[i] = demod_or_erase(freq[bin(i + carrier_offset)], prev[i]);
			compensate();
			demap();
			update_constellation(constellation_pixels);
			if (++symbol_number == symbol_count)
				status = STATUS_DONE;
		} else {
			update_oscilloscope(constellation_pixels);
		}
		if (symbol_number < symbol_count)
			for (int i = 0; i < carrier_count; ++i)
				prev[i] = freq[bin(i + carrier_offset)];
		return status;
	}
};
