# MNN モデル I/O マップ（CuteYukiMix / SD1.5）

MNN公式 `stable_diffusion.cpp` の実装から確定。

## clip_v2.mnn (text_encoder)

| 方向 | 名前 | shape | dtype |
|------|------|-------|-------|
| input | `input_ids` | [2, 77] | int32 |
| output | `last_hidden_state` | [2, 77, 768] | float32 |

## unet_asym_block32.mnn

| 方向 | 名前 | shape | dtype |
|------|------|-------|-------|
| input | `sample` | [2, 4, H/8, W/8] | float32 |
| input | `timestep` | [1] | float32 |
| input | `encoder_hidden_states` | [2, 77, 768] | float32 |
| output | `out_sample` | [2, 4, H/8, W/8] | float32 |

## vae_decoder_fp16.mnn

| 方向 | 名前 | shape | dtype |
|------|------|-------|-------|
| input | `latent_sample` | [1, 4, H/8, W/8] | float32 |
| output | `sample` | [1, 3, H, W] | float32 |

## スケジューラ

- PNDM (scaled_linear beta schedule)
- beta_start=0.00085, beta_end=0.012, T=1000
- latent scale factor: 1/0.18215

## トークナイザー

- BPE (tokenizer.json, HuggingFace format)
- BOS=49406, EOS=49407, max_len=77
- encode_pair: [uncond(77), cond(77)] = 154 ids
