#!/usr/bin/env python3
"""
Mirror Android ScreenshotProcessor overlapping-strip + letterbox preprocess.

Strip geometry matches ScreenshotProcessor.kt (3 strips, last pinned to far edge,
black letterbox, bilinear). Defaults target SigLIP2-x256:

  MODEL_SIZE = 256, STRIP_OVERLAP = 0.30, output → bad_out_siglip/

Existing NudeNet 320 / 15% work stays in bad_out/ — do not overwrite it:

  python tools/nudenet_strip_preprocess.py --size 320 --overlap 0.15 -o bad_out

Typical SigLIP run (JPGs in ./bad):
  python tools/nudenet_strip_preprocess.py
  # → bad_out_siglip/strips (3× 256) + bad_out_siglip/full (1× letterboxed fullscreen)
  # Sort keepers into cal_siglip/ for INT8 PTQ.
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

from PIL import Image

STRIP_COUNT = 3
# SigLIP2-x256 defaults (override via CLI for NudeNet 320 / 0.15)
STRIP_OVERLAP = 0.30
MODEL_SIZE = 256
LETTERBOX_PAD = (0, 0, 0)  # Android Color.BLACK
DEFAULT_INPUT = Path("bad")
DEFAULT_OUTPUT = Path("bad_out_siglip")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def strip_geometry(
    width: int,
    height: int,
    overlap: float = STRIP_OVERLAP,
) -> tuple[bool, int, int, list[int]]:
    """Return (landscape, strip_len, step, starts) using Kotlin float→int truncation."""
    landscape = width > height
    dim = width if landscape else height
    # Kotlin: (dim / (STRIP_COUNT - (STRIP_COUNT - 1) * STRIP_OVERLAP)).toInt()
    denom = STRIP_COUNT - (STRIP_COUNT - 1) * overlap
    strip_len = int(dim / denom)
    strip_len = max(1, min(strip_len, dim))
    # Kotlin: ((1f - STRIP_OVERLAP) * stripLen).toInt().coerceAtLeast(1)
    step = max(1, int((1.0 - overlap) * strip_len))
    # Kotlin: listOf(0, step, (dim - stripLen).coerceAtLeast(step))
    starts = [0, step, max(dim - strip_len, step)]
    return landscape, strip_len, step, starts


def crop_strips(
    img: Image.Image,
    overlap: float = STRIP_OVERLAP,
) -> list[tuple[Image.Image, tuple[int, int, int, int]]]:
    """Crop 3 overlapping long-axis strips. Returns (patch, (x, y, w, h))."""
    w, h = img.size
    landscape, strip_len, _step, starts = strip_geometry(w, h, overlap)
    dim = w if landscape else h
    out: list[tuple[Image.Image, tuple[int, int, int, int]]] = []
    for start in starts:
        length = min(strip_len, dim - start)
        if landscape:
            box = (start, 0, start + length, h)
            xywh = (start, 0, length, h)
        else:
            box = (0, start, w, start + length)
            xywh = (0, start, w, length)
        out.append((img.crop(box), xywh))
    return out


def letterbox_to_square(img: Image.Image, size: int = MODEL_SIZE) -> Image.Image:
    """
    Fit inside size×size with black bars; never crop.

    Matches Kotlin letterboxToSquare:
      scale = size / max(w, h)
      tw/th = (dim * scale).toInt().coerceAtLeast(1)
      createScaledBitmap(..., filter=true) → bilinear
      canvas.drawColor(Color.BLACK)
      drawBitmap at ((size - tw) / 2f, (size - th) / 2f)
    """
    w, h = img.size
    if w == size and h == size:
        return img.convert("RGB") if img.mode != "RGB" else img.copy()
    scale = float(size) / float(max(w, h))
    tw = max(1, int(w * scale))
    th = max(1, int(h * scale))
    resized = img.convert("RGB").resize((tw, th), Image.Resampling.BILINEAR)
    square = Image.new("RGB", (size, size), LETTERBOX_PAD)
    ox = int((size - tw) / 2.0)
    oy = int((size - th) / 2.0)
    square.paste(resized, (ox, oy))
    if square.size != (size, size):
        raise RuntimeError(f"letterbox produced {square.size}, expected ({size}, {size})")
    return square


def iter_images(input_dir: Path) -> list[Path]:
    """Collect images; prefer flat folder (e.g. bad/*.jpg), else recurse."""
    direct = [
        p
        for p in sorted(input_dir.iterdir())
        if p.is_file() and p.suffix.lower() in IMAGE_EXTS
    ]
    if direct:
        return direct
    return [
        p
        for p in sorted(input_dir.rglob("*"))
        if p.is_file() and p.suffix.lower() in IMAGE_EXTS
    ]


def process_image(
    path: Path,
    strips_dir: Path,
    full_dir: Path,
    raw_dir: Path | None,
    size: int,
    overlap: float,
    jpeg_quality: int,
    writer: csv.DictWriter,
) -> int:
    with Image.open(path) as im:
        img = im.convert("RGB")
        w, h = img.size
        orientation = "landscape" if w > h else "portrait"
        stem = path.stem

        # Full-frame letterbox (geo-accurate aspect, black bars) for A/B vs strips
        full_sq = letterbox_to_square(img, size)
        assert full_sq.size == (size, size)
        full_path = full_dir / f"{stem}_full.jpg"
        full_sq.save(full_path, format="JPEG", quality=jpeg_quality)
        writer.writerow(
            {
                "source": str(path),
                "parent_stem": stem,
                "orientation": orientation,
                "source_w": w,
                "source_h": h,
                "view": "full",
                "strip_index": "",
                "crop_x": 0,
                "crop_y": 0,
                "crop_w": w,
                "crop_h": h,
                "output": str(full_path),
                "model_size": size,
                "overlap": overlap,
            }
        )

        patches = crop_strips(img, overlap)
        for i, (patch, (x, y, cw, ch)) in enumerate(patches):
            if raw_dir is not None:
                patch.save(
                    raw_dir / f"{stem}_s{i}_raw.jpg",
                    format="JPEG",
                    quality=jpeg_quality,
                )
            squared = letterbox_to_square(patch, size)
            assert squared.size == (size, size)
            out_path = strips_dir / f"{stem}_s{i}.jpg"
            squared.save(out_path, format="JPEG", quality=jpeg_quality)
            writer.writerow(
                {
                    "source": str(path),
                    "parent_stem": stem,
                    "orientation": orientation,
                    "source_w": w,
                    "source_h": h,
                    "view": "strip",
                    "strip_index": i,
                    "crop_x": x,
                    "crop_y": y,
                    "crop_w": cw,
                    "crop_h": ch,
                    "output": str(out_path),
                    "model_size": size,
                    "overlap": overlap,
                }
            )
        return len(patches)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Strip crop + black letterbox for on-device NSFW. "
            f"Defaults: {MODEL_SIZE}×{MODEL_SIZE}, overlap={STRIP_OVERLAP} (SigLIP2) → "
            f"{DEFAULT_OUTPUT}/. Use --size 320 --overlap 0.15 -o bad_out for NudeNet."
        )
    )
    parser.add_argument(
        "--input",
        "-i",
        type=Path,
        default=DEFAULT_INPUT,
        help="Folder of screenshots / JPGs (default: bad)",
    )
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Output root (default: {DEFAULT_OUTPUT}; leaves bad_out/ untouched)",
    )
    parser.add_argument(
        "--size",
        type=int,
        default=MODEL_SIZE,
        help=f"Letterbox size (default {MODEL_SIZE} for SigLIP2)",
    )
    parser.add_argument(
        "--overlap",
        type=float,
        default=STRIP_OVERLAP,
        help=f"Strip overlap fraction (default {STRIP_OVERLAP})",
    )
    parser.add_argument("--jpeg-quality", type=int, default=95)
    parser.add_argument(
        "--save-raw",
        action="store_true",
        help="Also write pre-letterbox strip crops under out/raw_strips/",
    )
    args = parser.parse_args(argv)

    if not (0.0 <= args.overlap < 1.0):
        print(f"error: --overlap must be in [0, 1), got {args.overlap}", file=sys.stderr)
        return 1

    if args.output.resolve() == Path("bad_out").resolve() and (
        args.size != 320 or abs(args.overlap - 0.15) > 1e-6
    ):
        print(
            "warning: writing non-NudeNet settings into bad_out/ "
            "(expected --size 320 --overlap 0.15). Prefer bad_out_siglip/ for SigLIP.",
            file=sys.stderr,
        )

    input_dir: Path = args.input
    output_dir: Path = args.output
    if not input_dir.is_dir():
        print(
            f"error: input is not a directory: {input_dir}\n"
            f"Put JPGs in ./{DEFAULT_INPUT}/ or pass --input <folder>",
            file=sys.stderr,
        )
        return 1

    strips_dir = output_dir / "strips"
    strips_dir.mkdir(parents=True, exist_ok=True)
    full_dir = output_dir / "full"
    full_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = None
    if args.save_raw:
        raw_dir = output_dir / "raw_strips"
        raw_dir.mkdir(parents=True, exist_ok=True)

    images = iter_images(input_dir)
    if not images:
        print(f"error: no images found under {input_dir}", file=sys.stderr)
        return 1

    manifest_path = output_dir / "manifest.csv"
    fieldnames = [
        "source",
        "parent_stem",
        "orientation",
        "source_w",
        "source_h",
        "view",
        "strip_index",
        "crop_x",
        "crop_y",
        "crop_w",
        "crop_h",
        "output",
        "model_size",
        "overlap",
    ]
    n_strips = 0
    n_full = 0
    with manifest_path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for path in images:
            n_strips += process_image(
                path,
                strips_dir,
                full_dir,
                raw_dir,
                args.size,
                args.overlap,
                args.jpeg_quality,
                writer,
            )
            n_full += 1

    print(f"Processed {len(images)} images from {input_dir}")
    print(f"  strips: {n_strips}  |  full letterbox: {n_full}")
    print(f"Letterbox: {args.size}×{args.size} black pad | overlap={args.overlap}")
    print(f"Strips:    {strips_dir}")
    print(f"Full:      {full_dir}")
    print(f"Manifest:  {manifest_path}")
    if raw_dir:
        print(f"Raw:       {raw_dir}")
    print(
        "Sort keepers into cal_siglip/ (strips and/or full) for static INT8; "
        "do not overwrite bad_out/ NudeNet strips."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
