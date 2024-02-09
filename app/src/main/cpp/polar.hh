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

template<typename code_type>
class Polar {
#ifdef __AVX2__
	typedef SIMD<code_type, 32 / sizeof(code_type)> mesg_type;
#else
	typedef SIMD<code_type, 16 / sizeof(code_type)> mesg_type;
#endif
	static const int data_bits = 43040;
	static const int crc_bits = data_bits + 32;
	CODE::CRC<uint32_t> crc;
	CODE::PolarEncoder<mesg_type> encode;
	CODE::PolarListDecoder<mesg_type, 16> decode;
	mesg_type mesg[44096], mess[65536];
	const uint32_t *frozen_bits;
	int code_order = 0;
	int cons_bits = 0;
	int mesg_bits = 0;

	void lengthen(code_type *code) {
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

	void prepare(int mode) {
		switch (mode) {
			case 6:
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 7:
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 8:
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 9:
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 10:
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 11:
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 12:
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 13:
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
		}
	}

public:
	Polar() : crc(0xD419CC15) {}

	int operator()(uint8_t *message, code_type *code, int operation_mode) {
		prepare(operation_mode);
		lengthen(code);
		decode(nullptr, mesg, code, frozen_bits, code_order);
		systematic();
		int best = -1;
		for (int k = 0; k < mesg_type::SIZE; ++k) {
			crc.reset();
			for (int i = 0; i < crc_bits; ++i)
				crc(mesg[i].v[k] < 0);
			if (crc() == 0) {
				best = k;
				break;
			}
		}
		if (best < 0)
			return -1;

		int flips = 0;
		for (int i = 0, j = 0; i < data_bits; ++i, ++j) {
			while ((frozen_bits[j / 32] >> (j % 32)) & 1)
				++j;
			bool received = code[j] < 0;
			bool decoded = mesg[i].v[best] < 0;
			flips += received != decoded;
			CODE::set_le_bit(message, i, decoded);
		}
		return flips;
	}
};
