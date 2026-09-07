#pragma once

// Engine lifecycle: mnn_sd_initialize_sessions / mnn_sd_release_sessions.
//
// 旧 mnn_session.cpp の extern "C" 初期化・解放ブロックをここへ移動した。

#include "mnn_sd/engine.h"
#include "mnn_sd/types.h"

struct MnnSdEngine;

namespace mnn_sd_detail
{

    MnnSdError initialize_sessions(MnnSdEngine *engine, MnnSdErrorInfo *out_error);
    void release_sessions(MnnSdEngine *engine);

} // namespace mnn_sd_detail
