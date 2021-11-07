/*
CA-SCL polar decoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#pragma once

#include <cmath>
#include <iostream>
#include <algorithm>

#include "crc.hh"
#include "psk.hh"
#include "bitman.hh"
#include "complex.hh"
#include "polar_tables.hh"
#include "polar_helper.hh"
#include "polar_encoder.hh"
#include "polar_list_decoder.hh"

class Polar {
	typedef float code_type;
#ifdef __AVX2__
	typedef SIMD<code_type, 32 / sizeof(code_type)> mesg_type;
#else
	typedef SIMD<code_type, 16 / sizeof(code_type)> mesg_type;
#endif
	typedef DSP::Complex<float> cmplx;
	static const int mod_max = 3;
	static const int data_bits = 43040;
	static const int crc_bits = data_bits + 32;
	CODE::CRC<uint32_t> crc;
	CODE::PolarEncoder<mesg_type> encode;
	CODE::PolarListDecoder<mesg_type, 16> decode;
	mesg_type mesg[44096], mess[65536];
	code_type code[65536];
	const uint32_t *frozen_bits;
	int code_order = 0;
	int cons_bits = 0;
	int cons_cnt = 0;
	int mesg_bits = 0;
	int mod_bits = 0;

	void lengthen() {
		int code_bits = 1 << code_order;
		for (int i = code_bits - 1, j = cons_bits - 1, k = mesg_bits - 1; i >= 0; --i)
			if ((frozen_bits[i / 32] >> (i % 32)) & 1 || k-- < crc_bits)
				code[i] = code[j--];
			else
				code[i] = CODE::PolarHelper<code_type>::quant(9000);
	}

	void systematic() {
		encode(mess, mesg, frozen_bits, code_order);
		int code_bits = 1 << code_order;
		for (int i = 0, j = 0; i < code_bits && j < mesg_bits; ++i)
			if (!((frozen_bits[i / 32] >> (i % 32)) & 1))
				mesg[j++] = mess[i];
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

	void prepare(int mode) {
		switch (mode) {
			case 6:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 7:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 8:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 9:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 10:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 11:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 12:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 13:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
		}
		cons_cnt = cons_bits / mod_bits;
	}

	float precision(const cmplx *cons) {
		float sp = 0, np = 0;
		for (int i = 0; i < cons_cnt; ++i) {
			code_type tmp[mod_max];
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

public:
	Polar() : crc(0xD419CC15) {}

	bool operator()(uint8_t *message, const cmplx *cons, int operation_mode) {
		prepare(operation_mode);
		float prec = 1;
		if (std::is_integral<code_type>::value)
			prec = precision(cons);
		for (int i = 0; i < cons_cnt; ++i)
			mod_soft(code + mod_bits * i, cons[i], prec);
		lengthen();
		CODE::PolarHelper<mesg_type>::PATH metric[mesg_type::SIZE];
		decode(metric, mesg, code, frozen_bits, code_order);
		systematic();
		int order[mesg_type::SIZE];
		for (int k = 0; k < mesg_type::SIZE; ++k)
			order[k] = k;
		std::sort(order, order + mesg_type::SIZE, [metric](int a, int b) { return metric[a] < metric[b]; });
		int best = -1;
		for (int k = 0; k < mesg_type::SIZE; ++k) {
			crc.reset();
			for (int i = 0; i < crc_bits; ++i)
				crc(mesg[i].v[order[k]] < 0);
			if (crc() == 0) {
				best = order[k];
				break;
			}
		}
		if (best < 0)
			return false;

		for (int i = 0; i < data_bits; ++i)
			CODE::set_le_bit(message, i, mesg[i].v[best] < 0);
		return true;
	}
};
