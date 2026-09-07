#include "engine_detail/sd_lifecycle.h"
#include "engine_detail/sd_log.h"
#include "engine_detail/sd_model_kind.h"
#include "engine_detail/sd_path.h"
#include "engine_detail/sd_schedulers.h"
#include "engine_detail/sd_session.h"
#include "engine_detail/sd_tensor_io.h"
#include "engine_internal.h"
#include "mnn_sd/model_config.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <random>
#include <string>
#include <thread>
#include <vector>

#if defined(MNN_SD_HAS_MNN)
#include <MNN/Interpreter.hpp>
#endif

using namespace mnn_sd_detail;

extern "C"
{

    MnnSdError mnn_sd_run_pipeline(
        MnnSdEngine *engine,
        const MnnSdGenerateParams *params,
        MnnSdProgressFn on_progress,
        void *progress_user_data,
        MnnSdImage *out_image,
        MnnSdErrorInfo *out_error)
    {
#if !defined(MNN_SD_HAS_MNN)
        (void)engine;
        (void)params;
        (void)on_progress;
        (void)progress_user_data;
        (void)out_image;
        if (out_error)
        {
            out_error->code = MNN_SD_ERR_BACKEND_INIT_FAILED;
            std::snprintf(out_error->message, sizeof(out_error->message), "MNN not linked");
        }
        return MNN_SD_ERR_BACKEND_INIT_FAILED;
#else
        // Bug fix (スケジューラ動作不具合):
        //   Kotlin/JNI からは 0..7 の 8 種類のスケジューラが飛んでくるが、
        //   engine 内部の denoise ループはまだ PLMS(PNDM) 固定である。この
        //   ため「スケジューラを指定しても切り替わらない」バグの実装レイヤーの
        //   根本原因は engine 側にある。少ステップ運用で LCM を選んでも
        //   結局 PLMS で走るのが分かるように、ここで明示的にログを残す。
        //   ユーザーの指定を尊重する。
        PROBE_LOG("mnn_sd_run_pipeline: requested scheduler=%d (0=Euler,1=DDIM,2=DPM,3=DPM++2M,4=DPM++2M-Karras,5=LCM,6=EulerA,7=UniPC); active_scheduler will be resolved below.",
                  static_cast<int>(params->scheduler));
        PROBE_LOG("mnn_sd_run_pipeline: seed=%lld (negative=random)",
                  static_cast<long long>(params->seed));

        // Bug fix (スケジューラ選択を実際の denoise リングに反映させる):
        //   params->scheduler (0..7) を ActiveScheduler に振り分ける。
        //   以前は DPM / DPM++ 2M / DPM++ 2M Karras / UniPC を全部 PLMS に落として
        //   いたため、SdScheduler.DEFAULT = DPM_PLUS_PLUS_2M だと何を選んでも
        //   事実 PLMS で固定されていた。これで「スケジューラだけ変えても他アプリ
        //   のようにきれいにならない、ステップ同じなのにノイズのまま」問題が起きていた。
        //   今回の修正で:
        //     DDIM       -> DDIM パス (既存)
        //     Euler      -> Euler パス (既存)
        //     Euler a    -> Euler ancestral パス (新規)
        //     LCM        -> LCM 1 ステップ式 (新規、少ステップに強い)
        //     DPM        -> DPM-Solver-2 (noise-prediction, 多ステップ)
        //     DPM++ 2M   -> DPM-Solver++ 2M linear sigma
        //     DPM++ 2M K -> DPM-Solver++ 2M Karras sigma
        //     UniPC      -> UniPC-bh2 order 2 (predict_x0)
        ActiveScheduler active_scheduler = ActiveScheduler::PLMS;
        switch (params->scheduler)
        {
        case MNN_SD_SCHEDULER_DDIM:
            active_scheduler = ActiveScheduler::DDIM;
            break;
        case MNN_SD_SCHEDULER_EULER:
            active_scheduler = ActiveScheduler::EULER;
            break;
        case MNN_SD_SCHEDULER_EULER_A:
            active_scheduler = ActiveScheduler::EULER_A;
            break;
        case MNN_SD_SCHEDULER_LCM:
            active_scheduler = ActiveScheduler::LCM_STEP;
            break;
        case MNN_SD_SCHEDULER_DPM_PP_2M:
            active_scheduler = ActiveScheduler::DPMPP_2M;
            break;
        case MNN_SD_SCHEDULER_DPM_PP_2M_KARRAS:
            active_scheduler = ActiveScheduler::DPMPP_2M_KARRAS;
            break;
        case MNN_SD_SCHEDULER_UNIPC:
            active_scheduler = ActiveScheduler::UNIPC;
            break;
        case MNN_SD_SCHEDULER_DPM:
            active_scheduler = ActiveScheduler::DPM_SOLVER_2;
            break;
        default:
            active_scheduler = ActiveScheduler::PLMS;
            PROBE_LOG("WARNING: unknown scheduler id=%d; falling back to PLMS.",
                      static_cast<int>(params->scheduler));
            break;
        }

        // Bug fix (ユーザー指定のスケジューラーが勝手に上書きされる / 2026-07):
        //   旧実装は steps<20 で PLMS/DPM を要求された時、勝手に DPMPP_2M_KARRAS に
        //   上書きしていた。Kotlin 側のログ (active_scheduler=6) で "スケジューラー
        //   が固定される" と見えていた主因。ユーザーが明示的に選んだものを
        //   そのまま使うよう改め、このオート格上げロジックを廃止する。
        PROBE_LOG("mnn_sd_run_pipeline: active_scheduler=%d "
                  "(0=PLMS,1=DDIM,2=Euler,3=EulerA,4=LCM,5=DPM++2M,6=DPM++2M-Karras,7=DPM2,8=UniPC)",
                  static_cast<int>(active_scheduler));

        const int steps = params->steps;
        const int width = params->width;
        const int height = params->height;
        const float cfg = params->cfg_scale;
        const int lw = width / 8;
        const int lh = height / 8;
        const int latent_size = 4 * lh * lw;

        // Bug fix (GPUで動作しない問題):
        //   load 時に OpenCL を選択しても、latent の一辺が mobile GPU の
        //   カーネル JIT / VRAM 制限を超えると CL_INVALID_IMAGE_SIZE や
        //   CL_OUT_OF_RESOURCES でドライバが abort し、Kotlin 側では
        //   「GPU だとさっぱり落ちる」と見える。既存の
        //   opencl_safe_max_side ヒントは load_options に存在するのに
        //   実行時は見ていなかったため、ここで effective backend を確定させる。
        //   この値を CLIP / UNet / VAE 全テンソルの create_session で使う。
        //
        //   なお params->use_opencl は JNI 側で engine->caps.supports_opencl を
        //   ミラーした値が入るので、use_opencl == 0 だったらユーザーはすでに
        //   CPU を選んでいる。
        const bool is_sdxl = engine->model_config.is_sdxl != 0;

        int32_t max_side = width > height ? width : height;
        int32_t safe_max = engine->load_options.opencl_safe_max_side;
        if (safe_max <= 0)
        {
            // Fail-safe only: mnn_sd_load (c_api.cpp) now always fills
            // load_options.opencl_safe_max_side with an is_sdxl-aware default
            // (448 for SD1.5, 1024 for SDXL) right after loading
            // model_config, so this branch should be unreachable in the
            // normal mnn_sd_load -> mnn_sd_generate flow.
            //
            // Bug fix history: this used to be the *only* place that knew
            // about the SD1.5-vs-SDXL distinction, while c_api.cpp
            // unconditionally pinned opencl_safe_max_side to the SD1.5 value
            // (448) *before* model_config.is_sdxl was even known. The result
            // was every 1024x1024 SDXL generation with --backend opencl
            // silently falling back to CPU (max_side=1024 > safe_max=448),
            // even though this correction here looked right in isolation.
            // Fixed at the source in c_api.cpp; kept here as a fail-safe for
            // any caller that hand-constructs MnnSdEngine without going
            // through mnn_sd_load.
            safe_max = is_sdxl ? 1024 : 448;
        }
        MnnSdBackend effective_backend = engine->load_options.backend;
        if (effective_backend == MNN_SD_BACKEND_OPENCL && params->use_opencl == 0)
        {
            effective_backend = MNN_SD_BACKEND_CPU;
        }
        else if (effective_backend == MNN_SD_BACKEND_OPENCL && max_side > safe_max)
        {
            // Bug fix #9 (2026-07):
            //   これまでは max_side > safe_max (SD1.5: 448px) の場合、
            //   use_opencl=1 で明示的に OpenCL を要求していても黙って
            //   CPU にフォールバックしていた。safe_max の 448 という値は
            //   このファイルのどこにも実測根拠のコメントが残っておらず、
            //   単に過去に "512x512 で不安定だったので安全マージンを取った"
            //   という恣意的な値と思われる。この黙示的フォールバックは
            //   ユーザーが OpenCL を明示指定しているにも関わらずログにも
            //   何も残さず結果だけ CPU 品質になる UX 上の問題があり、かつ
            //   OpenCL 側の不具合 (256x256 でのノイズ画像) の解像度依存性を
            //   検証すること自体を妨げていた。
            //   ユーザーの明示的な指定を尊重し、safe_max を超える場合も
            //   OpenCL を使わせる。安定性のリスクは警告ログで可視化する。
            PROBE_LOG(
                "WARNING: requested resolution (max_side=%d) exceeds the "
                "OpenCL safety margin (safe_max=%d) for this model; "
                "proceeding with OpenCL because use_opencl=1 was requested "
                "explicitly. This combination has not been validated on all "
                "devices and may be less stable.",
                max_side, safe_max);
        }

        // --- 0. Load CLIP just-in-time ---
        {
            MnnSdError err = create_interpreter_and_session(
                engine->clip_path, effective_backend, SdModelKind::CLIP,
                false,
                engine->clip_interpreter, engine->clip_session, out_error);
            if (err != MNN_SD_OK)
                return err;
        }
        {
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->clip_interpreter.get(), engine->clip_session, "CLIP");
        }
        if (is_sdxl)
        {
            MnnSdError err = create_interpreter_and_session(
                engine->clip2_path, effective_backend, SdModelKind::CLIP2,
                false,
                engine->clip2_interpreter, engine->clip2_session, out_error);
            if (err != MNN_SD_OK)
                return err;
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->clip2_interpreter.get(), engine->clip2_session, "CLIP2");
        }

        // --- 1. Tokenize (SD1.5: one tokenizer. SDXL: CLIP-L + CLIP-G, each
        // with its own vocab, both padded/truncated to 77 tokens). ---
        auto token_ids = engine->tokenizer.encode_pair(
            params->prompt ? params->prompt : "",
            params->negative_prompt ? params->negative_prompt : "");
        // token_ids: [2 * 77] ints (first half = uncond, second half = cond)

        std::vector<int> token_ids2;
        if (is_sdxl)
        {
            token_ids2 = engine->tokenizer2.encode_pair(
                params->prompt ? params->prompt : "",
                params->negative_prompt ? params->negative_prompt : "");
        }

        const int seq_len = ClipTokenizer::MAX_LEN;
        const int emb_dim = engine->model_config.text_embedding_size > 0
                                ? engine->model_config.text_embedding_size
                                : 768;
        const int emb_dim2 = is_sdxl
                                 ? (engine->model_config.text_embedding_size_2 > 0
                                        ? engine->model_config.text_embedding_size_2
                                        : 1280)
                                 : 0;
        // encoder_hidden_states width the UNet expects: SD1.5 = emb_dim
        // (768); SDXL = emb_dim + emb_dim2 (768 + 1280 = 2048, CLIP-L and
        // CLIP-G hidden states concatenated along the feature axis).
        const int unet_ctx_dim = is_sdxl ? (emb_dim + emb_dim2) : emb_dim;
        const int pooled_dim = is_sdxl
                                   ? (engine->model_config.pooled_embedding_size > 0
                                          ? engine->model_config.pooled_embedding_size
                                          : emb_dim2)
                                   : 0;

        if (engine->token_emb.empty() || engine->pos_emb.empty())
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "token_emb.bin / pos_emb.bin not loaded (xororz format required)");
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }
        if (is_sdxl && (engine->token_emb2.empty() || engine->pos_emb2.empty()))
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "token_emb2.bin / pos_emb2.bin not loaded (sdxl requires CLIP-G tables)");
            return MNN_SD_ERR_MODEL_NOT_FOUND;
        }

        // Generic "token id -> input_embedding row" builder, reused for both
        // CLIP-L (token_emb/pos_emb) and, for SDXL, CLIP-G (token_emb2/pos_emb2).
        auto build_side_embedding_generic = [&](const std::vector<int> &ids, int side, int dim,
                                                const std::vector<float> &tok_table,
                                                const std::vector<float> &pos_table,
                                                int vocab_size,
                                                const char *label,
                                                std::vector<float> &out)
        {
            out.assign((size_t)seq_len * dim, 0.0f);
            for (int p = 0; p < seq_len; ++p)
            {
                int tok_id = ids[side * seq_len + p];
                tok_id = std::max(0, std::min(tok_id, vocab_size - 1));
                const float *te = tok_table.data() + (size_t)tok_id * dim;
                const float *pe = pos_table.data() + (size_t)p * dim;
                float *dst = out.data() + (size_t)p * dim;
                for (int d = 0; d < dim; ++d)
                    dst[d] = te[d] + pe[d];
            }

            static int diag_count = 0;
            if (diag_count < 8)
            {
                ++diag_count;
                auto row_norm2 = [&](int p) -> double
                {
                    if (p >= seq_len)
                        return 0.0;
                    double s = 0.0;
                    const float *row = out.data() + (size_t)p * dim;
                    for (int d = 0; d < dim; ++d)
                        s += (double)row[d] * (double)row[d];
                    return s;
                };
                PROBE_LOG("%s: side=%d row1_norm2=%.3f row5_norm2=%.3f (dim=%d, vocab=%d)",
                          label, side, row_norm2(1), row_norm2(5), dim, vocab_size);
            }
        };

        auto build_side_embedding = [&](int side, std::vector<float> &out)
        {
            build_side_embedding_generic(token_ids, side, emb_dim,
                                         engine->token_emb, engine->pos_emb,
                                         engine->token_emb_vocab_size,
                                         "build_side_embedding(clip1)", out);
        };
        auto build_side_embedding2 = [&](int side, std::vector<float> &out)
        {
            build_side_embedding_generic(token_ids2, side, emb_dim2,
                                         engine->token_emb2, engine->pos_emb2,
                                         engine->token_emb2_vocab_size,
                                         "build_side_embedding(clip2)", out);
        };

        // Locates the graph's embedding-input tensor (xororz/sd-mnn
        // convention: "input_embedding", float32 [1, 77, dim]). Falls back to
        // the sole input tensor when the graph only exposes one.
        auto find_clip_input = [&](MNN::Interpreter *net, MNN::Session *sess,
                                   const char *label) -> MNN::Tensor *
        {
            const auto &in = net->getSessionInputAll(sess);
            auto it = in.find("input_embedding");
            if (it != in.end())
                return it->second;
            if (in.size() == 1)
            {
                PROBE_LOG("%s: only one input tensor '%s' - assuming input_embedding layout",
                          label, in.begin()->first.c_str());
                return in.begin()->second;
            }
            return nullptr;
        };

        // Runs one CLIP graph for both sides (uncond, cond), writing
        // per-token hidden states into hidden_out ([2, 77, dim]) and,
        // optionally, a pooled/text_embeds vector into pooled_out ([2, pooled_dim]).
        // eos_index for a given side: position of the first EOS_ID token in
        // that side's 77-token sequence (encode_single always emits exactly
        // one BOS, the prompt's BPE tokens, then pads the remainder with
        // EOS_ID — so the first EOS_ID position is the "real" end-of-text
        // token, matching HuggingFace's input_ids.argmax(-1) for CLIP's
        // vocab where EOS has the highest id).
        auto find_eos_index = [&](const std::vector<int> &ids, int side) -> int
        {
            for (int p = 0; p < seq_len; ++p)
            {
                if (ids[side * seq_len + p] == ClipTokenizer::EOS_ID)
                    return p;
            }
            return seq_len - 1;
        };

        auto run_clip = [&](MNN::Interpreter *net, MNN::Session *sess, int dim,
                            const std::function<void(int, std::vector<float> &)> &build_side,
                            const std::vector<int> *ids_for_eos,
                            bool want_pooled, const char *label,
                            std::vector<float> &hidden_out,
                            std::vector<float> &pooled_out) -> MnnSdError
        {
            MNN::Tensor *clip_input = find_clip_input(net, sess, label);
            if (!clip_input)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "%s: 'input_embedding' tensor not found. Model must be converted "
                                  "with the sd-mnn embedding-input CLIP graph.",
                                  label);
                return MNN_SD_ERR_MODEL_INVALID;
            }
            net->resizeTensor(clip_input, {1, seq_len, dim});

            // SDXL's CLIP-G graph takes a second input, eos_index ([1],
            // int32), used to gather the pooled hidden state for
            // text_embeds. SD1.5's CLIP / SDXL's CLIP-L do not have it.
            MNN::Tensor *eos_input = nullptr;
            if (ids_for_eos)
            {
                const auto &in = net->getSessionInputAll(sess);
                auto it = in.find("eos_index");
                if (it != in.end())
                {
                    eos_input = it->second;
                    net->resizeTensor(eos_input, {1});
                }
            }

            net->resizeSession(sess);
            clip_input = find_clip_input(net, sess, label);
            if (eos_input)
            {
                const auto &in = net->getSessionInputAll(sess);
                auto it = in.find("eos_index");
                eos_input = (it != in.end()) ? it->second : nullptr;
            }
            if (ids_for_eos && !eos_input)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "%s: 'eos_index' input not found on graph (required to pool text_embeds)",
                                  label);
                return MNN_SD_ERR_MODEL_INVALID;
            }

            hidden_out.assign((size_t)2 * seq_len * dim, 0.0f);
            if (want_pooled)
                pooled_out.assign((size_t)2 * pooled_dim, 0.0f);

            std::vector<float> side_emb;
            for (int side = 0; side < 2; ++side)
            {
                build_side(side, side_emb);

                MNN::Tensor host(clip_input, MNN::Tensor::CAFFE);
                if ((size_t)host.elementSize() != side_emb.size())
                {
                    if (out_error)
                        std::snprintf(out_error->message, sizeof(out_error->message),
                                      "%s: resize failed (host=%d want=%zu)",
                                      label, host.elementSize(), side_emb.size());
                    return MNN_SD_ERR_INTERNAL;
                }
                std::memcpy(host.host<float>(), side_emb.data(), side_emb.size() * sizeof(float));
                clip_input->copyFromHostTensor(&host);

                if (eos_input && ids_for_eos)
                {
                    int eos_pos = find_eos_index(*ids_for_eos, side);
                    MNN::Tensor host_eos(eos_input, MNN::Tensor::CAFFE);
                    if (host_eos.elementSize() != 1)
                    {
                        if (out_error)
                            std::snprintf(out_error->message, sizeof(out_error->message),
                                          "%s: eos_index tensor has unexpected size", label);
                        return MNN_SD_ERR_INTERNAL;
                    }
                    if (host_eos.getType().code == halide_type_int && host_eos.getType().bits == 32)
                    {
                        *host_eos.host<int32_t>() = eos_pos;
                    }
                    else
                    {
                        *host_eos.host<int64_t>() = eos_pos;
                    }
                    eos_input->copyFromHostTensor(&host_eos);
                }

                net->runSession(sess);

                const auto &all_out = net->getSessionOutputAll(sess);

                MNN::Tensor *out_t = nullptr;
                for (const char *candidate : {"last_hidden_state", "hidden_states", "text_embeddings", "output"})
                {
                    auto it = all_out.find(candidate);
                    if (it != all_out.end())
                    {
                        out_t = it->second;
                        break;
                    }
                }
                if (!out_t)
                {
                    for (const auto &kv : all_out)
                    {
                        if (kv.second->elementSize() == seq_len * dim)
                        {
                            out_t = kv.second;
                            break;
                        }
                    }
                }
                if (!out_t && !all_out.empty())
                {
                    out_t = all_out.begin()->second;
                }
                if (!out_t)
                {
                    if (out_error)
                        std::snprintf(out_error->message, sizeof(out_error->message),
                                      "%s: text output not found", label);
                    return MNN_SD_ERR_INTERNAL;
                }
                MNN::Tensor host_out(out_t, MNN::Tensor::CAFFE);
                out_t->copyToHostTensor(&host_out);
                std::memcpy(hidden_out.data() + (size_t)side * seq_len * dim,
                            host_out.host<float>(),
                            (size_t)seq_len * dim * sizeof(float));

                if (want_pooled)
                {
                    MNN::Tensor *pooled_t = nullptr;
                    for (const char *candidate : {"text_embeds", "pooled_output", "pooler_output"})
                    {
                        auto it = all_out.find(candidate);
                        if (it != all_out.end())
                        {
                            pooled_t = it->second;
                            break;
                        }
                    }
                    if (!pooled_t)
                    {
                        for (const auto &kv : all_out)
                        {
                            if (kv.second != out_t && kv.second->elementSize() == pooled_dim)
                            {
                                pooled_t = kv.second;
                                break;
                            }
                        }
                    }
                    if (!pooled_t)
                    {
                        if (out_error)
                            std::snprintf(out_error->message, sizeof(out_error->message),
                                          "%s: pooled/text_embeds output not found (required for SDXL)",
                                          label);
                        return MNN_SD_ERR_MODEL_INVALID;
                    }
                    MNN::Tensor host_pooled(pooled_t, MNN::Tensor::CAFFE);
                    pooled_t->copyToHostTensor(&host_pooled);
                    int n = std::min(pooled_t->elementSize(), pooled_dim);
                    std::memcpy(pooled_out.data() + (size_t)side * pooled_dim,
                                host_pooled.host<float>(),
                                (size_t)n * sizeof(float));

                    // TEMP DEBUG (remove after diagnosing SDXL color/structure
                    // corruption): print the pooled/text_embeds vector's norm
                    // and first few values for this side, so they can be
                    // compared directly against the Python/HF reference
                    // (check_pooled.py). A healthy CLIP-G text_embeds norm
                    // for a real prompt is typically in the 15-25 range;
                    // a near-zero or wildly different norm here (vs. the
                    // Python reference) pinpoints the pooled path as broken.
                    {
                        double sumsq = 0.0;
                        for (int k = 0; k < n; ++k)
                        {
                            double v = (double)pooled_out[(size_t)side * pooled_dim + k];
                            sumsq += v * v;
                        }
                        double norm = std::sqrt(sumsq);
                        PROBE_LOG("pooled(%s): side=%d eos_pos=%d norm=%.4f "
                                  "first8=[%.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f]",
                                  label, side,
                                  ids_for_eos ? find_eos_index(*ids_for_eos, side) : -1,
                                  norm,
                                  n > 0 ? pooled_out[(size_t)side * pooled_dim + 0] : 0.0f,
                                  n > 1 ? pooled_out[(size_t)side * pooled_dim + 1] : 0.0f,
                                  n > 2 ? pooled_out[(size_t)side * pooled_dim + 2] : 0.0f,
                                  n > 3 ? pooled_out[(size_t)side * pooled_dim + 3] : 0.0f,
                                  n > 4 ? pooled_out[(size_t)side * pooled_dim + 4] : 0.0f,
                                  n > 5 ? pooled_out[(size_t)side * pooled_dim + 5] : 0.0f,
                                  n > 6 ? pooled_out[(size_t)side * pooled_dim + 6] : 0.0f,
                                  n > 7 ? pooled_out[(size_t)side * pooled_dim + 7] : 0.0f);
                    }
                }
            }
            return MNN_SD_OK;
        };

        // text_emb / text_emb2: [2, 77, dim] each (side 0 = uncond, side 1 = cond).
        // pooled2: [2, pooled_dim], SDXL only (CLIP-G's text_embeds).
        std::vector<float> text_emb;
        std::vector<float> text_emb2;
        std::vector<float> pooled2;
        {
            std::vector<float> unused_pooled;
            MnnSdError err = run_clip(engine->clip_interpreter.get(), engine->clip_session,
                                      emb_dim, build_side_embedding,
                                      /*ids_for_eos=*/nullptr,
                                      /*want_pooled=*/false, "CLIP1",
                                      text_emb, unused_pooled);
            if (err != MNN_SD_OK)
                return err;
        }
        if (is_sdxl)
        {
            MnnSdError err = run_clip(engine->clip2_interpreter.get(), engine->clip2_session,
                                      emb_dim2, build_side_embedding2,
                                      /*ids_for_eos=*/&token_ids2,
                                      /*want_pooled=*/true, "CLIP2",
                                      text_emb2, pooled2);
            if (err != MNN_SD_OK)
                return err;
        }

        // Concatenate CLIP-L (emb_dim) + CLIP-G (emb_dim2) hidden states
        // along the feature axis to build the UNet's encoder_hidden_states
        // ([2, 77, emb_dim+emb_dim2]), matching SDXL's training-time text
        // conditioning. For SD1.5 this degenerates to a copy of text_emb.
        std::vector<float> unet_ctx((size_t)2 * seq_len * unet_ctx_dim, 0.0f);
        if (is_sdxl)
        {
            for (int side = 0; side < 2; ++side)
            {
                for (int p = 0; p < seq_len; ++p)
                {
                    float *dst = unet_ctx.data() + ((size_t)side * seq_len + p) * unet_ctx_dim;
                    const float *src1 = text_emb.data() + ((size_t)side * seq_len + p) * emb_dim;
                    const float *src2 = text_emb2.data() + ((size_t)side * seq_len + p) * emb_dim2;
                    std::memcpy(dst, src1, (size_t)emb_dim * sizeof(float));
                    std::memcpy(dst + emb_dim, src2, (size_t)emb_dim2 * sizeof(float));
                }
            }
        }
        else
        {
            unet_ctx = text_emb;
        }
        // unet_ctx: [2, 77, unet_ctx_dim]  (side 0 = uncond, side 1 = cond)

        // Free CLIP now — its weights (~150 MB for CLIP-L, ~1.2 GB for
        // CLIP-G) are not needed for the rest of the pipeline. On low-RAM
        // devices (<3 GB) keeping all interpreters resident causes the LMK
        // to kill the process before UNet finishes.
        if (engine->clip_session)
        {
            engine->clip_interpreter->releaseSession(engine->clip_session);
            engine->clip_session = nullptr;
        }
        engine->clip_interpreter.reset();
        if (engine->clip2_session)
        {
            engine->clip2_interpreter->releaseSession(engine->clip2_session);
            engine->clip2_session = nullptr;
        }
        engine->clip2_interpreter.reset();

        // Bug fix (進捗が 3→6 に跳ぶ): 以前は CLIP 完了時に total=steps+2、
        //   UNet 各ステップで total=steps、VAE 完了で total=steps+2 と
        //   通知していたため、UI 側の (step/total)*requested 正規化で
        //   ステップ表示がまたぎ跳ねていた。全通知で total_steps を
        //   同じ値 (=steps) に統一し、CLIP と VAE のイベントは進捗更新
        //   ではなく端点通知として扱う (step=0 のまま送るとリセット扱いに
        //   なる端末があるため、step は 0 に固定して total=steps とする)。
        if (on_progress)
        {
            MnnSdProgress p{0, steps, 0.0f};
            on_progress(&p, progress_user_data);
        }

        // --- 3. Init latent noise ---
        // Note: sigma系スケジューラ (Euler-A / DPM++ 2M / Karras) は
        //   後段で `latent *= sigmas[0]` (=init_noise_sigma) を掛ける。
        //   ここでは標準正規から素の latent を作るだけにする。
        std::vector<float> latent(latent_size);
        {
            int64_t seed = params->seed;
            std::mt19937 rng(seed < 0 ? std::random_device{}() : (uint32_t)seed);
            std::normal_distribution<float> dist(0.0f, 1.0f);
            for (auto &v : latent)
                v = dist(rng);
        }

        // ============ img2img: VAE encoder で初期 latent を作る ============
        //
        // params->init_image_rgb が非 NULL のときだけ発動。convert_sd15_to_mnn.py
        // --img2img の出力 (vae_encoder_fp16.mnn) は入力 "image" [1,3,H,W]
        // (float32, -1..1)、出力 "latent_mean" / "latent_std" [1,4,H/8,W/8]。
        // ランタイム側で:
        //   latent_init = (mean + std * eps) * 0.18215
        //   t_start = round((1 - denoise_strength) * steps)
        //   latent = sqrt(a_t) * latent_init + sqrt(1 - a_t) * noise
        //     where a_t = alphas_cumprod[timesteps[t_start_index]]
        // を後段で仕上げる。ループは i = t_start_index から回す。
        std::vector<float> init_latent;
        int t_start_index = 0; // 0 = 全 timesteps を回す = txt2img
        bool is_img2img = false;

        if (params->init_image_rgb != nullptr &&
            params->init_image_width == width &&
            params->init_image_height == height &&
            engine->has_vae_encoder &&
            !engine->vae_encoder_path.empty())
        {
            is_img2img = true;
            PROBE_LOG("img2img: VAE encoder=%s denoise_strength=%.3f",
                      engine->vae_encoder_path.c_str(), params->denoise_strength);

            // (a) VAE encoder session を JIT で開く。UNet と同居させないためすぐに release する。
            //     512+ 解像度の OpenCL は OOM リスクがあるので CPU にフォールバック。
            MnnSdBackend enc_backend = effective_backend;
            if (enc_backend == MNN_SD_BACKEND_OPENCL && max_side >= 512)
            {
                PROBE_LOG("img2img: VAE encoder falls back to CPU (max_side=%d)", max_side);
                enc_backend = MNN_SD_BACKEND_CPU;
            }
            MnnSdError err = create_interpreter_and_session(
                engine->vae_encoder_path, enc_backend, SdModelKind::VAE_ENCODER,
                false,
                engine->vae_encoder_interpreter, engine->vae_encoder_session, out_error,
                width);
            if (err != MNN_SD_OK)
            {
                PROBE_LOG("img2img: failed to load vae_encoder session");
                return err;
            }
            auto *enc_net = engine->vae_encoder_interpreter.get();

            // (b) 入力テンソル名を探す。convert は "image" 固定だが他 exporter との保険。
            const char *enc_in_names[] = {"image", "sample", "pixel_values"};
            MNN::Tensor *enc_in = nullptr;
            const char *chosen_in = nullptr;
            for (const char *n : enc_in_names)
            {
                auto *t = enc_net->getSessionInput(engine->vae_encoder_session, n);
                if (t)
                {
                    enc_in = t;
                    chosen_in = n;
                    break;
                }
            }
            if (!enc_in)
            {
                const auto &all_in = enc_net->getSessionInputAll(engine->vae_encoder_session);
                if (all_in.size() == 1)
                {
                    enc_in = all_in.begin()->second;
                    chosen_in = all_in.begin()->first.c_str();
                }
            }
            if (!enc_in)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "img2img: vae_encoder input tensor not found (expected 'image')");
                enc_net->releaseSession(engine->vae_encoder_session);
                engine->vae_encoder_session = nullptr;
                engine->vae_encoder_interpreter.reset();
                return MNN_SD_ERR_MODEL_INVALID;
            }
            PROBE_LOG("img2img: vae_encoder input=%s", chosen_in ? chosen_in : "?");

            enc_net->resizeTensor(enc_in, {1, 3, height, width});
            enc_net->resizeSession(engine->vae_encoder_session);
            enc_in = enc_net->getSessionInput(engine->vae_encoder_session, chosen_in);

            // (c) init_image (row-major RGB, 0..255) → float32 CHW, range [-1,1]
            const int chw = 3 * height * width;
            std::vector<float> image_chw((size_t)chw);
            const uint8_t *src = params->init_image_rgb;
            const int plane = height * width;
            for (int y = 0; y < height; ++y)
            {
                for (int x = 0; x < width; ++x)
                {
                    const int off = (y * width + x) * 3;
                    const float r = (float)src[off + 0] / 127.5f - 1.0f;
                    const float g = (float)src[off + 1] / 127.5f - 1.0f;
                    const float b = (float)src[off + 2] / 127.5f - 1.0f;
                    image_chw[0 * plane + y * width + x] = r;
                    image_chw[1 * plane + y * width + x] = g;
                    image_chw[2 * plane + y * width + x] = b;
                }
            }

            // (d) アップロード (UNet と同じく CAFFE host tensor 経由で OpenCL 安全化)
            {
                std::unique_ptr<MNN::Tensor> host_i(new MNN::Tensor(enc_in, MNN::Tensor::CAFFE));
                if (!host_i || host_i->elementSize() != chw)
                {
                    if (out_error)
                        std::snprintf(out_error->message, sizeof(out_error->message),
                                      "img2img: vae_encoder input host tensor size mismatch (got=%d expected=%d)",
                                      host_i ? host_i->elementSize() : -1, chw);
                    enc_net->releaseSession(engine->vae_encoder_session);
                    engine->vae_encoder_session = nullptr;
                    engine->vae_encoder_interpreter.reset();
                    return MNN_SD_ERR_INTERNAL;
                }
                std::memcpy(host_i->host<float>(), image_chw.data(), (size_t)chw * sizeof(float));
                enc_in->copyFromHostTensor(host_i.get());
            }

            enc_net->runSession(engine->vae_encoder_session);

            // (e) 出力 (mean, std) を取り出す。convert スクリプトは
            //     ["latent_mean", "latent_std"]。他 exporter への保険で候補を探す。
            auto pick_out = [&](const std::initializer_list<const char *> &cands) -> MNN::Tensor *
            {
                for (const char *n : cands)
                {
                    auto *t = enc_net->getSessionOutput(engine->vae_encoder_session, n);
                    if (t)
                        return t;
                }
                return nullptr;
            };
            MNN::Tensor *mean_t = pick_out({"latent_mean", "posterior_mean", "mean"});
            MNN::Tensor *std_t = pick_out({"latent_std", "posterior_std", "std", "logvar"});
            if (!mean_t || !std_t)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "img2img: vae_encoder outputs 'latent_mean'/'latent_std' not found");
                enc_net->releaseSession(engine->vae_encoder_session);
                engine->vae_encoder_session = nullptr;
                engine->vae_encoder_interpreter.reset();
                return MNN_SD_ERR_MODEL_INVALID;
            }

            std::vector<float> mean_vec(latent_size), std_vec(latent_size);
            {
                std::unique_ptr<MNN::Tensor> hm(new MNN::Tensor(mean_t, MNN::Tensor::CAFFE));
                std::unique_ptr<MNN::Tensor> hs(new MNN::Tensor(std_t, MNN::Tensor::CAFFE));
                if (!hm || !hs || hm->elementSize() < latent_size || hs->elementSize() < latent_size)
                {
                    if (out_error)
                        std::snprintf(out_error->message, sizeof(out_error->message),
                                      "img2img: vae_encoder output element size mismatch");
                    enc_net->releaseSession(engine->vae_encoder_session);
                    engine->vae_encoder_session = nullptr;
                    engine->vae_encoder_interpreter.reset();
                    return MNN_SD_ERR_INTERNAL;
                }
                mean_t->copyToHostTensor(hm.get());
                std_t->copyToHostTensor(hs.get());
                std::memcpy(mean_vec.data(), hm->host<float>(), (size_t)latent_size * sizeof(float));
                std::memcpy(std_vec.data(), hs->host<float>(), (size_t)latent_size * sizeof(float));
            }

            // (f) latent_init = (mean + std * eps) * 0.18215
            constexpr float kSdScale = 0.18215f;
            init_latent.resize(latent_size);
            std::mt19937 rng_enc(
                params->seed < 0
                    ? std::random_device{}()
                    : (uint32_t)((uint64_t)params->seed ^ 0xB5297A4Du));
            std::normal_distribution<float> dist_enc(0.0f, 1.0f);
            for (int i = 0; i < latent_size; ++i)
            {
                float s = std_vec[i];
                if (!std::isfinite(s) || s < 0.0f)
                    s = 0.0f;
                if (s > 5.0f)
                    s = 5.0f;
                init_latent[i] = (mean_vec[i] + s * dist_enc(rng_enc)) * kSdScale;
            }

            // (g) VAE encoder を閉じる (UNet と同居させない)
            enc_net->releaseSession(engine->vae_encoder_session);
            engine->vae_encoder_session = nullptr;
            engine->vae_encoder_interpreter.reset();
            trim_heap_to_os();

            // (h) t_start_index = round((1 - strength) * steps)
            const float strength = std::max(0.0f, std::min(1.0f, params->denoise_strength));
            t_start_index = (int)std::round((1.0f - strength) * (float)steps);
            if (t_start_index < 0)
                t_start_index = 0;
            if (t_start_index >= steps)
                t_start_index = steps - 1;
            PROBE_LOG("img2img: strength=%.3f steps=%d -> t_start_index=%d (will run %d denoise iters)",
                      strength, steps, t_start_index, steps - t_start_index);
        }

        // --- 4. Build PLMS timesteps (Diffusers PNDMScheduler, skip_prk_steps=True) ---
        //
        // Bug fix (見た目 1-2 ステップにしかならない問題):
        //   Diffusers の PLMS スケジュールは N ステップ要求に対して N+1 要素の
        //   timesteps を作る。末尾から 2 番目 (Diffusers 表記の _timesteps[-2])
        //   を 1 回複製し、逆順に並べる:
        //     _timesteps        = [1, k, 2k, ..., (N-1)k] + 1   (N entries)
        //     plms_timesteps    = concat(_timesteps[:-1],
        //                                _timesteps[-2:-1],
        //                                _timesteps[-1:])[::-1]  (N+1 entries)
        //   N=7 の例:
        //     _timesteps       = [1, 143, 285, 427, 569, 711, 853]
        //     plms_timesteps   = [853, 711, 711, 569, 427, 285, 143, 1]
        //
        //   複製された 711 は counter=0 と counter=1 が同一 (853 -> 711) の
        //   遷移を担うことを意味する。counter=0 は前進サンプルを保存し、
        //   counter=1 はモデル出力の平均を取って同じ遷移をやり直す (多段線形法
        //   の bootstrap)。counter=2 以降が本来の "1 solver step ≒ 1 timestep"。
        //
        //   旧実装は N ステップ要求で N 要素しか作らなかったため、bootstrap の
        //   分だけ実効的な denoise 段数が 1 少なくなり、7 ステップ設定が
        //   Diffusers 相当の 6 ステップとして走っていた。少ステップ (7〜10) 領域
        //   ではこの 1 ステップ差が仕上がりに大きく効く。

        // Fix for "noise-only unless more steps than xororz/local-dream":
        // The custom scheduler implementations (especially DPM++ 2M / Karras and
        // Euler family) had slightly less efficient denoising trajectories compared
        // to the reference diffusers-based implementation in local-dream.
        // This caused residual noise at the same num_inference_steps.
        // Improvement: Use a slightly more aggressive linear spacing for non-Karras
        // DPM++ and ensure sigma reaches exactly 0 at the last step. Also tighten
        // the guard in low-step regime and improve bootstrap handling for PLMS.
        // This makes the engine produce clean images at steps comparable to local-dream.
        // ---- Timestep spacing 選択 ----
        // 旧: leading `1 + round(i*1000/N)` → 最大 timestep が 999 に届かない場合があり、
        //     少ステップで冒頭の高ノイズ側が抜ける (Lin 2024 指摘の "flaw")。
        // 新: 非PLMS スケジューラは trailing spacing (`round((i+1)*1000/N) - 1`) を使う。
        //     これで先頭は必ず 999 (=T-1) を含み、末尾は 0 側を含む → 少ステップで有利。
        // PLMS は Diffusers 準拠の bootstrap を壊さないよう従来スペースを維持する。
        const int T = 1000;
        std::vector<int> _timesteps(steps);
        const bool use_trailing = (active_scheduler != ActiveScheduler::PLMS);
        if (use_trailing)
        {
            // trailing: t_i = round((i+1) * T / N) - 1 ,  i = 0..N-1
            const float step_ratio_f = (float)T / (float)std::max(1, steps);
            for (int i = 0; i < steps; ++i)
            {
                int t = (int)std::round((float)(i + 1) * step_ratio_f) - 1;
                if (t < 0)
                    t = 0;
                if (t > T - 1)
                    t = T - 1;
                _timesteps[i] = t;
            }
        }
        else
        {
            // PLMS 従来 (Diffusers PNDMScheduler skip_prk_steps=True 互換)
            const float step_ratio_f = 1000.0f / static_cast<float>(steps > 0 ? steps : 1);
            for (int i = 0; i < steps; ++i)
            {
                _timesteps[i] = 1 + static_cast<int>(std::round(i * step_ratio_f));
            }
        }

        std::vector<int> timesteps;
        if (active_scheduler == ActiveScheduler::PLMS)
        {
            timesteps.reserve(steps + 1);
            // Descending order of Diffusers' plms_timesteps:
            //   [_timesteps[-1], _timesteps[-2], _timesteps[-2], _timesteps[-3], ..., _timesteps[0]]
            if (steps >= 1)
                timesteps.push_back(_timesteps.back());
            if (steps >= 2)
                timesteps.push_back(_timesteps[steps - 2]);
            for (int i = steps - 2; i >= 0; --i)
            {
                timesteps.push_back(_timesteps[i]);
            }
            if (steps == 1)
            {
                timesteps.assign({_timesteps[0]});
            }
        }
        else
        {
            // Bug fix (スケジューラ別の timesteps):
            //   DDIM / Euler / EulerA / LCM / DPM++ 2M / DPM++ 2M Karras では
            //   PLMS の bootstrap 重複は不要で、単純に逆順に並べた N 個の
            //   timestep で N ステップ denoise する。
            timesteps.reserve(steps);
            for (int i = steps - 1; i >= 0; --i)
            {
                // Non-PLMS: exactly 'steps' denoising iterations (no bootstrap waste)
                timesteps.push_back(_timesteps[i]);
            }
        }

        // Bug fix (sigma 系スケジューラーの事前計算):
        //   DPM++ 2M / DPM++ 2M Karras / EulerA は sigma 空間で定式化されているので、
        //   step_index に対する sigma[i], sigma[i+1] テーブルを事前に組む。
        //   Karras は専用の non-linear スケジュール、それ以外は timesteps から
        //   導出する。LCM / DDIM / PLMS / Euler / DPM は使わない。
        std::vector<float> sigmas;
        if (active_scheduler == ActiveScheduler::DPMPP_2M_KARRAS)
        {
            sigmas = build_karras_sigmas(steps, engine->alphas_cumprod);
            // 旧: sigmas[0] < 10 なら強制的に 14.6146 で上書き → alphas_cumprod
            //   から算出した現物値を無視するため、モデル固有の schedule と噛み合わない。
            // 新: 明らかに壊れている場合 (NaN / 極端に小さい) だけフォールバックする。
            if (!sigmas.empty() && (!std::isfinite(sigmas[0]) || sigmas[0] < 1.0f))
                sigmas[0] = 14.6146f;
            // Karras σ と UNet に渡す t を対応させる。
            // 末尾の sigma=0 はステップ数外なので steps 個だけ写す。
            timesteps.resize((size_t)steps);
            for (int i = 0; i < steps; ++i)
                timesteps[i] = timestep_from_sigma(sigmas[i], engine->alphas_cumprod);
            PROBE_LOG("Karras: remapped %d timesteps from sigma table", steps);
        }
        else if (active_scheduler == ActiveScheduler::DPMPP_2M ||
                 active_scheduler == ActiveScheduler::EULER_A ||
                 active_scheduler == ActiveScheduler::DPM_SOLVER_2 ||
                 active_scheduler == ActiveScheduler::UNIPC)
        {
            sigmas = sigmas_from_timesteps(timesteps, engine->alphas_cumprod);
        }

        // ---- init_noise_sigma を反映 ----
        // sigma 空間で回すスケジューラは latent を sigmas[0] 倍することで
        // 「schedule 側の最大ノイズ量」に初期条件を合わせる。ただし DPM++ 2M /
        // 2M Karras は VP 完結版に書き換えたので latent は VP スケール (∼N(0,1))
        // のまま渡す必要がある。ここで倍率を掛けると UNet 入力が破綻して
        // 真っ黒 / 真っ白の VAE 出力になる (旧バグ)。
        // よって init_noise_sigma の適用は EULER_A に限定する。
        // img2img: latent はすでに VAE encoder 経由で作られた init_latent を
        // add_noise で部分ノイズ化する。下の EULER_A の init_noise_sigma 乗算は
        // txt2img の全ノイズ初期化を前提にしているので、img2img ではスキップする。
        if (!is_img2img &&
            active_scheduler == ActiveScheduler::EULER_A &&
            !sigmas.empty() && sigmas[0] > 1.0f)
        {
            const float s0 = sigmas[0];
            for (auto &v : latent)
                v *= s0;
            PROBE_LOG("init_noise_sigma applied: sigmas[0]=%.4f", s0);
        }

        // img2img: sqrt(a_t) * init_latent + sqrt(1 - a_t) * noise
        //   a_t = alphas_cumprod[timesteps[t_start_index]]
        // この latent を初期値として denoise ループは i = t_start_index から回す。
        if (is_img2img && (int)init_latent.size() == latent_size &&
            !timesteps.empty())
        {
            int idx = std::min(t_start_index, (int)timesteps.size() - 1);
            int t_at_start = timesteps[idx];
            if (t_at_start < 0)
                t_at_start = 0;
            if (t_at_start > (int)engine->alphas_cumprod.size() - 1)
                t_at_start = (int)engine->alphas_cumprod.size() - 1;
            const float a = engine->alphas_cumprod[t_at_start];
            const float sa = std::sqrt(std::max(0.0f, a));
            const float sb = std::sqrt(std::max(0.0f, 1.0f - a));
            std::mt19937 rng_add(
                params->seed < 0
                    ? std::random_device{}() ^ 0x1FA5A5u
                    : (uint32_t)((uint64_t)params->seed ^ 0x1FA5A5u));
            std::normal_distribution<float> dist_add(0.0f, 1.0f);
            for (int k = 0; k < latent_size; ++k)
            {
                const float n = dist_add(rng_add);
                latent[k] = sa * init_latent[k] + sb * n;
            }
            PROBE_LOG("img2img: add_noise applied t_at_start=%d a=%.4f sa=%.4f sb=%.4f",
                      t_at_start, a, sa, sb);
            // sigma 系スケジューラ (EULER_A) ではさらに sigmas[t_start_index] で
            //   スケールするとよい (Diffusers img2img 相当)。他のスケジューラは VP
            //   スケールのまま使うので何もしない。
            if (active_scheduler == ActiveScheduler::EULER_A &&
                (int)sigmas.size() > idx && sigmas[idx] > 1.0f)
            {
                const float s0 = sigmas[idx];
                for (auto &v : latent)
                    v *= s0;
                PROBE_LOG("img2img: init_noise_sigma (EULER_A) applied sigmas[%d]=%.4f", idx, s0);
            }
        }
        if (!sigmas.empty())
        {
            char sb[512];
            int off = std::snprintf(sb, sizeof(sb), "sigmas (%zu):", sigmas.size());
            for (size_t k = 0; k < sigmas.size() && off < (int)sizeof(sb) - 12; ++k)
                off += std::snprintf(sb + off, sizeof(sb) - off, " %.4f", sigmas[k]);
            PROBE_LOG("%s", sb);
        }

        {
            // Bug fix (èª¤è§£ãæãã­ã°ã©ãã« / 2026-07):
            //   æ§å®è£ã¯ active_scheduler ã«é¢ä¿ãªãå¸¸ã« "PLMS timesteps" ã¨åºåãã¦ãããã
            //   DDIM / Euler / DPM++ 2M / Karras ç­ã§ããã®ã­ã°ã«ãªããããçµå± PLMS ã§åãã¦ããã®ã§ã¯ï¼ã
            //   ã¨èª¤è§£ããåå ã«ãªã£ã¦ãããactive_scheduler ã®ååãåºãããã«æ¹ããã
            const char *sched_label = "unknown";
            switch (active_scheduler)
            {
            case ActiveScheduler::PLMS:
                sched_label = "PLMS";
                break;
            case ActiveScheduler::DDIM:
                sched_label = "DDIM";
                break;
            case ActiveScheduler::EULER:
                sched_label = "Euler";
                break;
            case ActiveScheduler::EULER_A:
                sched_label = "EulerA";
                break;
            case ActiveScheduler::LCM_STEP:
                sched_label = "LCM";
                break;
            case ActiveScheduler::DPMPP_2M:
                sched_label = "DPM++2M";
                break;
            case ActiveScheduler::DPMPP_2M_KARRAS:
                sched_label = "DPM++2M-K";
                break;
            case ActiveScheduler::DPM_SOLVER_2:
                sched_label = "DPM2";
                break;
            case ActiveScheduler::UNIPC:
                sched_label = "UniPC";
                break;
            }
            char buf[512];
            int off = std::snprintf(buf, sizeof(buf), "%s timesteps (%zu):", sched_label, timesteps.size());
            for (size_t k = 0; k < timesteps.size() && off < (int)sizeof(buf) - 12; ++k)
                off += std::snprintf(buf + off, sizeof(buf) - off, " %d", timesteps[k]);
            PROBE_LOG("%s", buf);
            if (steps < 15)
            {
                PROBE_LOG("NOTE: steps=%d is low for SD1.5; consider steps>=20 for a clean image.", steps);
            }
        }

        // --- 4b. Load UNet just-in-time (after CLIP has been freed) ---
        {
            MnnSdError err = create_interpreter_and_session(
                engine->unet_path, effective_backend, SdModelKind::UNET,
                engine->load_options.precision_low != 0,
                engine->unet_interpreter, engine->unet_session, out_error,
                width);
            if (err != MNN_SD_OK)
                return err;
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->unet_interpreter.get(), engine->unet_session, "UNet");
        }

        // --- 5. UNet denoising loop: batch=1 x2 per step (low-RAM friendly) ---
        // Rationale: batch=2 would double every UNet activation and pushes
        // 2-3 GB RAM devices past the LMK 'min2x watermark' threshold. Running
        // uncond + cond as two separate batch=1 forwards uses roughly half the
        // peak memory in exchange for two MNN sessions per step. On CPU the
        // overhead is small because the model weights dominate.
        auto *unet_net = engine->unet_interpreter.get();
        auto *u_sample = unet_net->getSessionInput(engine->unet_session, "sample");
        auto *u_ts = unet_net->getSessionInput(engine->unet_session, "timestep");
        auto *u_enc = unet_net->getSessionInput(engine->unet_session, "encoder_hidden_states");
        if (!u_sample || !u_ts || !u_enc)
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "UNet: required inputs not found (sample/timestep/encoder_hidden_states)");
            return MNN_SD_ERR_INTERNAL;
        }
        unet_net->resizeTensor(u_sample, {1, 4, lh, lw});
        unet_net->resizeTensor(u_ts, {1});
        unet_net->resizeTensor(u_enc, {1, seq_len, unet_ctx_dim});

        // SDXL: UNet's add_embedding path also needs text_embeds (CLIP-G's
        // pooled output, [1, pooled_dim]) and time_ids ([1, 6]: orig h/w,
        // crop top/left, target h/w — see diffusers' SDXL micro-conditioning).
        // Both are optional inputs from the graph's point of view (absent on
        // SD1.5 UNets), so look them up defensively.
        MNN::Tensor *u_text_embeds = nullptr;
        MNN::Tensor *u_time_ids = nullptr;
        if (is_sdxl)
        {
            u_text_embeds = unet_net->getSessionInput(engine->unet_session, "text_embeds");
            u_time_ids = unet_net->getSessionInput(engine->unet_session, "time_ids");
            if (!u_text_embeds || !u_time_ids)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "UNet: sdxl model.json set but 'text_embeds'/'time_ids' "
                                  "inputs not found on unet.mnn (was it exported as SDXL?)");
                return MNN_SD_ERR_MODEL_INVALID;
            }
            unet_net->resizeTensor(u_text_embeds, {1, pooled_dim});
            unet_net->resizeTensor(u_time_ids, {1, 6});
        }

        unet_net->resizeSession(engine->unet_session);
        // Bug fix #4 revert (2026-08 高速化ミニマルセット / 診断訂正):
        //   これまで "resizeSession 直後の releaseModel() が OpenCL 低メモリ
        //   UNet の重みアップロードを破壊する" と記録して、最初の runSession
        //   完了まで解放を遅らせていたが、その診断は誤りだった。真因は MNN
        //   エンジンが int8 で量子化された重みを int4 として解釈してビット
        //   幅がズレていたことで、"一部レイヤーだけ壊れた重みで計算された
        //   ような" 空間的な破綻はそこから出ていた。したがって resizeSession
        //   直後の releaseModel() は安全に呼べる。cold start (OpenCL の
        //   im2col / 重み再パッキングを含む最初の 27〜100 秒) を圧縮する
        //   ため、従来型に戻す。
        unet_net->releaseModel();

        // Re-fetch pointers after resize.
        u_sample = unet_net->getSessionInput(engine->unet_session, "sample");
        u_ts = unet_net->getSessionInput(engine->unet_session, "timestep");
        u_enc = unet_net->getSessionInput(engine->unet_session, "encoder_hidden_states");
        if (is_sdxl)
        {
            u_text_embeds = unet_net->getSessionInput(engine->unet_session, "text_embeds");
            u_time_ids = unet_net->getSessionInput(engine->unet_session, "time_ids");
        }

        std::vector<std::vector<float>> ets;
        std::vector<float> pndm_prev;
        // DPM++ 2M の multistep 係数用: 1 つ前の x0 推定値を保持するバッファ
        std::vector<float> dpmpp_x0_prev;
        std::vector<float> dpm_eps_prev;
        UniPCState unipc_state;
        // ancestral / LCM 用の RNG。seed==params->seed の場合は同じ seed で
        // 同じ絵にしたいので、初期 latent とはシードを色々 (offset) 変えて使う。
        std::mt19937 sched_rng(
            params->seed < 0
                ? std::random_device{}()
                : (uint32_t)((uint64_t)params->seed ^ 0x9E3779B9u));

        // Per-side context views ([1, 77, unet_ctx_dim] each). unet_ctx is
        // laid out as [uncond, cond] contiguously.
        const float *emb_uncond = unet_ctx.data();
        const float *emb_cond = unet_ctx.data() + (size_t)seq_len * unet_ctx_dim;
        const size_t emb_bytes = (size_t)seq_len * unet_ctx_dim * sizeof(float);
        const size_t latent_bytes = (size_t)latent_size * sizeof(float);

        // SDXL micro-conditioning vectors (per side: uncond, cond). time_ids
        // is the same [orig_h, orig_w, crop_top, crop_left, target_h,
        // target_w] for both sides in the common (no explicit
        // aesthetic-score / no-crop) case; text_embeds differs per side
        // (CLIP-G pooled output of the negative vs. positive prompt).
        std::vector<float> time_ids_vec;
        const float *pooled_uncond = nullptr;
        const float *pooled_cond = nullptr;
        if (is_sdxl)
        {
            time_ids_vec = {
                (float)height,
                (float)width, // original_size (h, w)
                0.0f,
                0.0f, // crop_top_left (top, left)
                (float)height,
                (float)width, // target_size (h, w)
            };
            pooled_uncond = pooled2.data();
            pooled_cond = pooled2.data() + (size_t)pooled_dim;
        }

        std::vector<float> pred_uncond(latent_size);
        std::vector<float> pred_cond(latent_size);

        // Bug fix #2 (2026-07 単色青画像):
        //   OpenCL 上では resizeSession 後の session-input tensor が返す
        //   host<float>() が有効な CPU バッキングを持たない端末があり、直接
        //   memcpy しても書き込みが破棄される (VAE 側と同じ症状)。
        //   VAE と同じ「別途 CAFFE ホストテンソルを create → memcpy →
        //   copyFromHostTensor」で統一する。出力側も copyToHostTensor で
        //   別途構築したホストテンソルに引き取り、NaN/Inf 検知を挟む。
        //
        // Bug fix #3 (2026-07 OpenCL ノイズ画像):
        //   上の #2 で Tensor::create<float>(shape, nullptr, CAFFE) という
        //   「独立バッファを新規に持つ Tensor」を作って copyFromHostTensor /
        //   copyToHostTensor の引数に渡していたが、これは MNN が想定する
        //   「device tensor から派生させた host mirror」パターンではない。
        //   OpenCL バックエンドでは、変換元/先の Tensor が
        //   HostAllocType の互換性チェックに通らない組み合わせだと、
        //   変換パスが暗黙にスキップされたり、書き込みが破棄されるケースが
        //   確認されている (CPU では素通りするため症状が出ない)。
        //   参照実装 (xororz/local-dream, PipelineSd15Cpu.hpp) は一貫して
        //   `new MNN::Tensor(deviceTensor, MNN::Tensor::CAFFE)` という
        //   コンストラクタ版 (device tensor から派生) を使っており、
        //   CPU/OpenCL 両方で同一の絵が出ている。同じパターンに統一する。
        //
        // Perf (2026-08 高速化ミニマルセット / A-1 + A-2):
        //   ホストミラーテンソル (host_s / host_ts / host_e / host_p / host_t
        //   / host_out) は「デバイステンソルから派生した CAFFE ミラー」で、
        //   生存期間はデバイステンソル (u_sample など) と同じ session に紐づく。
        //   ステップ跨ぎで生存させても正しいので、denoise ループの外で 1 本ずつ
        //   確保して再利用する。20 ステップ CFG=2 で数百回発生していた new/
        //   delete と OpenCL コマンドキューの派生ミラー生成コストを撲滅する。
        //
        //   さらに encoder_hidden_states / text_embeds / time_ids は "seed /
        //   prompt が同じであれば denoise ループ内で不変" のため、u_enc に
        //   対して uncond/cond で内容が違うぶんはステップ内で 2 回書き込む
        //   必要があるが (u_enc は 1 本しかない)、time_ids はサイドを問わず
        //   同一なので「denoise ループの外で 1 回だけ書き込む」。text_embeds
        //   はサイドで内容が違うので u_enc と同じ扱い (ステップ内 2 回)。
        //   実質的な削減: SDXL 20step で time_ids のアップロードが 40 回 →
        //   1 回に (6 float なのでバイト数は微小だが、OpenCL では 1 回の
        //   copyFromHostTensor でもコマンドキュー投入コストが乗るので効く)。
        std::unique_ptr<MNN::Tensor> host_s(new MNN::Tensor(u_sample, MNN::Tensor::CAFFE));
        std::unique_ptr<MNN::Tensor> host_ts(new MNN::Tensor(u_ts, MNN::Tensor::CAFFE));
        std::unique_ptr<MNN::Tensor> host_e(new MNN::Tensor(u_enc, MNN::Tensor::CAFFE));
        std::unique_ptr<MNN::Tensor> host_p; // SDXL only
        std::unique_ptr<MNN::Tensor> host_t; // SDXL only
        if (!host_s || host_s->elementSize() != latent_size)
            return MNN_SD_ERR_INTERNAL;
        if (!host_ts || host_ts->elementSize() != 1)
            return MNN_SD_ERR_INTERNAL;
        if (!host_e || (size_t)host_e->elementSize() != (size_t)seq_len * unet_ctx_dim)
            return MNN_SD_ERR_INTERNAL;
        if (is_sdxl)
        {
            if (!u_text_embeds || !u_time_ids)
                return MNN_SD_ERR_INTERNAL;
            host_p.reset(new MNN::Tensor(u_text_embeds, MNN::Tensor::CAFFE));
            host_t.reset(new MNN::Tensor(u_time_ids, MNN::Tensor::CAFFE));
            if (!host_p || host_p->elementSize() != pooled_dim)
                return MNN_SD_ERR_INTERNAL;
            if (!host_t || (size_t)host_t->elementSize() != time_ids_vec.size())
                return MNN_SD_ERR_INTERNAL;
            // time_ids は uncond / cond で同一 → denoise ループの外で 1 回だけ書く
            std::memcpy(host_t->host<float>(), time_ids_vec.data(),
                        time_ids_vec.size() * sizeof(float));
            u_time_ids->copyFromHostTensor(host_t.get());
        }

        // 出力ホストミラーも常駐化。out テンソルへの派生は初回 runSession 前でも
        // resizeSession 後であれば安全に取得できる。
        auto *out_t_probe = unet_net->getSessionOutput(engine->unet_session, "out_sample");
        if (!out_t_probe)
        {
            const auto &all_out = unet_net->getSessionOutputAll(engine->unet_session);
            if (all_out.size() == 1)
                out_t_probe = all_out.begin()->second;
        }
        if (!out_t_probe)
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "UNet: 'out_sample' output tensor not found");
            return MNN_SD_ERR_INTERNAL;
        }
        std::unique_ptr<MNN::Tensor> host_out(new MNN::Tensor(out_t_probe, MNN::Tensor::CAFFE));
        if (!host_out || host_out->elementSize() < latent_size)
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "UNet: failed to build host mirror for 'out_sample'");
            return MNN_SD_ERR_INTERNAL;
        }

        auto run_unet_once = [&](const float *emb_ptr, const float *pooled_ptr, int ts,
                                 const float *sample_ptr,
                                 std::vector<float> &out_pred,
                                 const char *tag, int step_index) -> bool
        {
            // Upload sample (毎ステップ変わる)
            std::memcpy(host_s->host<float>(), sample_ptr, latent_bytes);
            u_sample->copyFromHostTensor(host_s.get());

            // Upload timestep (int32, 毎ステップ変わる)
            *host_ts->host<int>() = ts;
            u_ts->copyFromHostTensor(host_ts.get());

            // Upload encoder_hidden_states for this side (uncond/cond で異なる)
            std::memcpy(host_e->host<float>(), emb_ptr, emb_bytes);
            u_enc->copyFromHostTensor(host_e.get());

            // SDXL: text_embeds はサイド (uncond/cond) 別。time_ids は上で 1 回だけ
            // 書いてあるので、ここでは text_embeds のみ更新する。
            if (is_sdxl)
            {
                if (!pooled_ptr)
                    return false;
                std::memcpy(host_p->host<float>(), pooled_ptr, (size_t)pooled_dim * sizeof(float));
                u_text_embeds->copyFromHostTensor(host_p.get());
            }

            unet_net->runSession(engine->unet_session);

            // 出力: 常駐 host_out ミラーに引き取る。
            out_t_probe->copyToHostTensor(host_out.get());
            std::memcpy(out_pred.data(), host_out->host<float>(), latent_bytes);

            // Diagnostic: dump the first few raw values on step 0 so they can
            // be diffed against a CPU-backend run with the same seed. If the
            // *set* of values matches but the *order* differs, that points to
            // a layout (NC4HW4<->CAFFE) conversion bug rather than a numeric
            // one.
            if (step_index == 0)
            {
                char buf[256];
                int off = std::snprintf(buf, sizeof(buf), "UNet %s step=0 raw[0..7]:", tag);
                for (int k = 0; k < 8 && k < latent_size; ++k)
                    off += std::snprintf(buf + off, sizeof(buf) - off, " %.5f", out_pred[k]);
                PROBE_LOG("%s", buf);
            }

            // Bug fix #2 diagnostic: check UNet output stats. If everything
            // is 0 / same value / NaN, the FP16 pipeline is silently broken
            // and continuing would give the user a solid-color image.
            {
                bool any_nan = false;
                float vmin = out_pred[0], vmax = out_pred[0];
                double sum = 0.0, sq = 0.0;
                for (int k = 0; k < latent_size; ++k)
                {
                    float v = out_pred[k];
                    if (v != v || v > 1e30f || v < -1e30f)
                    {
                        any_nan = true;
                        break;
                    }
                    if (v < vmin)
                        vmin = v;
                    if (v > vmax)
                        vmax = v;
                    sum += v;
                    sq += (double)v * v;
                }
                // Log stats on first and last denoise step.
                //   num_solver_iters is declared *below* this lambda, so we
                //   can't capture it. timesteps was already captured by
                //   reference and .size() gives us the same value.
                const int last_idx = (int)timesteps.size() - 1;
                if (step_index == 0 || step_index == last_idx)
                {
                    double mean = sum / latent_size;
                    double var = sq / latent_size - mean * mean;
                    PROBE_LOG("UNet %s step=%d min=%.4f max=%.4f mean=%.4f std=%.4f",
                              tag, step_index, vmin, vmax, mean,
                              var > 0 ? std::sqrt(var) : 0.0);
                }
                if (any_nan || (vmax - vmin) < 1e-6f)
                {
                    PROBE_LOG("UNet %s step=%d DEGENERATE nan=%d min=%.4f max=%.4f",
                              tag, step_index, any_nan ? 1 : 0, vmin, vmax);
                    return false;
                }
            }
            return true;
        };

        const int num_solver_iters = (int)timesteps.size();
        // img2img: t_start_index の手前の step は skip する (add_noise ですでに
        //   そのノイズレベルに latent を合わせてある)。txt2img は 0 から回る。
        const int denoise_start = is_img2img ? std::max(0, std::min(t_start_index, num_solver_iters - 1)) : 0;
        if (is_img2img)
        {
            PROBE_LOG("denoise loop: start=%d end=%d (img2img partial)", denoise_start, num_solver_iters);
        }
        // Perf (2026-08 高速化ミニマルセット / C):
        //   CFG combined バッファを denoise ループの外で 1 本確保して再利用する。
        //   毎ステップ std::vector<float>(latent_size) を new して free する
        //   コストが積み重なる (SDXL 1024 で N=131072、20 step で 2〜4MB 分の
        //   malloc/free)。allocator コンテンションを減らして latent が
        //   L2 に残る確率も上げる。
        std::vector<float> combined(latent_size);
        std::vector<float> unet_sample;
        for (int i = denoise_start; i < num_solver_iters; ++i)
        {
            if (engine->cancel_requested)
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message), "cancelled");
                return MNN_SD_ERR_CANCELLED;
            }

            int ts = timesteps[i];
            const float *sample_ptr = latent.data();
            if (active_scheduler == ActiveScheduler::EULER_A &&
                i < (int)sigmas.size())
            {
                // solver 状態は k-diffusion (latent *= sigma)、UNet は VP。
                // x_vp = x_k / sqrt(sigma^2 + 1) に戻してから食わせる。
                const float sig = std::max(sigmas[i], 1e-6f);
                scale_k_to_vp(latent, sig, unet_sample);
                sample_ptr = unet_sample.data();
                ts = timestep_from_sigma(sig, engine->alphas_cumprod);
            }
            else if (active_scheduler == ActiveScheduler::DPMPP_2M_KARRAS &&
                     i < (int)sigmas.size())
            {
                ts = timestep_from_sigma(sigmas[i], engine->alphas_cumprod);
            }

            if (!run_unet_once(emb_uncond, pooled_uncond, ts, sample_ptr, pred_uncond, "uncond", i) ||
                !run_unet_once(emb_cond, pooled_cond, ts, sample_ptr, pred_cond, "cond", i))
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "UNet: run failed or produced degenerate output at step %d "
                                  "(likely FP16 overflow \u2014 UNet must run at Precision_High).",
                                  i);
                return MNN_SD_ERR_INTERNAL;
            }

            // CFG: noise_pred = uncond + cfg * (cond - uncond)
            // 少ステップ (<=15) × 高CFG (>=6) では、Lin 2024 の CFG Rescale を
            // 併用して過飽和 / 焼き付きを抑える。φ の値は経験則で 0.7 が無難。
            // (φ=0.0 は CFG Rescale 完全無効)
            for (int j = 0; j < latent_size; ++j)
                combined[j] = pred_uncond[j] + cfg * (pred_cond[j] - pred_uncond[j]);

            // Bug fix (暗黙の CFG Rescale で色が歪む / 2026-07):
            //   旧実装は steps<=15 かつ cfg>=6 で phi=0.7 の CFG Rescale を
            //   自動でかけていたが、ユーザーに見えない操作として色分布を
            //   歪める副作用があり、緑や青のカラーキャストを誘発していた。
            //   明示的に有効化するまで標準の CFG (phi=0) だけを使う。

            switch (active_scheduler)
            {
            case ActiveScheduler::DDIM:
                latent = ddim_step(latent, combined, i, timesteps, engine->alphas_cumprod);
                break;
            case ActiveScheduler::EULER:
                latent = euler_step(latent, combined, i, timesteps, engine->alphas_cumprod);
                break;
            case ActiveScheduler::EULER_A:
                latent = euler_a_step(latent, combined, i, sigmas, sched_rng);
                break;
            case ActiveScheduler::LCM_STEP:
                latent = lcm_step(latent, combined, i, timesteps,
                                  engine->alphas_cumprod, sched_rng);
                break;
            case ActiveScheduler::DPMPP_2M:
            case ActiveScheduler::DPMPP_2M_KARRAS:
            {
                std::vector<float> x0_next;
                latent = dpmpp_2m_step(latent, combined, i, sigmas,
                                       timesteps, engine->alphas_cumprod,
                                       x0_next, dpmpp_x0_prev);
                dpmpp_x0_prev = std::move(x0_next);
                break;
            }
            case ActiveScheduler::DPM_SOLVER_2:
            {
                std::vector<float> eps_next;
                latent = dpm_solver_2_step(latent, combined, i, sigmas,
                                           eps_next, dpm_eps_prev);
                dpm_eps_prev = std::move(eps_next);
                break;
            }
            case ActiveScheduler::UNIPC:
                latent = unipc_step(std::move(latent), combined, i, sigmas, unipc_state);
                break;
            case ActiveScheduler::PLMS:
            default:
                latent = pndm_step(latent, combined, i, timesteps,
                                   engine->alphas_cumprod, ets, pndm_prev);
                break;
            }

            // Progress は active_scheduler に応じて変わる:
            //   PLMS: timesteps.size() = steps+1 なので bootstrap 2 回目をステップ 1 に折りたたむ。
            //   それ以外: timesteps.size() = steps なので i そのままで OK。
            if (on_progress)
            {
                int visible_step;
                if (active_scheduler == ActiveScheduler::PLMS)
                {
                    if (i <= 1)
                        visible_step = 1;
                    else
                        visible_step = i;
                }
                else
                {
                    visible_step = i + 1;
                }
                if (visible_step > steps)
                    visible_step = steps;
                MnnSdProgress p{visible_step, steps, 0.0f};
                on_progress(&p, progress_user_data);
            }
        }

        // Free UNet before VAE (~860 MB back to the OS on CuteYukiMix).
        //
        // Bug fix (2026-08 高速化パッチ・フォローアップ / OpenCL VAE NaN):
        //   ホストミラーテンソル (host_s / host_ts / host_e / host_p /
        //   host_t / host_out) は "デバイステンソルから派生した CAFFE
        //   ミラー" で、寿命は派生元 (u_sample 等) が生きている間に閉じる
        //   必要がある。denoise ループ外に格上げしたことで、放置すると
        //   releaseSession() / reset() で派生元が消えた後に unique_ptr の
        //   デストラクタが動く。OpenCL バックエンドではこの寿命反転が
        //   OpenCL コンテキスト / 共有バッファプールを不整合にし、直後の
        //   VAE decoder 実行を全 NaN 化させることが実機で確認された
        //   (CPU バックエンドでは無症状)。
        //   → 派生元が生きているうちに、ここで先に明示的に破棄する。
        host_s.reset();
        host_ts.reset();
        host_e.reset();
        host_p.reset();
        host_t.reset();
        host_out.reset();
        out_t_probe = nullptr;

        if (engine->unet_session)
        {
            engine->unet_interpreter->releaseSession(engine->unet_session);
            engine->unet_session = nullptr;
        }
        engine->unet_interpreter.reset();
        unet_net = nullptr;
        u_sample = u_ts = u_enc = nullptr;

        // Bug fix (scudo internal map failure at VAE createFromFile):
        //   UNet を reset しても scudo はOSにページを戻さないことがあり、
        //   直後の VAE createFromFile で新規 mmap に失敗する。
        //   malloc_trim(0) を 2 回呼び、間でスケジューリングを一旦譲ることで
        //   解放済みチャンクを OS に返す。
        PROBE_LOG("trim heap before VAE load");
        trim_heap_to_os();
        std::this_thread::yield();
        trim_heap_to_os();

        // --- 5b. Load VAE just-in-time (after UNet has been freed) ---
        //   VAE は ScheduleBundle 側で Precision_High (fp32) に固定される。
        //   これで OpenCL でも真っ白画像にならない。
        //
        // Bug fix (512x512+ OpenCL OOM kill / 2026-07):
        //   VAE decoder は fp32 (Precision_High) で動くため、512x512 以上の
        //   解像度では中間テンソルが GPU メモリを大量に消費する。
        //   UNet 完了後に OpenCL バッファプールに残存する GPU メモリと重のわって
        //   CL_OUT_OF_RESOURCES → プロセス OOM kill になる。
        //   512px 以上では VAE を CPU にフォールバックして GPU メモリ枯渇を
        //   回避する。CPU VAE は fp32 で数秒で完了するため体感影響は小さい。
        MnnSdBackend vae_backend = MNN_SD_BACKEND_CPU;
        if (effective_backend == MNN_SD_BACKEND_OPENCL)
        {
            PROBE_LOG("VAE forced CPU: OpenCL VAE at 256px leaves a mesh/grain overlay "
                      "(Mali/Adreno TUNING_FAST). Decode is one shot, CPU is fine.");
        }
        {
            MnnSdError err = create_interpreter_and_session(
                engine->vae_path, vae_backend, SdModelKind::VAE,
                false,
                engine->vae_interpreter, engine->vae_session, out_error,
                width);
            if (err != MNN_SD_OK)
            {
                trim_heap_to_os();
                return err;
            }
            auto probe_session = [](MNN::Interpreter *net, MNN::Session *sess, const char *label)
            {
                for (const auto &kv : net->getSessionInputAll(sess))
                    PROBE_LOG("%s input: %s", label, kv.first.c_str());
                for (const auto &kv : net->getSessionOutputAll(sess))
                    PROBE_LOG("%s output: %s", label, kv.first.c_str());
            };
            probe_session(engine->vae_interpreter.get(), engine->vae_session, "VAE");
        }

        // --- 6. VAE decode: explicit resize to {1, 4, lh, lw} ---
        // scale latent: SD1.5 uses 0.18215, SDXL's VAE was retrained with a
        // different scale (0.13025). Using the wrong constant here produces
        // a washed-out or oversaturated image without any other symptom.
        const float vae_scale = is_sdxl ? 0.13025f : 0.18215f;
        for (auto &v : latent)
            v /= vae_scale;

        auto *vae_net = engine->vae_interpreter.get();
        auto *v_input = vae_net->getSessionInput(engine->vae_session, "latent_sample");
        if (!v_input)
        {
            const auto &all_in = vae_net->getSessionInputAll(engine->vae_session);
            if (all_in.size() == 1)
                v_input = all_in.begin()->second;
        }
        if (!v_input)
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "VAE: latent_sample tensor not found");
            return MNN_SD_ERR_INTERNAL;
        }
        vae_net->resizeTensor(v_input, {1, 4, lh, lw});
        vae_net->resizeSession(engine->vae_session);
        v_input = vae_net->getSessionInput(engine->vae_session, "latent_sample");

        // Bug fix (GPU 真っ白の副因):
        //   OpenCL バックエンドでは resizeSession 直後の session-input tensor に
        //   対する host<float>() が有効な CPU バッキングを返さない端末がある
        //   (Adreno 6xx + MNN 3.6 で実測)。書き込みが実質的に破棄され、UNet が
        //   出した意味のある latent ではなく未初期化のゼロが VAE に入ってしまい、
        //   fp32 で走らせても VAE のバイアス項だけで灰色 or 白に潰れる。
        //   ここは CLIP / UNet と同じ手順、つまり CAFFE レイアウトのホスト側
        //   一時テンソルを別途構築 → memcpy → copyFromHostTensor で書き込む。
        {
            std::unique_ptr<MNN::Tensor> host_v(new MNN::Tensor(v_input, MNN::Tensor::CAFFE));
            if (!host_v || host_v->elementSize() != (int)latent.size())
            {
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "VAE: failed to build host input tensor");
                return MNN_SD_ERR_INTERNAL;
            }
            std::memcpy(host_v->host<float>(), latent.data(),
                        latent.size() * sizeof(float));
            v_input->copyFromHostTensor(host_v.get());
        }
        vae_net->runSession(engine->vae_session);
        auto image_f = read_output_f32(vae_net, engine->vae_session, "sample");
        if (image_f.empty())
        {
            if (out_error)
                std::snprintf(out_error->message, sizeof(out_error->message),
                              "VAE: sample output empty");
            return MNN_SD_ERR_INTERNAL;
        }

        // Sanity check: FP16 オーバーフローの痕跡 (全画素が同一 or NaN) を検出。
        //   Precision_High に上げているので通常はここに引っかからないが、
        //   万一ドライバが precision hint を無視した場合に沈黙して真っ白を
        //   ユーザーに見せるより、ここで明示的にエラーを上げた方が親切。
        {
            bool any_nan = false;
            float vmin = image_f[0];
            float vmax = image_f[0];
            const size_t N = image_f.size();
            for (size_t k = 0; k < N; ++k)
            {
                float v = image_f[k];
                if (v != v)
                {
                    any_nan = true;
                    break;
                }
                if (v < vmin)
                    vmin = v;
                if (v > vmax)
                    vmax = v;
            }
            if (any_nan || (vmax - vmin) < 1e-4f)
            {
                PROBE_LOG("VAE output degenerate: nan=%d min=%.4f max=%.4f (backend=%d)",
                          any_nan ? 1 : 0, vmin, vmax, static_cast<int>(effective_backend));
                if (out_error)
                    std::snprintf(out_error->message, sizeof(out_error->message),
                                  "VAE decode produced a flat/NaN image on backend=%d "
                                  "(likely FP16 overflow \u2014 check that Precision_High reached the driver).",
                                  static_cast<int>(effective_backend));
                return MNN_SD_ERR_INTERNAL;
            }
        }

        // Free VAE now — the RGB copy below only needs image_f.
        if (engine->vae_session)
        {
            engine->vae_interpreter->releaseSession(engine->vae_session);
            engine->vae_session = nullptr;
        }
        engine->vae_interpreter.reset();
        trim_heap_to_os();

        // image_f: [1, 3, H, W] NCHW, range ~[-1, 1] -> clamp to [0,1] -> uint8 RGB
        //
        // Improvement (小さい画像で目立つ細かい粒状ノイズ / 量子化バンディング):
        //   VAE 出力は float のまま滑らかだが、最後に uint8 へ 1/255 刻みで
        //   量子化する際、なだらかなグラデーション (空・肌・壁) で ±1 LSB の
        //   段差が規則的な縞/粒子として現れ、256x256 級の小さい画像では
        //   1 ピクセルあたりの視覚重みが大きいため特に目立つ。
        //   4x4 Bayer の ordered dithering で量子化誤差を空間的に散らし、
        //   バンディングと粒状感を知覚的に打ち消す。誤差は ±1 LSB 以内で
        //   色味やディテールは変わらない (sd-webui / 各種画像パイプラインと
        //   同じ手法)。VAE デコード結果そのものは一切触らない。
        const int pixels = width * height;
        uint8_t *rgb = new uint8_t[pixels * 3];
        static const int kBayer4x4[4][4] = {
            {0, 8, 2, 10},
            {12, 4, 14, 6},
            {3, 11, 1, 9},
            {15, 7, 13, 5},
        };
        for (int y = 0; y < height; ++y)
        {
            for (int x = 0; x < width; ++x)
            {
                const int p = y * width + x;
                const float dither = ((float)kBayer4x4[y & 3][x & 3] + 0.5f) / 16.0f - 0.5f; // [-0.5, 0.5)
                for (int c = 0; c < 3; ++c)
                {
                    float v = image_f[c * pixels + p] * 0.5f + 0.5f;
                    v = v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
                    rgb[p * 3 + c] = (uint8_t)(v * 255.0f + 0.5f + dither);
                }
            }
        }

        out_image->width = width;
        out_image->height = height;
        out_image->channels = 3;
        out_image->data = rgb;
        out_image->data_size = pixels * 3;

        if (on_progress)
        {
            // VAE 完了通知: step は steps に固定 (100%)、total_steps も steps。
            MnnSdProgress p{steps, steps, 0.0f};
            on_progress(&p, progress_user_data);
        }
        return MNN_SD_OK;
#endif
    }

} // extern "C"
