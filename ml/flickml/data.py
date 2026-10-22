"""記録（logs/<session>/）の読み込みと、データ収集の記録への正解付け。"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pandas as pd

from .layout import DIR_TO_CLASS

# 提示から打鍵が始まるまでの最短の遅れ。これより前に始まった候補は前の提示のもの
LAG_MS = 200
ID_COLUMNS = ["session", "attempt", "index", "tStart", "tEnd", "row", "col", "ruleDir", "liveActions"]
LABEL_COLUMNS = ["label", "kind", "promptIndex", "promptLabel", "promptRow", "promptCol", "keyMatch"]


def sessions(root: Path) -> list[Path]:
    return sorted(p for p in root.iterdir() if (p / "candidates.csv").is_file())


def read_candidates(session: Path) -> pd.DataFrame:
    df = pd.read_csv(session / "candidates.csv", keep_default_na=False, dtype={"liveActions": str})
    df.insert(0, "session", session.name)
    return df


@dataclass
class LabelStats:
    prompts: int = 0
    windows_without_candidate: int = 0
    windows_with_extra: int = 0
    key_mismatch: int = 0
    words: int = 0
    missed_strokes: int = 0


def label_prompted(session: Path, stats: LabelStats | None = None) -> pd.DataFrame | None:
    """データ収集（提示）の記録の候補に正解を付ける。提示の記録がなければ None。

    1 打鍵ずつの提示：提示 i の時間枠は [t_i + LAG, t_{i+1} + LAG)。枠の中で始まった候補のうち、普通の候補
    （浅い候補でない）で一番深いものを、その提示の打鍵とする。
    単語の提示：[label_words]。
    どちらも、打鍵に対応しなかった候補と、どの枠にも入らない候補は「打鍵ではない（X）」。
    """
    prompts_path = session / "prompts.csv"
    if not prompts_path.is_file():
        return None
    cands = read_candidates(session)
    prompts = pd.read_csv(prompts_path, keep_default_na=False)
    stats = stats if stats is not None else LabelStats()

    cands["label"] = "X"
    cands["kind"] = "single"
    cands["promptIndex"] = -1
    cands["promptLabel"] = ""
    cands["promptRow"] = -1
    cands["promptCol"] = -1
    cands["keyMatch"] = False
    if "word" in prompts.columns and (prompts.word != "").any():
        cands["kind"] = "word"
        label_words(cands, prompts, stats)
        return cands

    t = prompts["t"].tolist()
    step = int(pd.Series(t).diff().median()) if len(t) > 1 else 2000
    ends = t[1:] + [t[-1] + step]
    for i, p in prompts.iterrows():
        lo, hi = t[i] + LAG_MS, ends[i] + LAG_MS
        inside = cands[(cands.tStart >= lo) & (cands.tStart < hi)]
        stats.prompts += 1
        if inside.empty:
            stats.windows_without_candidate += 1
            continue
        if len(inside) > 1:
            stats.windows_with_extra += 1
        normal = inside[inside.isDip == 0]
        main = (normal if not normal.empty else inside).sort_values("hMin").index[0]
        cands.loc[main, "label"] = DIR_TO_CLASS[p["dir"]]
        cands.loc[main, "promptIndex"] = p["index"]
        cands.loc[main, "promptLabel"] = p["label"]
        cands.loc[main, "promptRow"] = p["row"]
        cands.loc[main, "promptCol"] = p["col"]
        match = bool(cands.loc[main, "row"] == p["row"] and cands.loc[main, "col"] == p["col"])
        cands.loc[main, "keyMatch"] = match
        stats.key_mismatch += 0 if match else 1
    return cands


# 単語の対応づけの費用（キー単位の距離と同じ尺度）
SKIP_CANDIDATE = 1.0   # 候補を「打鍵ではない」にする
SKIP_STROKE = 1.2      # 単語の打鍵を取りこぼしにする
TAP_FLICK = 0.7        # 単押しとフリックの取り違え
WRONG_DIR = 0.5        # フリックの方向違い
DIP = 0.3              # 浅い候補を打鍵にする（普通の候補を優先する）


def _rough_type(c: pd.Series) -> str:
    """対応づけに使う大まかな種類。0.7 キー以上動いていればその向きのフリック"""
    if float(c.farDist) < 0.7:
        return "N"
    fx, fy = float(c.farDx), float(c.farDy)
    return ("L" if fx < 0 else "R") if abs(fx) > abs(fy) else ("U" if fy < 0 else "D")


def _match_cost(c: pd.Series, s: pd.Series) -> float:
    d = ((c.col + c.cellX - (s.col + 0.5)) ** 2 + (c.row + c.cellY - (s.row + 0.5)) ** 2) ** 0.5
    want, got = DIR_TO_CLASS[s.dir], _rough_type(c)
    if want != got:
        d += TAP_FLICK if "N" in (want, got) else WRONG_DIR
    return d + (DIP if c.isDip == 1 else 0.0)


def label_words(cands: pd.DataFrame, prompts: pd.DataFrame, stats: LabelStats) -> None:
    """単語の提示：単語の時間枠 [t_w + LAG, t_{w+1} + LAG) の中の候補の並びと、単語の打鍵の並びを順番どおりに対応づける。

    費用は、打ち始めの位置と目標のキーの中心の距離（キー）に、種類の違い（単押しとフリック、方向）の罰則を足したもの。
    候補を飛ばす（打鍵ではない）・打鍵を飛ばす（取りこぼし）にも費用を付け、合計が最小になる対応を動的計画法で選ぶ。
    """
    words = prompts.groupby("index", sort=True)
    starts = [g.t.iloc[0] for _, g in words]
    step = int(pd.Series(starts).diff().median()) if len(starts) > 1 else 5000
    for k, (_, g) in enumerate(words):
        strokes = g.sort_values("pos").reset_index(drop=True)
        lo = starts[k] + LAG_MS
        hi = (starts[k + 1] if k + 1 < len(starts) else starts[k] + step) + LAG_MS
        inside = cands[(cands.tStart >= lo) & (cands.tStart < hi)].sort_values("tStart")
        stats.words += 1
        stats.prompts += len(strokes)
        for c, j in align(inside, strokes):
            if c is None:
                stats.missed_strokes += 1
            elif j is not None:
                s = strokes.iloc[j]
                cands.loc[c, "label"] = DIR_TO_CLASS[s.dir]
                cands.loc[c, "promptIndex"] = s["index"]
                cands.loc[c, "promptLabel"] = s.label
                cands.loc[c, "promptRow"] = s.row
                cands.loc[c, "promptCol"] = s.col
                match = bool(cands.loc[c, "row"] == s.row and cands.loc[c, "col"] == s.col)
                cands.loc[c, "keyMatch"] = match
                stats.key_mismatch += 0 if match else 1


def align(inside: pd.DataFrame, strokes: pd.DataFrame) -> list[tuple[object | None, int | None]]:
    """候補の並び（時刻順）と打鍵の並びを順番どおりに対応づける。(候補の index, 打鍵の番号) の並びを返し、
    対応しなかった候補は (index, None)、取りこぼした打鍵は (None, 番号)。費用は [_match_cost] と飛ばす費用"""
    n, m = len(inside), len(strokes)
    cost = [[0.0] * (m + 1) for _ in range(n + 1)]
    back = [[""] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        for j in range(m + 1):
            if i == 0 and j == 0:
                continue
            best = (float("inf"), "")
            if i > 0:
                best = min(best, (cost[i - 1][j] + SKIP_CANDIDATE, "c"))
            if j > 0:
                best = min(best, (cost[i][j - 1] + SKIP_STROKE, "s"))
            if i > 0 and j > 0:
                best = min(best, (cost[i - 1][j - 1] + _match_cost(inside.iloc[i - 1], strokes.iloc[j - 1]), "m"))
            cost[i][j], back[i][j] = best
    out: list[tuple[object | None, int | None]] = []
    i, j = n, m
    while i > 0 or j > 0:
        kind = back[i][j]
        if kind == "m":
            out.append((inside.index[i - 1], j - 1))
            i, j = i - 1, j - 1
        elif kind == "c":
            out.append((inside.index[i - 1], None))
            i -= 1
        else:
            out.append((None, j - 1))
            j -= 1
    return out[::-1]


def feature_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in ID_COLUMNS and c not in LABEL_COLUMNS]


def rule_class(df: pd.DataFrame) -> pd.Series:
    """今のルール（スライド判定）での打鍵の種類。浅い候補はルールでは打鍵にしない"""
    return df.apply(lambda r: "X" if r.isDip == 1 else DIR_TO_CLASS[r.ruleDir], axis=1)
