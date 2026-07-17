#!/usr/bin/env python3
"""
SD1.5 (HuggingFace) → nezumi-ai / xororz-sd-mnn 互換 MNN 変換スクリプト（修正版）

現行版で "動かなかった" 主な原因は以下の 5 点。全部この版で潰してある:
  1. UNet の timestep は **int32** で渡す必要がある (ランタイム側 host<int>() で書き込み)
  2. token_emb.bin は **FP16 (uint16)** で保存する (ランタイム標準・vocab 自動判定)
  3. CLIP は **clip_skip=2 相当** (penultimate 層) を焼き込む
  4. CLIP は **batch=1, [1,77,768]** でエクスポート (ランタイムは 2 回逐次実行)
  5. 出力ファイル名を model.json 既定 (clip_v2 / unet_asym_block32 / vae_decoder_fp16) に合わせる

さらにモデルサイズを削るための追加スイッチも用意した:
  --unet-bits 4|8         UNet の重み量子化 bit 幅 (既定 8)
  --unet-block 32|64|128  block-wise 量子化 (既定 32、精度が最も安定)
  --vae-fp16              VAE decoder を fp16 で保存 (既定 True)
  --token-emb-fp16        token_emb.bin を fp16 で保存 (既定 True, サイズ半減)

使い方例:
  python convert_sd15_to_mnn.py \
      --model runwayml/stable-diffusion-v1-5 \
      --out ./out/CuteYukiMix \
      --size 512 \
      --unet-bits 4 --unet-block 32
"""
import argparse, json, os, shutil, subprocess, sys, gc
from pathlib import Path
import numpy as np
import torch
import torch.nn as nn


def log(msg): print(f"[convert] {msg}", flush=True)
def run(cmd): log("$ " + " ".join(cmd)); subprocess.check_call(cmd)


# --------------------------------------------------------------------------- #
# ONNX -> MNN 変換ヘルパ
# --------------------------------------------------------------------------- #
def which_mnnconvert():
    return [sys.executable, "-m", "MNN.tools.mnnconvert"]


def onnx_to_mnn(onnx_path, mnn_path, *, fp16=False, quant_bits=0,
                quant_block=0, asymmetric=True):
    """
    quant_bits:  0=無効, 4 or 8
    quant_block: 0=無効, 32/64/128  (block-wise の粒度)
    """
    conv = which_mnnconvert()
    args = [*conv, "-f", "ONNX",
            "--modelFile", str(onnx_path),
            "--MNNModel", str(mnn_path),
            "--bizCode", "mnn-sd15-v1",
            "--optimizeLevel", "2"]
    if fp16:
        args.append("--fp16")
    if quant_bits > 0:
        args += ["--weightQuantBits", str(quant_bits)]
        if asymmetric:
            args.append("--weightQuantAsymmetric")
        if quant_block > 0:
            args += ["--weightQuantBlock", str(quant_block)]
    run(args)


# --------------------------------------------------------------------------- #
# torch SDPA を ONNX 化可能な素朴実装に差し替え
# (torch 2.x の scaled_dot_product_attention は opset 14 では出せないため)
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


