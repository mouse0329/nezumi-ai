#!/usr/bin/env python3
"""
SDXL (HuggingFace) → nezumi-ai / MNN 互換 変換スクリプト

SD1.5 からの主な変更点:
  1. 2 つの Text Encoder (CLIP1: 768dim, CLIP2: 1280dim) を個別に処理・書き出し
  2. UNet への追加入力 (text_embeds: 1280dim, time_ids: 6dim) のラッパー対応
  3. デフォルト画像サイズを 1024x1024 (Latent 128x128) に変更
  4. encoder_hidden_states の結合次元 2048 (768 + 1280) に対応

使い方例:
  python convert_sdxl_to_mnn.py \
      --model stabilityai/stable-diffusion-xl-base-1.0 \
      --out ./out/SDXL_Base \
      --size 1024 \
      --unet-bits 4 --unet-block 32
"""

import argparse, json, os, shutil, subprocess, sys, gc
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn


def log(msg): print(f"[convert-sdxl] {msg}", flush=True)
def run(cmd): log("$ " + " ".join(cmd)); subprocess.check_call(cmd)


# --------------------------------------------------------------------------- #
# ONNX -> MNN 変換ヘルパ
# --------------------------------------------------------------------------- #
def which_mnnconvert():
    return [sys.executable, "-m", "MNN.tools.mnnconvert"]


def onnx_to_mnn(onnx_path, mnn_path, *, fp16=False, quant_bits=0,
                quant_block=0, asymmetric=True):
    conv = which_mnnconvert()
    args = [*conv, "-f", "ONNX",
            "--modelFile", str(onnx_path),
            "--MNNModel", str(mnn_path),
            "--bizCode", "mnn-sdxl-v1",
            "--optimizeLevel", "1"]
    if fp16:
        args.append("--fp16")
    if quant_bits > 0:
        args += ["--weightQuantBits", str(quant_bits)]
        if asymmetric:
            args.append("--weightQuantAsymmetric")
        if quant_block > 0:
            args += ["--weightQuantBlock", str(quant_block)]
    args.append("--saveExternalData")
    run(args)


# --------------------------------------------------------------------------- #
# torch SDPA & GroupNorm パッチ (MNN 変換の互換性維持)
# --------------------------------------------------------------------------- #
def scaled_dot_product_attention_patch(query, key, value, attn_mask=None,
                                       dropout_p=0.0, is_causal=False, scale=None):
    L, S = query.size(-2), key.size(-2)
    scale_factor = 1 / (query.size(-1) ** 0.5) if scale is None else scale
    attn_weight = query @ key.transpose(-2, -1) * scale_factor
    if is_causal:
        mask = torch.ones(L, S, dtype=torch.bool, device=query.device).tril(diagonal=0)
        attn_weight.masked_fill_(mask.logical_not(), float("-inf"))
    if attn_mask is not None:
        if attn_mask.dtype == torch.bool:
            attn_weight.masked_fill_(attn_mask.logical_not(), float("-inf"))
        else:
            attn_weight += attn_mask
    return torch.softmax(attn_weight, dim=-1) @ value


torch.nn.functional.scaled_dot_product_attention = scaled_dot_product_attention_patch


