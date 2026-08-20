#include "mnn_sd/model_config.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace
{

    void set_default(MnnSdModelConfig *cfg)
    {
        std::snprintf(cfg->format, sizeof(cfg->format), "%s", "mnn-sd15-v1");
        std::snprintf(cfg->base, sizeof(cfg->base), "%s", "sd1.5");
        std::snprintf(cfg->clip_file, sizeof(cfg->clip_file), "%s", "clip.mnn");
        std::snprintf(cfg->unet_file, sizeof(cfg->unet_file), "%s", "unet.mnn");
        std::snprintf(cfg->vae_decoder_file, sizeof(cfg->vae_decoder_file), "%s", "vae_decoder.mnn");
        // img2img 用 VAE encoder はデフォルトでは空 (モデルになければ img2img 不可)。
        cfg->vae_encoder_file[0] = '\0';
        std::snprintf(cfg->tokenizer_file, sizeof(cfg->tokenizer_file), "%s", "tokenizer.json");
        cfg->clip_skip = 2;
        cfg->text_embedding_size = 768;
        cfg->default_size = 512;

        cfg->is_sdxl = 0;
        cfg->clip2_file[0] = '\0';
        cfg->tokenizer2_file[0] = '\0';
        cfg->text_embedding_size_2 = 0;
        cfg->pooled_embedding_size = 0;
    }

    bool file_exists(const char *dir, const char *name)
    {
        char path[1024];
        std::snprintf(path, sizeof(path), "%s/%s", dir, name);
        FILE *f = std::fopen(path, "rb");
        if (!f)
            return false;
        std::fclose(f);
        return true;
    }

    void pick_clip_file(const char *dir, MnnSdModelConfig *cfg)
    {
        const char *candidates[] = {"clip_v2.mnn", "clip_fp16.mnn", "clip.mnn", "clip1.mnn"};
        for (const char *name : candidates)
        {
            if (file_exists(dir, name))
            {
                std::snprintf(cfg->clip_file, sizeof(cfg->clip_file), "%s", name);
                return;
            }
        }
        std::snprintf(cfg->clip_file, sizeof(cfg->clip_file), "%s", "clip.mnn");
    }

    void pick_unet_file(const char *dir, MnnSdModelConfig *cfg)
    {
        const char *candidates[] = {"unet_asym_block32.mnn", "unet.mnn", "unet_min.bin"};
        for (const char *name : candidates)
        {
            if (file_exists(dir, name))
            {
                std::snprintf(cfg->unet_file, sizeof(cfg->unet_file), "%s", name);
                return;
            }
        }
        std::snprintf(cfg->unet_file, sizeof(cfg->unet_file), "%s", "unet.mnn");
    }

    void pick_clip2_file(const char *dir, MnnSdModelConfig *cfg)
    {
        const char *candidates[] = {"clip2.mnn", "clip_g.mnn", "clip2_fp16.mnn"};
        for (const char *name : candidates)
        {
            if (file_exists(dir, name))
            {
                std::snprintf(cfg->clip2_file, sizeof(cfg->clip2_file), "%s", name);
                return;
            }
        }
        cfg->clip2_file[0] = '\0';
    }

    void pick_tokenizer2_file(const char *dir, MnnSdModelConfig *cfg)
    {
        const char *candidates[] = {"tokenizer_2.json", "tokenizer2.json"};
        for (const char *name : candidates)
        {
            if (file_exists(dir, name))
            {
                std::snprintf(cfg->tokenizer2_file, sizeof(cfg->tokenizer2_file), "%s", name);
                return;
            }
        }
        cfg->tokenizer2_file[0] = '\0';
    }

    void pick_vae_file(const char *dir, MnnSdModelConfig *cfg)
    {
        const char *candidates[] = {"vae_decoder_fp16.mnn", "vae_decoder.mnn", "vae_decoder_min.bin"};
        for (const char *name : candidates)
        {
            if (file_exists(dir, name))
            {
                std::snprintf(cfg->vae_decoder_file, sizeof(cfg->vae_decoder_file), "%s", name);
                return;
            }
        }
        std::snprintf(cfg->vae_decoder_file, sizeof(cfg->vae_decoder_file), "%s", "vae_decoder.mnn");
    }

    // img2img 用 VAE encoder の自動検出。見つからなければ vae_encoder_file は空のまま
    // にしておき、caps.supports_img2img=0 として UI 側で img2img を無効化する。
    void pick_vae_encoder_file(const char *dir, MnnSdModelConfig *cfg)
    {
        const char *candidates[] = {"vae_encoder_fp16.mnn", "vae_encoder.mnn"};
        for (const char *name : candidates)
        {
            if (file_exists(dir, name))
            {
                std::snprintf(cfg->vae_encoder_file, sizeof(cfg->vae_encoder_file), "%s", name);
                return;
            }
        }
        cfg->vae_encoder_file[0] = '\0';
    }

    // Minimal JSON field extraction (no third_party JSON dep for Phase 1).
    bool extract_string(const char *json, const char *key, char *out, size_t out_cap)
    {
        char pattern[64];
        std::snprintf(pattern, sizeof(pattern), "\"%s\"", key);
        const char *pos = std::strstr(json, pattern);
        if (!pos)
            return false;
        pos = std::strchr(pos + std::strlen(pattern), '"');
        if (!pos)
            return false;
        ++pos;
        const char *end = std::strchr(pos, '"');
        if (!end || end <= pos)
            return false;
        size_t len = static_cast<size_t>(end - pos);
        if (len >= out_cap)
            len = out_cap - 1;
        std::memcpy(out, pos, len);
        out[len] = '\0';
        return true;
    }

    bool extract_int(const char *json, const char *key, int32_t *out)
    {
        char pattern[64];
        std::snprintf(pattern, sizeof(pattern), "\"%s\"", key);
        const char *pos = std::strstr(json, pattern);
        if (!pos)
            return false;
        pos = std::strchr(pos + std::strlen(pattern), ':');
        if (!pos)
            return false;
        ++pos;
        while (*pos == ' ' || *pos == '\t')
            ++pos;
        *out = static_cast<int32_t>(strtol(pos, nullptr, 10));
        return true;
    }

} // namespace

