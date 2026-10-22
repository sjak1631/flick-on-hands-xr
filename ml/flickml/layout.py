"""学習で使うクラス（打鍵の種類）と、かなモードのキー配列（app の FlickLayout と同じ）。"""

# 打鍵ではない（X）、単押し（N）、フリック（左・上・右・下）
CLASSES = ["X", "N", "L", "U", "R", "D"]
DIR_TO_CLASS = {"NONE": "N", "LEFT": "L", "UP": "U", "RIGHT": "R", "DOWN": "D"}

# 文字は「中央・左・上・右・下」の順（空文字はその方向なし）。機能キーは記録での表記（SessionLog.encode）
KANA = [
    [list("あいうえお"), list("かきくけこ"), list("さしすせそ"), ["<BS>"]],
    [list("たちつてと"), list("なにぬねの"), list("はひふへほ"), ["<SP>"]],
    [list("まみむめも"), ["や", "「", "ゆ", "」", "よ"], list("らりるれろ"), ["<EN>"]],
    [["<MOD>"], ["わ", "を", "ん", "ー"], list("、。？！…"), ["<MODE>"]],
]
_DIRS = ["N", "L", "U", "R", "D"]

# 記録の文字 → (行, 列, 種類)
ACTION_TO_STROKE: dict[str, tuple[int, int, str]] = {
    ch: (r, c, _DIRS[i]) for r, row in enumerate(KANA) for c, key in enumerate(row) for i, ch in enumerate(key) if ch
}
