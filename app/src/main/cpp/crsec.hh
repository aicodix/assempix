/*
Cauchy Reed Solomon Erasure Coding

Copyright 2024 Ahmet Inan <inan@aicodix.de>
*/

#pragma once

#include <cstring>
#include <algorithm>

#include "crc.hh"
#include "galois_field.hh"
#include "cauchy_reed_solomon_erasure_coding.hh"

class CauchyReedSolomonErasureCoding {
	typedef CODE::GaloisField<16, 0b10001000000001011, uint16_t> GaloisField;
#ifdef __AVX2__
	static const int SIMD = 32;
#else
	static const int SIMD = 16;
#endif
	GaloisField instance;
	CODE::CRC<uint32_t> crc32;
	CODE::CauchyReedSolomonErasureCoding<GaloisField> crs;
	alignas(SIMD) uint8_t chunk_mesg[5376], chunk_data[64512];
	uint16_t chunk_ident[12];
public:
	CauchyReedSolomonErasureCoding() : crc32(0x8F6E37A0) {}

	bool chunk(const uint8_t *payload, int idx, int ident) {
		chunk_ident[idx] = ident;
		std::memcpy(chunk_data + idx * 5376, payload + 14, 5366);
		return true;
	}

	long recover(uint8_t *payload, int size, int count) {
		int copy = (size + count - 1) / count;
		crc32.reset();
		for (int i = 0, j = 0; i < count; ++i) {
			crs.decode(chunk_mesg, chunk_data, chunk_ident, i, 5376, count);
			j += copy;
			if (j > size)
				copy -= j - size;
			std::memcpy(payload, chunk_mesg, copy);
			for (int k = 0; k < copy; ++k)
				crc32(chunk_mesg[k]);
			payload += copy;
		}
		return crc32();
	}
};
