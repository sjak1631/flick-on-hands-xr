"""データ収集（提示）の記録から、正解付きのデータセットを作る。

先に Kotlin で候補を書き出しておく（リポジトリのルートで）:
    ./gradlew testDebugUnitTest --tests '*ExportCandidatesTest*' -PreplayDir=logs
そのあと:
    ml/.venv/Scripts/python ml/build_dataset.py --logs logs --out ml/data/dataset.csv
"""
from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

import pandas as pd

from flickml.data import LabelStats, label_prompted, rule_class, sessions


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--logs", type=Path, default=Path("logs"))
    ap.add_argument("--out", type=Path, default=Path("ml/data/dataset.csv"))
    ap.add_argument("--exclude", nargs="*", default=[], help="使わないセッション（ほとんど打たなかった回など）")
    args = ap.parse_args()

    stats = LabelStats()
    parts = [df for s in sessions(args.logs) if s.name not in args.exclude and (df := label_prompted(s, stats)) is not None]
    if not parts:
        raise SystemExit(f"{args.logs} にデータ収集（提示）の記録がない（prompts.csv と candidates.csv が要る）")
    data = pd.concat(parts, ignore_index=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    data.to_csv(args.out, index=False)
    # 取りこぼした打鍵はデータセットに行がないので、数を横に残す（train.py が単語の打鍵の誤りに含める）
    args.out.with_suffix(".stats.json").write_text(json.dumps(asdict(stats)), encoding="utf-8")

    print(f"セッション {data.session.nunique()}  候補 {len(data)}  提示 {stats.prompts}")
    print(f"  1 打鍵: 候補がなかった提示 {stats.windows_without_candidate}  候補が複数あった提示 {stats.windows_with_extra}")
    print(f"  単語: {stats.words} 語、取りこぼした打鍵 {stats.missed_strokes}")
    print(f"  キーがずれた打鍵 {stats.key_mismatch}")
    print("  種類ごとの候補:", data.kind.value_counts().to_dict())
    print("  正解の内訳:", data.label.value_counts().to_dict())
    print(f"  今のルールの正解率（打鍵の種類）: {(rule_class(data) == data.label).mean():.1%}")
    print(f"→ {args.out}")


if __name__ == "__main__":
    main()