extern "C"
{

    int mnn_sd_load_model_config(const char *model_dir, MnnSdModelConfig *out_config)
    {
        if (!model_dir || !out_config)
            return 0;
        set_default(out_config);
        pick_clip_file(model_dir, out_config);
        pick_unet_file(model_dir, out_config);
        pick_vae_file(model_dir, out_config);
        pick_vae_encoder_file(model_dir, out_config);

        char path[1024];
        std::snprintf(path, sizeof(path), "%s/model.json", model_dir);
        FILE *f = std::fopen(path, "rb");
        if (!f)
            return 1;

        char buf[4096];
        size_t n = std::fread(buf, 1, sizeof(buf) - 1, f);
        std::fclose(f);
        buf[n] = '\0';

        extract_string(buf, "format", out_config->format, sizeof(out_config->format));
        extract_string(buf, "base", out_config->base, sizeof(out_config->base));
        // Accept both plain SD1.5 keys ("clip") and the SDXL exporter's keys
        // ("clip1"/"tokenizer1") for the first CLIP/tokenizer.
        if (!extract_string(buf, "clip1", out_config->clip_file, sizeof(out_config->clip_file)))
        {
            extract_string(buf, "clip", out_config->clip_file, sizeof(out_config->clip_file));
        }
        extract_string(buf, "unet", out_config->unet_file, sizeof(out_config->unet_file));
        extract_string(buf, "vae_decoder", out_config->vae_decoder_file, sizeof(out_config->vae_decoder_file));
        // img2img: model.json に vae_encoder キーがあれば優先し、
        // なければ pick_vae_encoder_file で自動検出した値を保持。
        {
            char encoder_key[64] = {0};
            if (extract_string(buf, "vae_encoder", encoder_key, sizeof(encoder_key)) &&
                encoder_key[0] != '\0' && file_exists(model_dir, encoder_key))
            {
                std::snprintf(out_config->vae_encoder_file, sizeof(out_config->vae_encoder_file), "%s", encoder_key);
            }
        }
        if (!extract_string(buf, "tokenizer1", out_config->tokenizer_file, sizeof(out_config->tokenizer_file)))
        {
            extract_string(buf, "tokenizer", out_config->tokenizer_file, sizeof(out_config->tokenizer_file));
        }
        extract_int(buf, "clip_skip", &out_config->clip_skip);
        extract_int(buf, "text_embedding_size", &out_config->text_embedding_size);
        extract_int(buf, "default_size", &out_config->default_size);

        // --- SDXL detection & fields ---
        // "base": "sdxl" is the authoritative signal (set by the SDXL
        // converter script). Fall back to presence of a clip2/tokenizer2
        // entry so hand-edited model.json files still work.
        out_config->is_sdxl = 0;
        if (std::strcmp(out_config->base, "sdxl") == 0)
        {
            out_config->is_sdxl = 1;
        }

        char clip2_key[64] = {0};
        char tok2_key[64] = {0};
        bool has_clip2 = extract_string(buf, "clip2", clip2_key, sizeof(clip2_key));
        bool has_tok2 = extract_string(buf, "tokenizer2", tok2_key, sizeof(tok2_key));
        if (has_clip2 || has_tok2)
        {
            out_config->is_sdxl = 1;
        }

        if (out_config->is_sdxl)
        {
            if (has_clip2)
            {
                std::snprintf(out_config->clip2_file, sizeof(out_config->clip2_file), "%s", clip2_key);
            }
            else
            {
                pick_clip2_file(model_dir, out_config);
            }
            if (has_tok2)
            {
                std::snprintf(out_config->tokenizer2_file, sizeof(out_config->tokenizer2_file), "%s", tok2_key);
            }
            else
            {
                pick_tokenizer2_file(model_dir, out_config);
            }

            // SDXL text_embedding_size in model.json (from the converter) is
            // the *concatenated* CLIP-L+CLIP-G width (2048), not CLIP-L's own
            // width. UNet's encoder_hidden_states uses that concatenated
            // value, so keep it as-is in text_embedding_size, but also derive
            // the two individual widths (768 for CLIP-L, remainder for
            // CLIP-G) so the pipeline can size each CLIP's input/output
            // tensors correctly.
            int32_t combined = out_config->text_embedding_size;
            int32_t clip1_dim = 768; // CLIP-L (ViT-L/14) hidden size, standard for SDXL
            if (combined > clip1_dim)
            {
                out_config->text_embedding_size_2 = combined - clip1_dim;
                out_config->text_embedding_size = clip1_dim;
            }
            else if (out_config->text_embedding_size_2 <= 0)
            {
                // Fallback: standard SDXL widths if JSON only gave us the
                // combined value or something unexpected.
                out_config->text_embedding_size = clip1_dim;
                out_config->text_embedding_size_2 = 1280;
            }

            if (out_config->pooled_embedding_size <= 0)
            {
                // CLIP-G's pooled_output width feeds UNet's add_embedding
                // and is conventionally equal to its hidden size (1280).
                out_config->pooled_embedding_size = out_config->text_embedding_size_2;
            }

            if (out_config->default_size < 1024)
            {
                out_config->default_size = 1024;
            }
        }

        return 1;
    }

} // extern "C"

