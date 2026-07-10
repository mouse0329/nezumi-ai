#include "mnn_sd/model_config.h"

#include <cstdio>
#include <cstring>

namespace {

void set_default(MnnSdModelConfig* cfg) {
    std::snprintf(cfg->format, sizeof(cfg->format), "%s", "mnn-sd15-v1");
    std::snprintf(cfg->base, sizeof(cfg->base), "%s", "sd1.5");
    std::snprintf(cfg->clip_file, sizeof(cfg->clip_file), "%s", "clip.mnn");
    std::snprintf(cfg->unet_file, sizeof(cfg->unet_file), "%s", "unet.mnn");
    std::snprintf(cfg->vae_decoder_file, sizeof(cfg->vae_decoder_file), "%s", "vae_decoder.mnn");
    std::snprintf(cfg->tokenizer_file, sizeof(cfg->tokenizer_file), "%s", "tokenizer.json");
    cfg->clip_skip = 2;
    cfg->text_embedding_size = 768;
    cfg->default_size = 512;
}

bool file_exists(const char* dir, const char* name) {
    char path[1024];
    std::snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE* f = std::fopen(path, "rb");
    if (!f) return false;
    std::fclose(f);
    return true;
}

void pick_clip_file(const char* dir, MnnSdModelConfig* cfg) {
    const char* candidates[] = {"clip_v2.mnn", "clip_fp16.mnn", "clip.mnn"};
    for (const char* name : candidates) {
        if (file_exists(dir, name)) {
            std::snprintf(cfg->clip_file, sizeof(cfg->clip_file), "%s", name);
            return;
        }
    }
    std::snprintf(cfg->clip_file, sizeof(cfg->clip_file), "%s", "clip.mnn");
}

void pick_unet_file(const char* dir, MnnSdModelConfig* cfg) {
    const char* candidates[] = {"unet_asym_block32.mnn", "unet.mnn", "unet_min.bin"};
    for (const char* name : candidates) {
        if (file_exists(dir, name)) {
            std::snprintf(cfg->unet_file, sizeof(cfg->unet_file), "%s", name);
            return;
        }
    }
    std::snprintf(cfg->unet_file, sizeof(cfg->unet_file), "%s", "unet.mnn");
}

void pick_vae_file(const char* dir, MnnSdModelConfig* cfg) {
    const char* candidates[] = {"vae_decoder_fp16.mnn", "vae_decoder.mnn", "vae_decoder_min.bin"};
    for (const char* name : candidates) {
        if (file_exists(dir, name)) {
            std::snprintf(cfg->vae_decoder_file, sizeof(cfg->vae_decoder_file), "%s", name);
            return;
        }
    }
    std::snprintf(cfg->vae_decoder_file, sizeof(cfg->vae_decoder_file), "%s", "vae_decoder.mnn");
}

// Minimal JSON field extraction (no third_party JSON dep for Phase 1).
bool extract_string(const char* json, const char* key, char* out, size_t out_cap) {
    char pattern[64];
    std::snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char* pos = std::strstr(json, pattern);
    if (!pos) return false;
    pos = std::strchr(pos + std::strlen(pattern), '"');
    if (!pos) return false;
    ++pos;
    const char* end = std::strchr(pos, '"');
    if (!end || end <= pos) return false;
    size_t len = static_cast<size_t>(end - pos);
    if (len >= out_cap) len = out_cap - 1;
    std::memcpy(out, pos, len);
    out[len] = '\0';
    return true;
}

bool extract_int(const char* json, const char* key, int32_t* out) {
    char pattern[64];
    std::snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char* pos = std::strstr(json, pattern);
    if (!pos) return false;
    pos = std::strchr(pos + std::strlen(pattern), ':');
    if (!pos) return false;
    ++pos;
    while (*pos == ' ' || *pos == '\t') ++pos;
    *out = static_cast<int32_t>(std::strtol(pos, nullptr, 10));
    return true;
}

}  // namespace

extern "C" {

int mnn_sd_load_model_config(const char* model_dir, MnnSdModelConfig* out_config) {
    if (!model_dir || !out_config) return 0;
    set_default(out_config);
    pick_clip_file(model_dir, out_config);
    pick_unet_file(model_dir, out_config);
    pick_vae_file(model_dir, out_config);

    char path[1024];
    std::snprintf(path, sizeof(path), "%s/model.json", model_dir);
    FILE* f = std::fopen(path, "rb");
    if (!f) return 1;

    char buf[4096];
    size_t n = std::fread(buf, 1, sizeof(buf) - 1, f);
    std::fclose(f);
    buf[n] = '\0';

    extract_string(buf, "format", out_config->format, sizeof(out_config->format));
    extract_string(buf, "base", out_config->base, sizeof(out_config->base));
    extract_string(buf, "clip", out_config->clip_file, sizeof(out_config->clip_file));
    extract_string(buf, "unet", out_config->unet_file, sizeof(out_config->unet_file));
    extract_string(buf, "vae_decoder", out_config->vae_decoder_file, sizeof(out_config->vae_decoder_file));
    extract_string(buf, "tokenizer", out_config->tokenizer_file, sizeof(out_config->tokenizer_file));
    extract_int(buf, "clip_skip", &out_config->clip_skip);
    extract_int(buf, "text_embedding_size", &out_config->text_embedding_size);
    extract_int(buf, "default_size", &out_config->default_size);
    return 1;
}

}  // extern "C"
