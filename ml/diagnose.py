"""フレーズ入力（練習）の記録で、実機の判定がどこで誤ったかを種類別に数える。

先に候補と目標の打鍵を書き出しておく（リポジトリのルートで）:
    ./gradlew testDebugUnitTest --tests '*ExportCandidatesTest*' -PreplayDir=logs
    ml/.venv/Scripts/python ml/diagnose.py logs/<session> ...

各試行の候補の並びと目標の打鍵の並びを対応づけ（単語のデータの正解付けと同じ方法）、目標の打鍵ごとに
- 取りこぼし（候補がない／候補はあるが実機が打鍵にしなかった）
- キー違い（実機が出した文字のキーが目標と違う）
- 種類違い（キーは合っているが、単押し・フリックの方向が違う）
に分ける。対応しなかった候補で実機が文字を出したものは「余計な入力」。⌫ は訂正なので数えない。
"""
from __future__ import annotations

import argparse
import collections
from pathlib import Path

import pandas as pd

from flickml.data import align, read_candidates
from flickml.layout import ACTION_TO_STROKE, DIR_TO_CLASS


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("sessions", type=Path, nargs="+")
    args = ap.parse_args()

    counts: collections.Counter[str] = collections.Counter()
    examples: dict[str, list[str]] = collections.defaultdict(list)
    for s in args.sessions:
        if not (s / "targets.csv").is_file():
            print(f"{s.name}: targets.csv がない（フレーズ入力の記録ではないか、候補を書き出していない）")
            continue
        cands = read_candidates(s)
        targets = pd.read_csv(s / "targets.csv", keep_default_na=False)
        trials = pd.read_csv(s / "trials.csv", keep_default_na=False)
        for _, trial in trials.iterrows():
            inside = cands[cands.attempt == trial.attempt].sort_values("tStart")
            strokes = targets[targets.attempt == trial.attempt].sort_values("pos").reset_index(drop=True)
            if strokes.empty:
                continue
            for c, j in align(inside, strokes):
                live = inside.loc[c, "liveActions"].split() if c is not None else []
                live = [a for a in live if a != "<BS>"]
                if j is None:
                    if live:
                        counts["余計な入力"] += 1
                        examples["余計な入力"].append(f"{trial.target}: {' '.join(live)}")
                    continue
                st = strokes.iloc[j]
                counts["目標の打鍵"] += 1
                if c is None:
                    kind = "取りこぼし（候補なし）"
                elif not live:
                    kind = "取りこぼし（打鍵にしなかった）"
                else:
                    got = ACTION_TO_STROKE.get(live[0])
                    if got is None or (got[0], got[1]) != (st.row, st.col):
                        kind = "キー違い"
                    elif got[2] != DIR_TO_CLASS[st.dir]:
                        kind = "種類違い"
                    else:
                        kind = "正しい"
                counts[kind] += 1
                if kind != "正しい":
                    examples[kind].append(f"{trial.target}[{st.pos}]={st.label} → {' '.join(live) or '-'}")

    total = counts.pop("目標の打鍵", 0)
    print(f"目標の打鍵 {total}")
    for k in ["正しい", "キー違い", "種類違い", "取りこぼし（候補なし）", "取りこぼし（打鍵にしなかった）", "余計な入力"]:
        print(f"  {k:16s} {counts[k]:4d}  ({counts[k] / max(total, 1):.1%})")
        for e in examples[k][:6]:
            print(f"      {e}")


if __name__ == "__main__":
    main()
