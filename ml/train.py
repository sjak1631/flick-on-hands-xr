"""打鍵の候補を判定するモデルを学習し、今のルールと比べる。

    ml/.venv/Scripts/python ml/train.py --data ml/data/dataset.csv

判定は 2 段に分ける（データが少ないうちは、方向まで木に覚えさせるとかえって悪くなったため）:
1. モデル（LightGBM）が「打鍵ではない（X）／単押し（N）／フリック（F）」の 3 択を決める
2. フリックの方向は、触れ始めから最も遠くまで動いた向き（ルール）で決める
   --override を指定すると、最も遠くまで動いた距離がそれ以上ならモデルの判定にかかわらずフリックにする。
   1 打鍵ずつのデータだけのときは 1.2 キーが効いたが、単語（続けて打つ）のデータを入れると、単押しのあと次のキーへ
   滑る動きまでフリックにしてしまい悪くなった（単語の打鍵の誤り 13.6% → 18.3%）ので、初期値では使わない

評価はセッション単位で分ける（同じセッションの打鍵は似ているので、打鍵単位で分けると甘くなる）。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import confusion_matrix
from sklearn.model_selection import GroupKFold, KFold

from flickml.data import feature_columns, rule_class
from flickml.layout import CLASSES

STAGE1 = ["X", "N", "F"]
PARAMS = dict(
    objective="multiclass", num_class=len(STAGE1), learning_rate=0.05, n_estimators=300,
    num_leaves=15, min_child_samples=5, subsample=0.8, subsample_freq=1, colsample_bytree=0.8, verbose=-1,
)


def far_direction(data: pd.DataFrame) -> np.ndarray:
    """最も遠くまで動いた向き（上下左右の大きい方）のクラス番号"""
    fx, fy = data.farDx.astype(float).to_numpy(), data.farDy.astype(float).to_numpy()
    c = np.where(np.abs(fx) > np.abs(fy), np.where(fx < 0, "L", "R"), np.where(fy < 0, "U", "D"))
    return np.array([CLASSES.index(v) for v in c])


def combine(stage1: np.ndarray, data: pd.DataFrame, override: float) -> np.ndarray:
    s = np.where(data.farDist.astype(float).to_numpy() >= override, 2, stage1)
    return np.where(s == 0, 0, np.where(s == 1, 1, far_direction(data)))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("ml/data/dataset.csv"))
    ap.add_argument("--override", type=float, default=1e9, help="この距離（キー）以上動いたらフリックにする（初期値は使わない）")
    args = ap.parse_args()

    data = pd.read_csv(args.data, keep_default_na=False)
    feats = feature_columns(data)
    x = data[feats].replace("", np.nan).astype(float).to_numpy()
    y = data.label.map(CLASSES.index).to_numpy()
    y1 = np.minimum(y, 2)  # X, N, フリック
    groups = data.session.to_numpy()

    if len(set(groups)) >= 3:
        splits = list(GroupKFold(n_splits=min(6, len(set(groups)))).split(x, y, groups))
        how = "セッション単位"
    else:
        # 少ないうちは件数が 1〜2 件のクラスもあるので、層化しない K 分割にする
        splits = list(KFold(n_splits=5, shuffle=True, random_state=0).split(x, y))
        how = "打鍵単位（セッションが少ないので参考値）"

    stage1 = np.zeros(len(y), dtype=int)
    for tr, te in splits:
        m = lgb.LGBMClassifier(**PARAMS).fit(x[tr], y1[tr])
        proba = np.zeros((len(te), len(STAGE1)))
        proba[:, m.classes_] = m.predict_proba(x[te])
        stage1[te] = proba.argmax(1)
    pred = combine(stage1, data, args.override)
    rule = rule_class(data).map(CLASSES.index).to_numpy()

    print(f"候補 {len(y)}  セッション {len(set(groups))}  評価: {how}")
    real, tap, flick = y != 0, y == 1, y >= 2
    for name, p in (("今のルール", rule), ("モデル", pred)):
        print(f"  {name:6s} 全体 {(p == y).mean():.1%}  打鍵のみ {(p[real] == y[real]).mean():.1%}"
              f"  単押し {(p[tap] == 1).mean():.1%}  フリック {(p[flick] == y[flick]).mean():.1%}  打鍵ではない {(p[~real] == 0).mean():.1%}")
    # 単語（続けて打つ）のデータだけの成績。実際に使う場面に近いのはこちら
    if "kind" in data.columns and (data.kind == "word").any() and (data.kind != "word").any():
        w = (data.kind == "word").to_numpy()
        stats_path = args.data.with_suffix(".stats.json")
        missed = json.loads(stats_path.read_text(encoding="utf-8"))["missed_strokes"] if stats_path.is_file() else 0
        strokes = int((w & real).sum()) + missed
        for name, p in (("今のルール", rule), ("モデル", pred)):
            # 単語の打鍵に対する誤り：取りこぼし + 打鍵の判定違い + 打鍵ではない候補を打鍵にしたもの
            err = missed + int((p[w] != y[w]).sum())
            print(f"  単語のみ {name:6s} 全体 {(p[w] == y[w]).mean():.1%}  打鍵のみ {(p[w & real] == y[w & real]).mean():.1%}"
                  f"  打鍵の誤り（取りこぼし {missed} 込み） {err / strokes:.1%}")
    for name, p in (("ルール", rule), ("モデル", pred)):
        cm = pd.DataFrame(confusion_matrix(y, p, labels=range(len(CLASSES))), index=[f"正解{c}" for c in CLASSES], columns=CLASSES)
        print(f"\n{name}の混同行列（行: 正解、列: 予測）\n{cm}")

    m = lgb.LGBMClassifier(**PARAMS).fit(x, y1)
    imp = pd.Series(m.booster_.feature_importance("gain"), index=feats).sort_values(ascending=False)
    print("\n効いている特徴量（gain 上位 10）\n" + imp.head(10).round(1).to_string())


if __name__ == "__main__":
    main()
