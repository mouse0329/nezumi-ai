#pragma once

// MNN tensor I/O helpers (host mirror construction + f32/i32 transfer).
//
// 旧 mnn_session.cpp の Pipeline helpers セクションにあった
// get_session_input_tensor / get_session_output_tensor / fill_input_f32 /
// fill_input_i32 / read_output_f32 をここへ移動した。

#include <memory>
#include <vector>

#include "mnn_sd/types.h"

// MNN types are only used as pointers here; forward declarations from
// engine_internal.h's namespace block are sufficient. We repeat them so
// this header is self-contained.
namespace MNN
{
    class Interpreter;
    class Session;
    class Tensor;
} // namespace MNN

namespace mnn_sd_detail
{

    MNN::Tensor *get_session_input_tensor(MNN::Interpreter *net, MNN::Session *session, const char *name);
    MNN::Tensor *get_session_output_tensor(MNN::Interpreter *net, MNN::Session *session, const char *name);

    [[maybe_unused]]
    bool fill_input_f32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const float *data, size_t count);

    [[maybe_unused]]
    bool fill_input_i32(MNN::Interpreter *net, MNN::Session *session,
                        const char *name, const int *data, size_t count);

    // Copy output tensor to a float vector
    //
    // Bug fix (GPU で緑シミ + RGB ノイズが出る問題 / 2026-07):
    //   以前はスタック上に MNN::Tensor host(t, CAFFE) を作り copyToHostTensor する
    //   実装だったが、OpenCL 経路ではデバイステンソルからホストへの
    //   NC4HW4 -> NCHW 遅延変換がスタック host の寿命とタイミングが噛み合わず、
    //   変換が中途半端で完了するケースがあった。VAE 出力でこれが起きると
    //   緑シミ・粒状の RGB ノイズになる。UNet 出力は別経路で既にヒープに new した
    //   MNN::Tensor を使っているので問題が出ていない。ここも同じパターンに揃える。
    //   実装は clean-room: MNN 公式 API (Interpreter/Tensor/copyToHostTensor) のみを利用。
    std::vector<float> read_output_f32(MNN::Interpreter *net, MNN::Session *session, const char *name);

} // namespace mnn_sd_detail
