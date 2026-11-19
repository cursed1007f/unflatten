#pragma once
// sha256.h — реализация SHA-256 (RFC 6234), без внешних зависимостей.
// Нужна для хеширования HWID и контрольных подписей.
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace lic {

class Sha256 {
public:
    static const size_t DIGEST_SIZE = 32;

    Sha256() { reset(); }

    void reset() {
        state_[0] = 0x6a09e667; state_[1] = 0xbb67ae85;
        state_[2] = 0x3c6ef372; state_[3] = 0xa54ff53a;
        state_[4] = 0x510e527f; state_[5] = 0x9b05688c;
        state_[6] = 0x1f83d9ab; state_[7] = 0x5be0cd19;
        len_ = 0; bufLen_ = 0;
    }

    void update(const void* data, size_t n) {
        const uint8_t* p = static_cast<const uint8_t*>(data);
        len_ += n;
        while (n > 0) {
            size_t take = 64 - bufLen_;
            if (take > n) take = n;
            std::memcpy(buf_ + bufLen_, p, take);
            bufLen_ += take;
            p += take;
            n -= take;
            if (bufLen_ == 64) { transform(); bufLen_ = 0; }
        }
    }

    void update(const std::string& s) { update(s.data(), s.size()); }
    void update(const char c) { update(&c, 1); }
    void update(const std::vector<uint8_t>& v) { update(v.data(), v.size()); }

    std::vector<uint8_t> digest() {
        // финализация: копируем состояние в отдельный экземпляр
        Sha256 tmp = *this;
        return tmp.finish();
    }

    std::string hex() {
        auto d = digest();
        static const char* hexc = "0123456789abcdef";
        std::string out;
        out.reserve(d.size() * 2);
        for (uint8_t b : d) { out += hexc[(b >> 4) & 0xf]; out += hexc[b & 0xf]; }
        return out;
    }

    // Однострочный хелпер.
    static std::string hexOf(const std::string& input) {
        Sha256 s; s.update(input); return s.hex();
    }

private:
    uint32_t state_[8];
    uint64_t len_;      // общее число байт (не блоков)
    uint8_t buf_[64];
    size_t bufLen_;
    static constexpr uint32_t K[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    };

    static inline uint32_t rotr(uint32_t x, unsigned n) { return (x >> n) | (x << (32 - n)); }

    void transform() {
        uint32_t w[64];
        for (unsigned i = 0; i < 16; ++i) {
            w[i] = ((uint32_t)buf_[i*4] << 24) | ((uint32_t)buf_[i*4+1] << 16)
                 | ((uint32_t)buf_[i*4+2] << 8)  | ((uint32_t)buf_[i*4+3]);
        }
        for (unsigned i = 16; i < 64; ++i) {
            uint32_t s0 = rotr(w[i-15], 7) ^ rotr(w[i-15], 18) ^ (w[i-15] >> 3);
            uint32_t s1 = rotr(w[i-2], 17) ^ rotr(w[i-2], 19) ^ (w[i-2] >> 10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }
        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (unsigned i = 0; i < 64; ++i) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t t1 = h + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            h = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
        state_[4] += e; state_[5] += f; state_[6] += g; state_[7] += h;
    }

    std::vector<uint8_t> finish() {
        uint64_t bitLen = len_ * 8;
        update("\x80", 1);
        while (bufLen_ != 56) update("\x00", 1);
        uint8_t lenBytes[8];
        for (unsigned i = 0; i < 8; ++i) lenBytes[i] = (uint8_t)(bitLen >> (56 - i*8));
        update(lenBytes, 8);
        std::vector<uint8_t> out(DIGEST_SIZE);
        for (unsigned i = 0; i < 8; ++i) {
            out[i*4]   = (uint8_t)(state_[i] >> 24);
            out[i*4+1] = (uint8_t)(state_[i] >> 16);
            out[i*4+2] = (uint8_t)(state_[i] >> 8);
            out[i*4+3] = (uint8_t)(state_[i]);
        }
        return out;
    }

    };

} // namespace lic