# --------------------------------------------------------------------------- #
# ラッパーモデル群
# --------------------------------------------------------------------------- #
class CLIPTextEncoderNoEmbedSkip(nn.Module):
    """
    xororz / nezumi-ai 互換 CLIP:
      入力: input_embedding [1, 77, 768]  (float32, token_emb + pos_emb を CPU で加算済み)
      出力: last_hidden_state [1, 77, 768] (float32)

    ★ clip_skip=2 対応 ★
      SD1.5 系の多くの二次配布モデル (CuteYukiMix 系含む) は penultimate hidden
      state に final_layer_norm を掛けたテンソルを条件として使う。
      transformer 全 12 層のうち最後の 1 層をスキップして 11 層目の出力を final_layer_norm
      に流し込む形で焼き込む。
    """
    def __init__(self, hf_text_encoder, clip_skip: int = 2):
        super().__init__()
        # encoder 内の layers を必要な深さだけコピー
        full_encoder = hf_text_encoder.text_model.encoder
        keep_layers = len(full_encoder.layers) - (clip_skip - 1)
        # clip_skip=1 で全層 / =2 で最後の 1 層を落とす / =3 で 2 層落とす
        self.layers = nn.ModuleList(list(full_encoder.layers)[:keep_layers])
        self.final_layer_norm = hf_text_encoder.text_model.final_layer_norm

        mask = torch.full((77, 77), float("-inf"))
        self.register_buffer(
            "causal_mask",
            torch.triu(mask, diagonal=1)[None, None, :, :],
            persistent=False,
        )

    def forward(self, input_embedding):
        # input_embedding: [B, 77, D]
        hidden = input_embedding
        causal = self.causal_mask.expand(input_embedding.shape[0], 1, 77, 77)
        for layer in self.layers:
            # CLIPEncoderLayer.forward(hidden_states, attention_mask, causal_attention_mask, ...)
            out = layer(hidden, None, causal, False)
            hidden = out[0]
        return self.final_layer_norm(hidden)


