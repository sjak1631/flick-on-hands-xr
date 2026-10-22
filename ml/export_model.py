"""全データで学習したモデルを、端末で読む形式で書き出す。

    ml/.venv/Scripts/python ml/export_model.py --data ml/data/dataset.csv

- app/src/main/assets/stroke_model.txt : モデル（端末の StrokeModel が読む）
- app/src/test/resources/stroke_model_parity.csv : 一部の候補の特徴量と、Python で出した確率（Kotlin の推論と一致するかのテスト用）

形式（1 行 1 項目、空白区切り）:
    features <名前>...       StrokeFeatures.NAMES の名前
    classes X N F
    override <キー>          この距離以上動いたらフリック
    tree <クラス番号> <ノード数>
    split <特徴量番号> <しきい値> <左> <右> <欠損は左か 0/1>   値 <= しきい値なら左
    leaf <値>
クラスごとに木の値を足し、softmax で確率にする。
"""
from __future__ import annotations

import argparse
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

from flickml.data import feature_columns
from flickml.layout import CLASSES
from train import PARAMS, STAGE1

ROOT = Path(__file__).resolve().parent.parent


def flatten(tree: dict) -> list[str]:
    """LightGBM の木（入れ子の dict）を、番号で子を指すノードの並びにする"""
    nodes: list[list] = []

    def visit(n: dict) -> int:
        i = len(nodes)
        nodes.append([])
        if "leaf_value" in n:
            nodes[i] = ["leaf", repr(float(n["leaf_value"]))]
            return i
        if n["decision_type"] != "<=":
            raise ValueError(f"未対応の分岐: {n['decision_type']}")
        left, right = visit(n["left_child"]), visit(n["right_child"])
        nodes[i] = ["split", n["split_feature"], repr(float(n["threshold"])), left, right, int(bool(n["default_left"]))]
        return i

    visit(tree)
    return [" ".join(map(str, n)) for n in nodes]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=ROOT / "ml/data/dataset.csv")
    ap.add_argument("--override", type=float, default=1e9, help="この距離（キー）以上動いたらフリックにする（初期値は使わない）")
    ap.add_argument("--model", type=Path, default=ROOT / "app/src/main/assets/stroke_model.txt")
    ap.add_argument("--parity", type=Path, default=ROOT / "app/src/test/resources/stroke_model_parity.csv")
    args = ap.parse_args()

    data = pd.read_csv(args.data, keep_default_na=False)
    feats = feature_columns(data)
    x = data[feats].replace("", np.nan).astype(float).to_numpy()
    y1 = np.minimum(data.label.map(CLASSES.index).to_numpy(), 2)
    m = lgb.LGBMClassifier(**PARAMS).fit(x, y1)

    dump = m.booster_.dump_model()
    if dump["num_class"] != len(STAGE1):
        raise ValueError("クラス数が合わない")
    lines = [f"features {' '.join(feats)}", f"classes {' '.join(STAGE1)}", f"override {args.override!r}"]
    for t in dump["tree_info"]:
        nodes = flatten(t["tree_structure"])
        lines.append(f"tree {t['tree_index'] % len(STAGE1)} {len(nodes)}")
        lines += nodes
    args.model.parent.mkdir(parents=True, exist_ok=True)
    args.model.write_text("\n".join(lines) + "\n", encoding="utf-8")

    sample = data.sample(min(60, len(data)), random_state=0)
    proba = m.predict_proba(sample[feats].replace("", np.nan).astype(float).to_numpy())
    parity = sample[feats].copy()
    for i, c in enumerate(STAGE1):
        parity[f"p_{c}"] = proba[:, i]
    args.parity.parent.mkdir(parents=True, exist_ok=True)
    parity.to_csv(args.parity, index=False)
    print(f"木 {len(dump['tree_info'])} 本、特徴量 {len(feats)} 個、学習に使った候補 {len(data)} → {args.model}")


if __name__ == "__main__":
    main()
