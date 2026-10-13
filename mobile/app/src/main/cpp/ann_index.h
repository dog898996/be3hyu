#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#elif defined(__SSE2__)
#include <emmintrin.h>
#endif
// Upstream SSE prefetch reads one label past a full upper-layer link list (ASan found
// this during a real build). Disable that optional upstream path, keeping our bounded
// SSE2/NEON quantized dot product above. Vendored upstream files stay unmodified.
#ifndef NO_MANUAL_VECTORIZATION
#define NO_MANUAL_VECTORIZATION
#endif
#include "third_party/hnswlib/hnswlib.h"

namespace hoyoung {
/** Rebuildable approximate index; source text and canonical float vectors remain in SQLite.
 * Int8 unit-vector rounding loses precision. A stored inverse quantized norm restores cosine scale. */
class QuantizedCosineSpace final : public hnswlib::SpaceInterface<float> {
    size_t dimension_;
    static float distance(const void* a, const void* b, const void* parameter) {
        const size_t dimension = *static_cast<const size_t*>(parameter);
        const auto* left = static_cast<const int8_t*>(a);
        const auto* right = static_cast<const int8_t*>(b);
        int32_t dot = 0;
        size_t i = 0;
#if defined(__ARM_NEON)
        int32x4_t sum = vdupq_n_s32(0);
        for (; i + 16 <= dimension; i += 16) {
            int8x16_t x = vld1q_s8(left + i), y = vld1q_s8(right + i);
            sum = vpadalq_s16(sum, vmull_s8(vget_low_s8(x), vget_low_s8(y)));
            sum = vpadalq_s16(sum, vmull_s8(vget_high_s8(x), vget_high_s8(y)));
        }
        dot = vaddvq_s32(sum);
#elif defined(__SSE2__)
        __m128i sum = _mm_setzero_si128(), zero = _mm_setzero_si128();
        for (; i + 16 <= dimension; i += 16) {
            __m128i x = _mm_loadu_si128(reinterpret_cast<const __m128i*>(left + i));
            __m128i y = _mm_loadu_si128(reinterpret_cast<const __m128i*>(right + i));
            __m128i xs = _mm_cmpgt_epi8(zero, x), ys = _mm_cmpgt_epi8(zero, y);
            sum = _mm_add_epi32(sum, _mm_madd_epi16(_mm_unpacklo_epi8(x, xs), _mm_unpacklo_epi8(y, ys)));
            sum = _mm_add_epi32(sum, _mm_madd_epi16(_mm_unpackhi_epi8(x, xs), _mm_unpackhi_epi8(y, ys)));
        }
        alignas(16) int32_t values[4]; _mm_store_si128(reinterpret_cast<__m128i*>(values), sum);
        dot = values[0] + values[1] + values[2] + values[3];
#endif
        for (; i < dimension; i++) dot += static_cast<int32_t>(left[i]) * right[i];
        float inverseLeft, inverseRight;
        std::memcpy(&inverseLeft, left + dimension, sizeof(float));
        std::memcpy(&inverseRight, right + dimension, sizeof(float));
        float cosine = static_cast<float>(dot) * inverseLeft * inverseRight;
        return 1.0f - std::max(-1.0f, std::min(1.0f, cosine));
    }
public:
    explicit QuantizedCosineSpace(size_t dimension) : dimension_(dimension) {
        if (dimension < 1 || dimension > 4096) throw std::invalid_argument("ANN dimension outside 1..4096");
    }
    // HNSW's level-0 header is 4 mod 8 bytes. Padding makes its following uint64 label
    // naturally aligned even in small-dimensional regression tests; production uses 512.
    size_t get_data_size() override { return ((dimension_ + 7) / 8) * 8 + sizeof(float); }
    hnswlib::DISTFUNC<float> get_dist_func() override { return distance; }
    void* get_dist_func_param() override { return &dimension_; }
    std::vector<int8_t> quantize(const float* vector, size_t length) const {
        if (length != dimension_) throw std::invalid_argument("ANN dimension mismatch");
        double norm = 0;
        for (size_t i = 0; i < dimension_; i++) {
            if (!std::isfinite(vector[i])) throw std::invalid_argument("Nonfinite ANN vector");
            norm += static_cast<double>(vector[i]) * vector[i];
        }
        if (!(norm > 1e-24) || !std::isfinite(norm)) throw std::invalid_argument("Zero or invalid ANN vector");
        const double scale = 127.0 / std::sqrt(norm);
        std::vector<int8_t> encoded(((dimension_ + 7) / 8) * 8 + sizeof(float),0);
        int32_t square = 0;
        for (size_t i = 0; i < dimension_; i++) {
            int value = static_cast<int>(std::round(vector[i] * scale));
            value = std::max(-127, std::min(127, value));
            encoded[i] = static_cast<int8_t>(value); square += value * value;
        }
        if (square == 0) throw std::invalid_argument("Quantized ANN vector is zero");
        float inverse = 1.0f / std::sqrt(static_cast<float>(square));
        std::memcpy(encoded.data() + dimension_, &inverse, sizeof(float));
        return encoded;
    }
    float similarity(const float* a, const float* b) const {
        auto x = quantize(a, dimension_), y = quantize(b, dimension_);
        return 1.0f - distance(x.data(), y.data(), &dimension_);
    }
};

class ExcludedLabels final : public hnswlib::BaseFilterFunctor {
    const std::unordered_set<hnswlib::labeltype>& denied_;
public:
    explicit ExcludedLabels(const std::unordered_set<hnswlib::labeltype>& denied) : denied_(denied) {}
    bool operator()(hnswlib::labeltype label) override { return denied_.find(label) == denied_.end(); }
};

struct AnnHit { uint64_t label; float similarity; };

class AnnIndex {
    QuantizedCosineSpace space_;
    std::unique_ptr<hnswlib::HierarchicalNSW<float>> graph_;
    size_t dimension_;
    std::unordered_set<hnswlib::labeltype> denied_;
public:
    static constexpr size_t MAX_ELEMENTS = 2'000'000;
    AnnIndex(size_t dimension, size_t capacity, size_t m = 16, size_t efConstruction = 200)
        : space_(dimension), dimension_(dimension) {
        if (capacity < 1 || capacity > MAX_ELEMENTS || m < 4 || m > 64 || efConstruction < m || efConstruction > 2000)
            throw std::invalid_argument("Unsafe ANN construction parameters");
        graph_ = std::make_unique<hnswlib::HierarchicalNSW<float>>(&space_, capacity, m, efConstruction, 100, true);
    }
    size_t count() const { return graph_->getCurrentElementCount(); }
    size_t capacity() const { return graph_->getMaxElements(); }
    size_t dimension() const { return dimension_; }
    void reserve(size_t capacity) {
        if (capacity < count() || capacity > MAX_ELEMENTS) throw std::invalid_argument("Unsafe ANN capacity");
        if (capacity > this->capacity()) graph_->resizeIndex(capacity);
    }
    void add(uint64_t label, const float* vector, size_t length) {
        if (label == 0 || label > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) throw std::invalid_argument("ANN labels must be positive SQLite row IDs");
        auto encoded = space_.quantize(vector, length);
        graph_->addPoint(encoded.data(), static_cast<hnswlib::labeltype>(label));
    }
    void exclude(const std::vector<uint64_t>& labels) {
        denied_.clear(); denied_.reserve(labels.size());
        for (uint64_t label : labels) if (label != 0) denied_.insert(static_cast<hnswlib::labeltype>(label));
    }
    std::vector<AnnHit> search(const float* query, size_t length, size_t k, size_t ef) {
        if (count() == 0) return {};
        if (k < 1 || k > 4096 || ef < k || ef > 20000) throw std::invalid_argument("Unsafe ANN search parameters");
        auto encoded = space_.quantize(query, length);
        graph_->setEf(ef);
        ExcludedLabels filter(denied_);
        auto hits = graph_->searchKnn(encoded.data(), std::min(k, count()), denied_.empty() ? nullptr : &filter);
        std::vector<AnnHit> output(hits.size());
        for (size_t at = output.size(); at > 0; at--) {
            auto hit = hits.top(); hits.pop(); output[at - 1] = {hit.second, 1.0f - hit.first};
        }
        return output;
    }
    void save(const std::string& path) { graph_->saveIndex(path); }
    /** Raw hnswlib files are private derived caches, never accepted from a backup or public input.
     * The Java owner checks SHA, encoder identity and canonical fingerprint before this call.
     * Validate allocation/offset bounds before hnswlib's ABI-specific native deserializer. */
    void load(const std::string& path, size_t expectedCount) {
        std::ifstream input(path, std::ios::binary | std::ios::ate);
        if (!input) throw std::runtime_error("Cannot open ANN cache");
        auto fileSize = input.tellg(); input.seekg(0);
        size_t offsetLevel0, maximum, count, dataSize, labelOffset, dataOffset;
        int maximumLevel; uint32_t entryPoint;
        size_t maxM, maxM0, m; double multiplier; size_t construction;
        auto read = [&](auto& value) { input.read(reinterpret_cast<char*>(&value), sizeof(value)); if (!input) throw std::runtime_error("Truncated ANN cache header"); };
        read(offsetLevel0); read(maximum); read(count); read(dataSize); read(labelOffset); read(dataOffset);
        read(maximumLevel); read(entryPoint); read(maxM); read(maxM0); read(m); read(multiplier); read(construction);
        size_t headerSize = static_cast<size_t>(input.tellg());
        if (count != expectedCount || count > MAX_ELEMENTS || maximum < count || maximum > MAX_ELEMENTS || maximum > std::max<size_t>(1024,count+count/2)
            || offsetLevel0 != 0 || m < 4 || m > 64 || maxM != m || maxM0 != 2 * m
            || dataOffset != maxM0 * sizeof(uint32_t) + sizeof(uint32_t)
            || labelOffset != dataOffset + space_.get_data_size()
            || dataSize != labelOffset + sizeof(size_t) || maximumLevel < -1 || maximumLevel > 64
            || (count > 0 && entryPoint >= count) || !(multiplier > 0) || !std::isfinite(multiplier)
            || construction < m || construction > 2000 || fileSize < 0 || fileSize > static_cast<std::streamoff>(3ULL*1024*1024*1024)
            || static_cast<uint64_t>(fileSize) < headerSize + static_cast<uint64_t>(count) * dataSize)
            throw std::runtime_error("Invalid ANN cache dimensions or allocation bounds");
        // Strictly validate graph references and quantized norms before native code sees them.
        std::vector<char> record(dataSize);std::vector<uint64_t> labels;labels.reserve(count);
        for (size_t row = 0; row < count; row++) {
            input.read(record.data(), record.size()); if (!input) throw std::runtime_error("Truncated ANN cache data");
            uint16_t links; std::memcpy(&links, record.data(), sizeof(links));
            if (links > maxM0) throw std::runtime_error("Invalid ANN cache links");
            for (size_t i = 0; i < links; i++) { uint32_t link; std::memcpy(&link, record.data() + 4 + i * 4, sizeof(link)); if (link >= count) throw std::runtime_error("Invalid ANN cache link reference"); }
            float inverse; std::memcpy(&inverse, record.data() + dataOffset + dimension_, sizeof(float));
            int32_t square=0;for(size_t at=0;at<dimension_;at++){int8_t value=static_cast<int8_t>(record[dataOffset+at]);if(value==-128)throw std::runtime_error("Invalid ANN quantized coordinate");square+=static_cast<int32_t>(value)*value;}
            if (!(inverse > 0) || !std::isfinite(inverse) || square==0 || std::abs(inverse-1.0f/std::sqrt(static_cast<float>(square)))>1e-6f) throw std::runtime_error("Invalid ANN cache norm");
            uint64_t label; std::memcpy(&label, record.data() + labelOffset, sizeof(label));
            if (label == 0 || label > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) throw std::runtime_error("Invalid ANN cache label");
            labels.push_back(label);
        }
        std::sort(labels.begin(),labels.end());if(std::adjacent_find(labels.begin(),labels.end())!=labels.end())throw std::runtime_error("Duplicate ANN cache label");
        const size_t levelBytes = maxM * sizeof(uint32_t) + sizeof(uint32_t);
        std::vector<uint8_t> levels(count);std::vector<std::pair<uint32_t,uint8_t>> upperReferences;int actualMaximumLevel=-1;
        for (size_t row = 0; row < count; row++) {
            uint32_t bytes; read(bytes);
            if (bytes % levelBytes != 0 || bytes / levelBytes > 64) throw std::runtime_error("Invalid ANN upper layers");
            levels[row]=static_cast<uint8_t>(bytes/levelBytes);actualMaximumLevel=std::max(actualMaximumLevel,static_cast<int>(levels[row]));
            std::vector<char> layer(bytes); input.read(layer.data(), bytes); if (!input) throw std::runtime_error("Truncated ANN upper layers");
            for (size_t at = 0; at < bytes; at += levelBytes) {
                uint16_t links; std::memcpy(&links, layer.data() + at, sizeof(links));
                if (links > maxM) throw std::runtime_error("Invalid ANN upper links");
                for (size_t i = 0; i < links; i++) { uint32_t link; std::memcpy(&link, layer.data() + at + 4 + i * 4, sizeof(link)); if (link >= count) throw std::runtime_error("Invalid ANN upper link reference");upperReferences.emplace_back(link,static_cast<uint8_t>(at/levelBytes+1)); }
            }
        }
        if (input.tellg() != fileSize) throw std::runtime_error("Trailing ANN cache bytes");
        if(maximumLevel!=actualMaximumLevel||(count>0&&levels[entryPoint]!=maximumLevel))throw std::runtime_error("Invalid ANN entry-point layer");
        for(const auto& reference:upperReferences)if(levels[reference.first]<reference.second)throw std::runtime_error("ANN upper link references missing layer");
        graph_ = std::make_unique<hnswlib::HierarchicalNSW<float>>(&space_, path, false, maximum, true);
    }
};
} // namespace hoyoung