class UNetWrapper(nn.Module):
    """
    ランタイムは timestep を int32 で書き込むため、
    ★ ここは必ず torch.int64/int32 で受ける ★
    (diffusers UNet2DConditionModel.forward の timestep は int でも long でもOK)
    """
    def __init__(self, unet):
        super().__init__()
        self.unet = unet

    def forward(self, sample, timestep, encoder_hidden_states):
        # timestep: [1] int32/int64
        t = timestep.reshape(-1).to(torch.long).expand(sample.shape[0])
        return self.unet(sample=sample, timestep=t,
                         encoder_hidden_states=encoder_hidden_states,
                         return_dict=False)[0]


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
                    help="HF model ID or local path (e.g. runwayml/stable-diffusion-v1-5)")
    ap.add_argument("--out", required=True, help="出力ディレクトリ")
    ap.add_argument("--size", type=int, default=512, help="latent 解像度の元になる画像サイズ")
    ap.add_argument("--clip-skip", type=int, default=2,
                    help="CLIP の layers スキップ数 (SD1.5 の多くは 2)")
    ap.add_argument("--unet-bits", type=int, default=8, choices=[0, 4, 8],
                    help="UNet の重み量子化 bit 幅。0=無効, 4=小さい/速い, 8=標準")
    ap.add_argument("--unet-block", type=int, default=32,
                    help="UNet の block-wise 量子化サイズ (32/64/128; 32 が最安定)")
    ap.add_argument("--clip-bits", type=int, default=8, choices=[0, 4, 8],
                    help="CLIP の重み量子化 bit 幅")
    ap.add_argument("--vae-bits", type=int, default=8, choices=[0, 4, 8],
                    help="VAE の重み量子化 bit 幅 (VAE は 4bit で崩れやすいので 8 推奨)")
    ap.add_argument("--no-vae-fp16", action="store_true",
                    help="指定すると VAE を fp16 化せず fp32 のまま (サイズ 2 倍)")
    ap.add_argument("--no-token-emb-fp16", action="store_true",
                    help="指定すると token_emb.bin を fp32 で保存する (サイズ 2 倍)")
    ap.add_argument("--filenames", choices=["default", "nezumi"], default="nezumi",
                    help="出力ファイル名。nezumi=CuteYukiMix の model.json と同じ名前")
    args = ap.parse_args()

    out_dir = Path(args.out).resolve()
    work_dir = out_dir / "_onnx"
    work_dir.mkdir(parents=True, exist_ok=True)

    from diffusers import UNet2DConditionModel, AutoencoderKL
    from transformers import CLIPTextModel, CLIPTokenizer

    # ------------------------------------------------------------------ #
    # ★ ONNX エクスポートは基本 CPU / fp32 で行う ★
    #   GPU 経由の fp16 エクスポートは onnx opset 14 の Op でシンボリック関数が
    #   欠けて "TracerWarning: torch.tensor..." が出たり、MNN 側で解釈違いに
    #   なることがある。エクスポートは fp32、MNN 側で --fp16 する方が安全。
    # ------------------------------------------------------------------ #
    device = "cpu"
    dtype = torch.float32
    log(f"Using device: {device.upper()} ({dtype})")

    # ファイル名の対応
    if args.filenames == "nezumi":
        f_clip = "clip_v2.mnn"
        f_unet = f"unet_asym_block{args.unet_block}.mnn"
        f_vae = "vae_decoder_fp16.mnn" if not args.no_vae_fp16 else "vae_decoder.mnn"
    else:
        f_clip = "clip.mnn"
        f_unet = "unet.mnn"
        f_vae = "vae_decoder.mnn"

    # =================================================================== #
    # 1. CLIP TEXT ENCODER
    # =================================================================== #
    if not (out_dir / f_clip).exists():
        log("Exporting CLIP text encoder...")
        text_encoder = CLIPTextModel.from_pretrained(
            args.model, subfolder="text_encoder", low_cpu_mem_usage=True
        ).eval()
        hidden = text_encoder.config.hidden_size
        embeddings = text_encoder.text_model.embeddings

        # ------------------------------------------------------------- #
        # ★ token_emb.bin を書き出す (ランタイムは vocab を自動検出) ★
        #   FP16 版が現行主流。sd1.5 の CLIP は vocab=49408, dim=768
        #   -> FP32=~151MB / FP16=~75MB
        # ------------------------------------------------------------- #
        tok_w = embeddings.token_embedding.weight.detach().cpu().numpy()
        pos_w = embeddings.position_embedding.weight.detach().cpu().numpy()[:77]
        if args.no_token_emb_fp16:
            (out_dir / "token_emb.bin").write_bytes(tok_w.astype(np.float32).tobytes())
            log(f"  token_emb.bin (fp32) vocab={tok_w.shape[0]} dim={tok_w.shape[1]} "
                f"-> {tok_w.nbytes/1024/1024:.1f} MB")
        else:
            (out_dir / "token_emb.bin").write_bytes(tok_w.astype(np.float16).tobytes())
            log(f"  token_emb.bin (fp16) vocab={tok_w.shape[0]} dim={tok_w.shape[1]} "
                f"-> {tok_w.astype(np.float16).nbytes/1024/1024:.1f} MB")
        # pos_emb.bin は歴代 fp32 (77 * 768 = 231KB なので削る意味なし)
        (out_dir / "pos_emb.bin").write_bytes(pos_w.astype(np.float32).tobytes())

        # ------------------------------------------------------------- #
        # ★ ONNX 化: batch=1 固定・clip_skip 焼き込み ★
        # ------------------------------------------------------------- #
        onnx_r = work_dir / "clip.raw.onnx"
        model_to_run = CLIPTextEncoderNoEmbedSkip(text_encoder, clip_skip=args.clip_skip).eval()
        dummy = torch.randn(1, 77, hidden)  # batch=1 固定！
        torch.onnx.export(
            model_to_run, (dummy,), onnx_r.as_posix(),
            input_names=["input_embedding"],
            output_names=["last_hidden_state"],
            opset_version=14,
        )
        # CLIP は fp16 + int8 で通常 150MB -> ~40MB
        onnx_to_mnn(onnx_r, out_dir / f_clip,
                    fp16=True,
                    quant_bits=args.clip_bits, quant_block=0, asymmetric=True)
        del text_encoder, model_to_run
        gc.collect()

    # =================================================================== #
    # 2. UNet
    # =================================================================== #
    if not (out_dir / f_unet).exists():
        log("Exporting UNet (this can take a lot of RAM)...")
        unet = UNet2DConditionModel.from_pretrained(
            args.model, subfolder="unet", low_cpu_mem_usage=True, torch_dtype=torch.float32
        ).eval()
        hidden = unet.config.cross_attention_dim
        onnx_r = work_dir / "unet.raw.onnx"

        model_to_run = UNetWrapper(unet).eval()
        # ★ ランタイムのアップロード形状に完全に合わせる ★
        #   sample: [1, 4, H/8, W/8]   (batch=1, ランタイムが 2 回逐次実行)
        #   timestep: [1] int32
        #   encoder_hidden_states: [1, 77, 768]
        dummy_sample = torch.randn(1, 4, args.size // 8, args.size // 8)
        dummy_timestep = torch.tensor([999], dtype=torch.int32)   # ← ★ int32 ★
        dummy_ehs = torch.randn(1, 77, hidden)

        torch.onnx.export(
            model_to_run,
            (dummy_sample, dummy_timestep, dummy_ehs),
            onnx_r.as_posix(),
            input_names=["sample", "timestep", "encoder_hidden_states"],
            output_names=["out_sample"],
            opset_version=14,
        )

        # UNet 最大の節約ポイント
        #  - fp16 で -50%
        #  - weightQuantBits=4 でさらに -50% (合計 -75%)
        #  - block=32 で品質を維持
        onnx_to_mnn(onnx_r, out_dir / f_unet,
                    fp16=True,
                    quant_bits=args.unet_bits,
                    quant_block=args.unet_block,
                    asymmetric=True)
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
        onnx_r = work_dir / "vae.raw.onnx"

        model_to_run = VAEDecoderWrapper(vae).eval()
        # ランタイムは latent_sample [1, 4, H/8, W/8]
        dummy_latent = torch.randn(1, 4, args.size // 8, args.size // 8)
        torch.onnx.export(
            model_to_run, (dummy_latent,), onnx_r.as_posix(),
            input_names=["latent_sample"],
            output_names=["sample"],
            opset_version=14,
        )
        # VAE は int4 だと簡単に色が抜ける。int8 のままで fp16 の効果だけ乗せる
        onnx_to_mnn(onnx_r, out_dir / f_vae,
                    fp16=not args.no_vae_fp16,
                    quant_bits=args.vae_bits, quant_block=0, asymmetric=True)
        del vae, model_to_run
        gc.collect()

    # =================================================================== #
    # 4. tokenizer.json + model.json
    # =================================================================== #
    tokenizer = CLIPTokenizer.from_pretrained(args.model, subfolder="tokenizer")
    tokenizer.save_pretrained(work_dir)
    src_json = work_dir / "tokenizer.json"
    if src_json.exists():
        shutil.copyfile(src_json, out_dir / "tokenizer.json")
    else:
        # fast tokenizer が無いと tokenizer.json を吐かないので transformers の
        # PreTrainedTokenizerFast 経由で作る
        try:
            from transformers import CLIPTokenizerFast
            fast = CLIPTokenizerFast.from_pretrained(args.model, subfolder="tokenizer")
            fast.save_pretrained(work_dir)
            src_json = work_dir / "tokenizer.json"
            if src_json.exists():
                shutil.copyfile(src_json, out_dir / "tokenizer.json")
            else:
                log("WARNING: tokenizer.json could not be generated")
        except Exception as e:
            log(f"WARNING: failed to save tokenizer.json: {e}")

    model_json = {
        "format": "mnn-sd15-v1",
        "base": "sd1.5",
        "clip": f_clip,
        "unet": f_unet,
        "vae_decoder": f_vae,
        "tokenizer": "tokenizer.json",
        "token_embedding": "token_emb.bin",
        "position_embedding": "pos_emb.bin",
        "clip_skip": args.clip_skip,
        "text_embedding_size": 768,
        "default_size": args.size,
    }
    with open(out_dir / "model.json", "w", encoding="utf-8") as f:
        json.dump(model_json, f, ensure_ascii=False, indent=2)

    log("=" * 60)
    log("Done. Files:")
    total = 0
    for p in sorted(out_dir.iterdir()):
        if p.is_file():
            sz = p.stat().st_size
            total += sz
            log(f"  {p.name:35s}  {sz/1024/1024:8.2f} MB")
    log(f"  {'TOTAL':35s}  {total/1024/1024:8.2f} MB")


if __name__ == "__main__":
    main()
