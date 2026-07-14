#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MnnSdModelConfig {
    char format[32];
    char base[16];
    char clip_file[64];
    char unet_file[64];
    char vae_decoder_file[64];
    char tokenizer_file[64];
    int32_t clip_skip;
    int32_t text_embedding_size;
    int32_t default_size;
} MnnSdModelConfig;

/** Load model.json if present; otherwise apply SD1.5 defaults. */
int mnn_sd_load_model_config(const char* model_dir, MnnSdModelConfig* out_config);

#ifdef __cplusplus
}
#endif
