# -*- coding: utf-8 -*-
"""
doc_to_md.py

Chaquopy 経由で Kotlin (NezumiLiteRtTools / ConvertFileToMdToolBridge) から
呼び出される、PDF / Word(.docx) / Excel(.xlsx, .xls) を Markdown に変換する
ブリッジスクリプト。

呼び出し規約:
    Python py = Python.getInstance();
    PyObject module = py.getModule("doc_to_md");
    PyObject result = module.callAttr("convert_file", inputPath, outputPath);
    // result は JSON 文字列 (成功/失敗、markdown文字列、エラーメッセージ等)

外部からは常に convert_file(input_path, output_path) -> str(JSON) の
1関数のみを利用する。JSON でやりとりすることで Kotlin 側の型変換
（PyObject -> Map など）を最小限に抑える。
"""

import json
import os
import traceback


def _error_json(error_code, message):
    return json.dumps({
        "success": False,
        "error": error_code,
        "message": message,
    }, ensure_ascii=False)


def _success_json(markdown_text, output_path, char_count):
    return json.dumps({
        "success": True,
        "outputPath": output_path,
        "charCount": char_count,
        # markdown 本文は Kotlin 側で output_path のファイルからも読めるが、
        # 小さいファイルであれば直接ここに含めて往復のディスクI/Oを減らす。
        "preview": markdown_text[:2000],
    }, ensure_ascii=False)


def convert_file(input_path: str, output_path: str) -> str:
    """
    input_path のドキュメント (PDF/DOCX/XLSX/XLS/PPTX/HTML 等) を
    Markdown に変換し、output_path に UTF-8 で書き出す。

    Returns:
        JSON 文字列。成功時は {"success": true, "outputPath": ..., "charCount": ..., "preview": ...}
        失敗時は {"success": false, "error": "...", "message": "..."}
    """
    try:
        if not input_path or not os.path.exists(input_path):
            return _error_json("file_not_found", f"Input file does not exist: {input_path}")

        if os.path.getsize(input_path) == 0:
            return _error_json("empty_file", f"Input file is empty: {input_path}")

        try:
            from markitdown import MarkItDown
        except ImportError as e:
            return _error_json(
                "markitdown_import_failed",
                f"Failed to import markitdown package: {e}",
            )

        # markitdown のバージョンによっては enable_plugins 引数が廃止されており、
        # 渡すと TypeError になる (0.1.4 で "unexpected keyword argument" になることを
        # 実機ログで確認)。新しめのバージョンではデフォルトでプラグイン無効なので、
        # 引数なしで初期化する。念のため古い版互換でフォールバックも用意する。
        try:
            md = MarkItDown()
        except TypeError:
            md = MarkItDown(enable_plugins=False)

        try:
            result = md.convert(input_path)
        except Exception as e:
            return _error_json(
                "conversion_failed",
                f"MarkItDown failed to convert '{os.path.basename(input_path)}': {e}",
            )

        markdown_text = result.text_content or ""

        if not markdown_text.strip():
            return _error_json(
                "empty_result",
                "Conversion produced no text content. The file may be a scanned "
                "(image-only) document that requires OCR, which is not supported.",
            )

        out_dir = os.path.dirname(output_path)
        if out_dir and not os.path.exists(out_dir):
            os.makedirs(out_dir, exist_ok=True)

        with open(output_path, "w", encoding="utf-8") as f:
            f.write(markdown_text)

        return _success_json(markdown_text, output_path, len(markdown_text))

    except Exception as e:
        # 想定外の例外は握りつぶさずスタックトレースを含めて返す（デバッグ用）
        return _error_json(
            "unexpected_error",
            f"{e}\n{traceback.format_exc()}",
        )


def is_available() -> str:
    """
    markitdown パッケージが import 可能かどうかを事前チェックするための
    軽量エンドポイント。UI 側で「この端末では未対応」を早期に判定するのに使う。
    """
    try:
        import markitdown  # noqa: F401
        return json.dumps({"available": True})
    except Exception as e:
        return json.dumps({"available": False, "reason": str(e)})
