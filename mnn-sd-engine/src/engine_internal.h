#pragma once

#include "mnn_sd/engine.h"
#include "mnn_sd/model_config.h"

#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace MNN
{
    class Interpreter;
    class Session;
} // namespace MNN

// Minimal BPE tokenizer for CLIP (SD1.5)
// Loads vocab + merges from tokenizer.json (HuggingFace BPE format)
struct ClipTokenizer
{
    static constexpr int BOS_ID = 49406;
    static constexpr int EOS_ID = 49407;
    static constexpr int MAX_LEN = 77;

    std::unordered_map<std::string, int> vocab;   // token -> id
    std::vector<std::pair<std::string, std::string>> merges; // BPE merge rules

    bool load(const std::string &tokenizer_json_path);

    // Returns [2 * MAX_LEN] ids: uncond (negative_prompt) + cond (prompt).
    // When negative_prompt is empty this degenerates to the classic
    // Stable-Diffusion "empty prompt" uncond (BOS + EOS + pad).
    std::vector<int> encode_pair(const std::string &prompt,
                                 const std::string &negative_prompt) const;

    // Backwards-compatible overload (uncond = empty prompt).
    inline std::vector<int> encode_pair(const std::string &prompt) const {
        return encode_pair(prompt, std::string());
    }

private:
    std::vector<int> encode_single(const std::string &text) const;
    std::string bpe(const std::string &token) const;
    // byte-level unicode -> latin1 mapping (CLIP byte fallback)
    static std::string bytes_to_unicode(unsigned char c);
};

struct MnnSdEngine
{
    bool loaded = false;
    std::string model_dir;
    MnnSdLoadOptions load_options{};
    MnnSdCapabilities caps{};
    MnnSdModelConfig model_config{};
    volatile bool cancel_requested = false;

    ClipTokenizer tokenizer;

    // xororz/local-dream embedding tables
    // token_emb: [vocab_size, 768] float32  (loaded from token_emb.bin)
    // pos_emb:   [77, 768]         float32  (loaded from pos_emb.bin)
    std::vector<float> token_emb;  // vocab_size * 768
    std::vector<float> pos_emb;    // 77 * 768
    int token_emb_vocab_size = 0;

    // Just-in-time load: paths are resolved at mnn_sd_load(), interpreters
    // are created lazily inside mnn_sd_run_pipeline and released between
    // stages to keep peak RAM below the LMK threshold on 2-3 GB devices.
    std::string clip_path;
    std::string unet_path;
    std::string vae_path;

    std::shared_ptr<MNN::Interpreter> clip_interpreter;
    std::shared_ptr<MNN::Interpreter> unet_interpreter;
    std::shared_ptr<MNN::Interpreter> vae_interpreter;

    MNN::Session *clip_session = nullptr;
    MNN::Session *unet_session = nullptr;
    MNN::Session *vae_session = nullptr;

    // PNDM scheduler alphas_cumprod[0..999]
    std::vector<float> alphas_cumprod;
    // PNDM state
    // PNDM state (plain float vectors, no MNN::Express dependency)
    std::vector<std::vector<float>> pndm_ets;
    std::vector<float> pndm_prev_sample;
};

#ifdef __cplusplus
extern "C" {
#endif

MnnSdError mnn_sd_initialize_sessions(MnnSdEngine *engine, MnnSdErrorInfo *out_error);
void mnn_sd_release_sessions(MnnSdEngine *engine);

// Run full txt2img pipeline; fills out_image on success
MnnSdError mnn_sd_run_pipeline(
    MnnSdEngine *engine,
    const MnnSdGenerateParams *params,
    MnnSdProgressFn on_progress,
    void *progress_user_data,
    MnnSdImage *out_image,
    MnnSdErrorInfo *out_error);

#ifdef __cplusplus
}
#endif
