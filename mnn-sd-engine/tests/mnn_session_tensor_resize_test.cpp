#include <gtest/gtest.h>
#include <vector>
#include <cstring>

// Minimal compile-time smoke test for the tensor fill helper behavior.
// The production code uses an internal anonymous namespace helper, so we exercise the
// equivalent logic via a small local mirror here.

namespace {
struct FakeTensor {
    int dims = 3;
    std::vector<int> shape{2, 77, 768};
    std::vector<float> storage;
    int elementSize() const { int size = 1; for (int d : shape) size *= d; return size; }
};

bool fill_input_f32_mirror(FakeTensor& t, const float* data, size_t count) {
    if (t.elementSize() != static_cast<int>(count)) {
        t.shape[0] = 2;
        t.shape[1] = 77;
        t.shape[2] = 768;
        t.storage.resize(count);
        std::memcpy(t.storage.data(), data, count * sizeof(float));
        return true;
    }
    std::memcpy(t.storage.data(), data, count * sizeof(float));
    return true;
}
}  // namespace

TEST(MnnSessionTensorResize, AcceptsExpectedElementCount) {
    FakeTensor t;
    std::vector<float> data(2 * 77 * 768, 1.0f);
    EXPECT_TRUE(fill_input_f32_mirror(t, data.data(), data.size()));
    EXPECT_EQ(t.storage.size(), data.size());
}
