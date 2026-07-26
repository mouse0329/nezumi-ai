#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MnnSdModelConfig {
    char format[32];
    char base[16];              /* "sd1.5" or "sdxl" */
    char clip_file[64];         /* SD1.5: single CLIP. SDXL: CLIP-L (clip1) */
    char unet_file[64];
    char vae_decoder_file[64];
    char tokenizer_file[64];    /* SD1.5: single tokenizer. SDXL: CLIP-L tokenizer */
    int32_t clip_skip;
    int32_t text_embedding_size; /* SD1.5: 768. SDXL: CLIP-L dim, usually 768 */
    int32_t default_size;

    /* --- SDXL-only fields (ignored when base == "sd1.5") --- */
    int32_t is_sdxl;               /* 1 if base == "sdxl", else 0 (derived, not from JSON) */
    char clip2_file[64];           /* CLIP-G .mnn (e.g. clip2.mnn) */
    char tokenizer2_file[64];      /* CLIP-G tokenizer.json (e.g. tokenizer_2.json) */
    int32_t text_embedding_size_2; /* CLIP-G hidden dim, usually 1280 */
    int32_t pooled_embedding_size; /* text_embeds dim fed to UNet add_embedding, usually 1280 */
} MnnSdModelConfig;

/** Load model.json if present; otherwise apply SD1.5 defaults. */
int mnn_sd_load_model_config(const char* model_dir, MnnSdModelConfig* out_config);

#ifdef __cplusplus
}
#endif
