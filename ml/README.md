# 打鍵の判定の学習

打鍵の候補を「打鍵ではない（X）／単押し（N）／上下左右のフリック（L・U・R・D）」に分類するモデルを学習する。学習は PC の Python で行い、端末には推論だけを載せる（予定）。

## 流れ

1. 端末の「データ収集（提示）」で記録を取る（正解は提示した打鍵）
2. 記録を取り出す: `adb pull /sdcard/Android/data/com.flickxr.app/files/sessions logs/`
3. 打鍵の候補を切り出し、特徴量と一緒に `logs/<session>/candidates.csv` に書き出す（リポジトリのルートで）
   `./gradlew testDebugUnitTest --tests '*ExportCandidatesTest*' -PreplayDir=logs`
   切り出し（`StrokeCandidates`）と特徴量（`StrokeFeatures`）は端末と同じ Kotlin のコードで計算する。学習と推論で値がずれないように
4. 正解を付けてデータセットにする: `ml/.venv/Scripts/python ml/build_dataset.py --logs logs --out ml/data/dataset.csv`
5. 学習して今のルールと比べる: `ml/.venv/Scripts/python ml/train.py --data ml/data/dataset.csv`
6. 全データで学習したモデルを端末用に書き出す: `ml/.venv/Scripts/python ml/export_model.py`
   `app/src/main/assets/stroke_model.txt`（端末の `StrokeModel` が読む）と、Kotlin の推論が Python と一致するかのテスト用の `app/src/test/resources/stroke_model_parity.csv` ができる。アプリをビルドし直すと、判定の「学習」で使われる

## モデル

2 段に分ける。データが少ないうちは、方向まで木に覚えさせるとかえって悪くなったため。

1. LightGBM が「打鍵ではない（X）／単押し（N）／フリック（F）」の 3 択を決める
2. フリックの方向は、触れ始めから最も遠くまで動いた向きで決める

1 打鍵ずつのデータだけのときは「最も遠くまで動いた距離が 1.2 キー以上なら、モデルにかかわらずフリックにする」上書きが効いたが、単語（続けて打つ）のデータを入れると、単押しのあと次のキーへ滑る動きまでフリックにしてしまい悪くなったので、今は使わない（`--override` で試せる）

## 環境

```sh
python -m venv ml/.venv
ml/.venv/Scripts/python -m pip install -r ml/requirements.txt
```

## 正解の付け方

提示 i の時間枠を「提示の時刻 + 0.2 秒」から「次の提示の時刻 + 0.2 秒」までとし、枠の中で始まった候補のうち、普通の候補（浅い候補でない）で一番深いものに、提示した打鍵の種類を付ける。枠の中の残りの候補と、どの枠にも入らない候補は X。

## 候補

- 普通の候補：スライド判定と同じ区切り方（接触しきい値まで下がってから離すまで、押し直しで分ける）
- 浅い候補：しきい値に届かず折り返した上下（ゆるめに拾い、打鍵かどうかはモデルに任せる）

始まりの高さそのものを上げると、打鍵の間も指がその高さより下に残り、続けて打った打鍵が 1 つにつながってしまうので、浅い単押しは別の候補として拾っている。