def group_norm_patch(input, num_groups, weight=None, bias=None, eps=1e-5):
    C = int(input.shape[1])
    G = num_groups
    N = input.shape[0]
    x = input.reshape(N, G, C // G, -1)
    dims = tuple(range(2, x.dim()))
    mean = x.mean(dim=dims, keepdim=True)
    var = x.var(dim=dims, unbiased=False, keepdim=True)
    x = (x - mean) / torch.sqrt(var + eps)
    x = x.reshape_as(input)
    if weight is not None:
        x = x * weight.view(1, C, *([1] * (input.dim() - 2)))
    if bias is not None:
        x = x + bias.view(1, C, *([1] * (input.dim() - 2)))
    return x


torch.nn.functional.group_norm = group_norm_patch


def _patch_vae_attn_processor_dynamic():
    import torch
    import torch.nn.functional as F
    from diffusers.models.attention_processor import AttnProcessor2_0, AttnProcessor

    def _dynamic_call(self, attn, hidden_states, encoder_hidden_states=None,
                      attention_mask=None, temb=None, *args, **kwargs):
        residual = hidden_states

        if attn.spatial_norm is not None:
            hidden_states = attn.spatial_norm(hidden_states, temb)

        input_ndim = hidden_states.ndim
        if input_ndim == 4:
            b = hidden_states.shape[0]
            c = hidden_states.shape[1]
            hidden_states = hidden_states.reshape(b, c, -1).transpose(1, 2)

        batch_size, sequence_length, _ = (
            hidden_states.shape if encoder_hidden_states is None
            else encoder_hidden_states.shape
        )

        if attention_mask is not None:
            attention_mask = attn.prepare_attention_mask(attention_mask, sequence_length, batch_size)
            attention_mask = attention_mask.view(batch_size, attn.heads, -1, attention_mask.shape[-1])

        if attn.group_norm is not None:
            hidden_states = attn.group_norm(hidden_states.transpose(1, 2)).transpose(1, 2)

        query = attn.to_q(hidden_states)
        if encoder_hidden_states is None:
            encoder_hidden_states = hidden_states
        elif attn.norm_cross:
            encoder_hidden_states = attn.norm_encoder_hidden_states(encoder_hidden_states)

        key = attn.to_k(encoder_hidden_states)
        value = attn.to_v(encoder_hidden_states)

        inner_dim = key.shape[-1]
        head_dim = inner_dim // attn.heads

        query = query.view(batch_size, -1, attn.heads, head_dim).transpose(1, 2)
        key = key.view(batch_size, -1, attn.heads, head_dim).transpose(1, 2)
        value = value.view(batch_size, -1, attn.heads, head_dim).transpose(1, 2)

        hidden_states = F.scaled_dot_product_attention(
            query, key, value, attn_mask=attention_mask, dropout_p=0.0, is_causal=False
        )

        hidden_states = hidden_states.transpose(1, 2).reshape(batch_size, -1, attn.heads * head_dim)
        hidden_states = hidden_states.to(query.dtype)

        hidden_states = attn.to_out[0](hidden_states)
        hidden_states = attn.to_out[1](hidden_states)

        if input_ndim == 4:
            h = residual.shape[2]
            w = residual.shape[3]
            hidden_states = hidden_states.transpose(-1, -2).reshape(
                hidden_states.shape[0], residual.shape[1], h, w
            )

        if attn.residual_connection:
            hidden_states = hidden_states + residual

        hidden_states = hidden_states / attn.rescale_output_factor
        return hidden_states

    AttnProcessor2_0.__call__ = _dynamic_call
    AttnProcessor.__call__ = _dynamic_call


# --------------------------------------------------------------------------- #
# SDXL 用ラッパーモデル群
# --------------------------------------------------------------------------- #
class CLIP1TextEncoderNoEmbedSkip(nn.Module):
    """ CLIP 1 (CLIP ViT-L/14) ラッパー """
    def __init__(self, hf_text_encoder, clip_skip: int = 2):
        super().__init__()
        full_encoder = hf_text_encoder.text_model.encoder
        keep_layers = len(full_encoder.layers) - (clip_skip - 1)
        self.layers = nn.ModuleList(list(full_encoder.layers)[:keep_layers])
        self.final_layer_norm = hf_text_encoder.text_model.final_layer_norm

        mask = torch.full((77, 77), float("-inf"))
        self.register_buffer(
            "causal_mask",
            torch.triu(mask, diagonal=1)[None, None, :, :],
            persistent=False,
        )

    def forward(self, input_embedding):
        hidden = input_embedding
        causal = self.causal_mask.expand(input_embedding.shape[0], 1, 77, 77)
        for layer in self.layers:
            out = layer(hidden, None, causal, False)
            hidden = out[0]
        # NOTE: SDXL 本家 (diffusers) の encode_prompt は
        #   prompt_embeds.hidden_states[-2] (clip_skip=None のデフォルト時) を
        #   そのまま UNet の encoder_hidden_states として使い、
        #   final_layer_norm は一切かけない。
        #   (clip_skip 指定時も hidden_states[-(clip_skip+2)] で同様に
        #    LayerNorm 前の中間層出力を使う。SD1.5 の一部コミュニティ実装
        #    (lpw_stable_diffusion 等) は LayerNorm をかける流儀もあるが、
        #    SDXL ではこれを行うとUNetが学習時に見た分布と一致せず、
        #    条件付けが機能しなくなる。)
        # よってここでは final_layer_norm を意図的にかけない。
        return hidden


class CLIP2TextEncoderNoEmbedSkip(nn.Module):
    """ CLIP 2 (OpenCLIP ViT-bigG/14) ラッパー: last_hidden_state と pooled_embeds を出力 """
    def __init__(self, hf_text_encoder_2, clip_skip: int = 2):
        super().__init__()
        full_encoder = hf_text_encoder_2.text_model.encoder
        keep_layers = len(full_encoder.layers) - (clip_skip - 1)
        self.layers = nn.ModuleList(list(full_encoder.layers)[:keep_layers])
        self.final_layer_norm = hf_text_encoder_2.text_model.final_layer_norm
        self.text_projection = hf_text_encoder_2.text_projection

    def forward(self, input_embedding, eos_index):
        # input_embedding: [1, 77, 1280]
        # eos_index: [1] long/int32
        hidden = input_embedding
        mask = torch.full((77, 77), float("-inf"), device=input_embedding.device)
        causal = torch.triu(mask, diagonal=1)[None, None, :, :].expand(input_embedding.shape[0], 1, 77, 77)

        for layer in self.layers:
            out = layer(hidden, None, causal, False)
            hidden = out[0]

        # NOTE: SDXL 本家 (diffusers) は UNet の encoder_hidden_states には
        #   hidden_states[-2] (LayerNorm 前) をそのまま使う一方、
        #   text_embeds (pooled) は CLIPTextModelWithProjection の
        #   pooler_output 経由で "LayerNorm 済み" の last_hidden_state から
        #   EOS 位置を取り出して text_projection にかけたものを使う
        #   (transformers の CLIPTextTransformer.forward 参照:
        #    pooled_output は self.final_layer_norm(last_hidden_state) の後に
        #    EOS 位置を gather する)。
        #   そのため、UNet に渡す hidden はLayerNorm 前のまま、
        #   pooled 計算用にだけ別途 final_layer_norm をかけた
        #   hidden_normed を使う必要がある。
        hidden_normed = self.final_layer_norm(hidden)

        # EOS トークン位置から pooled 特徴量を抽出してプロジェクション
        batch_idx = torch.arange(input_embedding.shape[0], device=input_embedding.device)
        pooled = hidden_normed[batch_idx, eos_index]
        text_embeds = self.text_projection(pooled)

        return hidden, text_embeds


class SDXLUNetWrapper(nn.Module):
    """ SDXL UNet ラッパー """
    def __init__(self, unet):
        super().__init__()
        self.unet = unet

    def forward(self, sample, timestep, encoder_hidden_states, text_embeds, time_ids):
        # sample: [1, 4, H/8, W/8]
        # timestep: [1] int32
        # encoder_hidden_states: [1, 77, 2048]
        # text_embeds: [1, 1280]
        # time_ids: [1, 6]
        t = timestep.reshape(-1).to(torch.long).expand(sample.shape[0])
        added_cond_kwargs = {
            "text_embeds": text_embeds,
            "time_ids": time_ids
        }
        return self.unet(
            sample=sample,
            timestep=t,
            encoder_hidden_states=encoder_hidden_states,
            added_cond_kwargs=added_cond_kwargs,
            return_dict=False
        )[0]


class VAEDecoderWrapper(nn.Module):
    def __init__(self, vae):
        super().__init__()
        self.vae = vae

    def forward(self, latent_sample):
        return self.vae.decode(latent_sample, return_dict=False)[0]


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True,
                    help="HF model ID or local path (e.g. stabilityai/stable-diffusion-xl-base-1.0)")
    ap.add_argument("--out", required=True, help="出力ディレクトリ")
    ap.add_argument("--size", type=int, default=1024, help="latent 解像度の元になる画像サイズ (SDXL既定: 1024)")
    ap.add_argument("--clip-skip", type=int, default=2, help="CLIP の layers スキップ数 (既定 2)")
    ap.add_argument("--unet-bits", type=int, default=8, choices=[0, 4, 8], help="UNet 重み量子化 bit 幅")
    ap.add_argument("--unet-block", type=int, default=0, choices=[0, 32, 64, 128], help="UNet block-wise 量子化サイズ")
    ap.add_argument("--clip-bits", type=int, default=8, choices=[0, 4, 8], help="CLIP 重み量子化 bit 幅")
    ap.add_argument("--vae-bits", type=int, default=8, choices=[0, 4, 8], help="VAE 重み量子化 bit 幅")
    ap.add_argument("--no-vae-fp16", action="store_true", help="指定すると VAE を fp16 化せず fp32 で保存")
    ap.add_argument("--no-token-emb-fp16", action="store_true", help="指定すると token_emb を fp32 で保存")
    args = ap.parse_args()

    out_dir = Path(args.out).resolve()
    work_dir = out_dir / "_onnx"
    work_dir.mkdir(parents=True, exist_ok=True)

    from diffusers import UNet2DConditionModel, AutoencoderKL
    from transformers import CLIPTextModel, CLIPTextModelWithProjection, CLIPTokenizer, CLIPTokenizerFast

    _patch_vae_attn_processor_dynamic()

    device = "cpu"
    log(f"Using device: {device.upper()}")

    f_clip1 = "clip1.mnn"
    f_clip2 = "clip2.mnn"
    f_unet = "unet.mnn"
    f_vae = "vae_decoder_fp16.mnn" if not args.no_vae_fp16 else "vae_decoder.mnn"

    # =================================================================== #
    # 1. CLIP 1 & CLIP 2 TEXT ENCODERS
    # =================================================================== #
    if not (out_dir / f_clip1).exists():
        log("Exporting CLIP 1 text encoder...")
        text_encoder_1 = CLIPTextModel.from_pretrained(
            args.model, subfolder="text_encoder", low_cpu_mem_usage=True
        ).eval()
        hidden_dim1 = text_encoder_1.config.hidden_size # 768
        emb1 = text_encoder_1.text_model.embeddings

        tok_w1 = emb1.token_embedding.weight.detach().cpu().numpy()
        pos_w1 = emb1.position_embedding.weight.detach().cpu().numpy()[:77]
        dtype_emb = np.float32 if args.no_token_emb_fp16 else np.float16

        (out_dir / "token_emb1.bin").write_bytes(tok_w1.astype(dtype_emb).tobytes())
        (out_dir / "pos_emb1.bin").write_bytes(pos_w1.astype(np.float32).tobytes())

        onnx_r = work_dir / "clip1.raw.onnx"
        model_to_run = CLIP1TextEncoderNoEmbedSkip(text_encoder_1, clip_skip=args.clip_skip).eval()
        dummy = torch.randn(1, 77, hidden_dim1)
        torch.onnx.export(
            model_to_run, (dummy,), onnx_r.as_posix(),
            input_names=["input_embedding"],
            output_names=["last_hidden_state"],
            opset_version=14,
        )
        onnx_to_mnn(onnx_r, out_dir / f_clip1, fp16=True, quant_bits=args.clip_bits, quant_block=0, asymmetric=True)
        del text_encoder_1, model_to_run
        gc.collect()

    if not (out_dir / f_clip2).exists():
        log("Exporting CLIP 2 text encoder...")
        text_encoder_2 = CLIPTextModelWithProjection.from_pretrained(
            args.model, subfolder="text_encoder_2", low_cpu_mem_usage=True
        ).eval()
        hidden_dim2 = text_encoder_2.config.hidden_size # 1280
        emb2 = text_encoder_2.text_model.embeddings

        tok_w2 = emb2.token_embedding.weight.detach().cpu().numpy()
        pos_w2 = emb2.position_embedding.weight.detach().cpu().numpy()[:77]
        dtype_emb = np.float32 if args.no_token_emb_fp16 else np.float16

        (out_dir / "token_emb2.bin").write_bytes(tok_w2.astype(dtype_emb).tobytes())
        (out_dir / "pos_emb2.bin").write_bytes(pos_w2.astype(np.float32).tobytes())

        onnx_r = work_dir / "clip2.raw.onnx"
        model_to_run = CLIP2TextEncoderNoEmbedSkip(text_encoder_2, clip_skip=args.clip_skip).eval()
        dummy_emb = torch.randn(1, 77, hidden_dim2)
        dummy_eos = torch.tensor([76], dtype=torch.int32)
        torch.onnx.export(
            model_to_run, (dummy_emb, dummy_eos), onnx_r.as_posix(),
            input_names=["input_embedding", "eos_index"],
            output_names=["last_hidden_state", "text_embeds"],
            opset_version=14,
        )
        onnx_to_mnn(onnx_r, out_dir / f_clip2, fp16=True, quant_bits=args.clip_bits, quant_block=0, asymmetric=True)
        del text_encoder_2, model_to_run
        gc.collect()

    # =================================================================== #
    # 2. SDXL UNet
    # =================================================================== #
    if not (out_dir / f_unet).exists():
        log("Exporting SDXL UNet...")
        unet = UNet2DConditionModel.from_pretrained(
            args.model, subfolder="unet", low_cpu_mem_usage=True, torch_dtype=torch.float32
        ).eval()
        onnx_r = work_dir / "unet.raw.onnx"

        model_to_run = SDXLUNetWrapper(unet).eval()
        # SDXL Latent 形状: [1, 4, H/8, W/8] (1024x1024 -> 128x128)
        dummy_sample = torch.randn(1, 4, args.size // 8, args.size // 8)
        dummy_timestep = torch.tensor([999], dtype=torch.int32)
        dummy_ehs = torch.randn(1, 77, 2048) # 768 + 1280
        dummy_text_embeds = torch.randn(1, 1280)
        dummy_time_ids = torch.tensor([[args.size, args.size, 0, 0, args.size, args.size]], dtype=torch.float32)

        torch.onnx.export(
            model_to_run,
            (dummy_sample, dummy_timestep, dummy_ehs, dummy_text_embeds, dummy_time_ids),
            onnx_r.as_posix(),
            input_names=["sample", "timestep", "encoder_hidden_states", "text_embeds", "time_ids"],
            output_names=["out_sample"],
            opset_version=14,
            dynamic_axes={
                "sample": {0: "batch", 2: "height", 3: "width"},
                "out_sample": {0: "batch", 2: "height", 3: "width"},
            },
        )

        onnx_to_mnn(onnx_r, out_dir / f_unet,
                    fp16=False,
                    quant_bits=args.unet_bits,
                    quant_block=args.unet_block,
                    asymmetric=False)
        del unet, model_to_run
        gc.collect()

    # =================================================================== #
    # 3. VAE Decoder
    # =================================================================== #
    if not (out_dir / f_vae).exists():
        log("Exporting VAE decoder...")
        vae = AutoencoderKL.from_pretrained(
            args.model, subfolder="vae", low_cpu_mem_usage=True, torch_dtype=torch.float32
        ).eval()

        from diffusers.models.attention_processor import AttnProcessor
        for name, module in vae.named_modules():
            if module.__class__.__name__ == "Attention":
                module.set_processor(AttnProcessor())

        onnx_r = work_dir / "vae.raw.onnx"
        model_to_run = VAEDecoderWrapper(vae).eval()
        dummy_latent = torch.randn(1, 4, args.size // 8, args.size // 8)

        torch.onnx.export(
            model_to_run, (dummy_latent,), onnx_r.as_posix(),
            input_names=["latent_sample"],
            output_names=["sample"],
            opset_version=14,
            dynamic_axes={
                "latent_sample": {0: "batch", 2: "height", 3: "width"},
                "sample": {0: "batch", 2: "height", 3: "width"},
            },
        )
        onnx_to_mnn(onnx_r, out_dir / f_vae,
                    fp16=not args.no_vae_fp16,
                    quant_bits=args.vae_bits, quant_block=0, asymmetric=True)
        del vae, model_to_run
        gc.collect()

    # =================================================================== #
    # 4. Tokenizers & model.json
    # =================================================================== #
    log("Exporting tokenizers and model.json...")
    try:
        tok1 = CLIPTokenizer.from_pretrained(args.model, subfolder="tokenizer")
        tok1.save_pretrained(work_dir / "tok1")
        shutil.copyfile(work_dir / "tok1" / "tokenizer.json", out_dir / "tokenizer.json")
    except Exception as e:
        log(f"WARNING: tokenizer 1 export failed: {e}")

    try:
        tok2 = CLIPTokenizerFast.from_pretrained(args.model, subfolder="tokenizer_2")
        tok2.save_pretrained(work_dir / "tok2")
        shutil.copyfile(work_dir / "tok2" / "tokenizer.json", out_dir / "tokenizer_2.json")
    except Exception as e:
        log(f"WARNING: tokenizer 2 export failed: {e}")

    model_json = {
        "format": "mnn-sdxl-v1",
        "base": "sdxl",
        "clip1": f_clip1,
        "clip2": f_clip2,
        "unet": f_unet,
        "vae_decoder": f_vae,
        "tokenizer1": "tokenizer.json",
        "tokenizer2": "tokenizer_2.json",
        "token_embedding1": "token_emb1.bin",
        "position_embedding1": "pos_emb1.bin",
        "token_embedding2": "token_emb2.bin",
        "position_embedding2": "pos_emb2.bin",
        "clip_skip": args.clip_skip,
        "text_embedding_size": 2048,
        "default_size": args.size,
    }
    with open(out_dir / "model.json", "w", encoding="utf-8") as f:
        json.dump(model_json, f, ensure_ascii=False, indent=2)

    log("=" * 60)
    log("Done. Output directory contains:")
    total = 0
    for p in sorted(out_dir.iterdir()):
        if p.is_file():
            sz = p.stat().st_size
            total += sz
            log(f"  {p.name:35s}  {sz/1024/1024:8.2f} MB")
    log(f"  {'TOTAL':35s}  {total/1024/1024:8.2f} MB")


if __name__ == "__main__":
    main()