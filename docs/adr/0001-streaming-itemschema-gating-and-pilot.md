# ADR-0001: OpenAPI 3.2 ストリーミング(itemSchema)対応 — ゲーティング方針とパイロット言語

**Status:** Accepted
**Date:** 2026-10-01
**Deciders:** khayashi4337（PR作者）
**関連ブランチ:** openapi-3.2-streaming（openapi-3.2-codegenから分岐）

## Context

OpenAPI 3.2は`MediaType.itemSchema`（ストリーム内の各要素のスキーマ）、`Encoding.itemEncoding`/
`Encoding.prefixEncoding`（multipart/mixedの位置指定エンコーディング）を新設した。対象メディアタイプは
`text/event-stream`(SSE)、`application/jsonl`、`application/json-seq`、`multipart/mixed`。

この機能基盤（モデル・パース）は、このPRが既に依存しているswagger-core/swagger-parserの
SNAPSHOT（khayashi4337自身の上流フォーク、`oas32-full-support`/`openapi-3.2-full-support`ブランチ）に
既に実装済み（`javap`でjar内`MediaType.getItemSchema()`等を確認、Verified）。openapi-generator側
（本ブランチ）でcodegen層の対応が必要。

既存の3.2対応（`query`/`additionalOperations`/`in:querystring`、ブランチ`openapi-3.2-codegen`）は
「非対応ジェネレータはoperation自体をwarn+skip」という方針を一貫して採用した。itemSchemaにも
同じ方針を機械的に当てはめるべきか、という問いが本ADRの主題。

**重要な既存バグの発見（Fableレビュー、Verified by reading）**: 現状（本ブランチ分岐前のコード）、
`ModelUtils.getSchemaFromContent`（`modules/openapi-generator/src/main/java/org/openapitools/codegen/utils/ModelUtils.java:1482-1494`）は
**content内の先頭media typeの`schema`のみ**を見ており、`itemSchema`を一切見ない。つまり
「itemSchemaのみを持つレスポンス」は現状何の警告も出さずに`schema=null`→戻り値`void`になる。
さらにSSEが先頭に書かれていると、後続の`application/json`等の通常schemaも握りつぶされる。
つまり**「何もしない」という選択肢は、既に静かに壊れている現状を放置することと同義**であり、
比較の基準線（baseline）は「現状維持」ではなく「現状の既存バグを直すこと」である。

## Decision

**ゲーティング方針（Codexレビューで訂正済み。Fable単独案から変更）: 判定単位を「operation全体」
ではなく「generator/library × 送受信方向 × media type」にする。非対応時は(a)同じoperationに
通常の`schema`を持つ代替media typeがあればそれにフォールバック、(b)代替が無ければ既定は
warn+skip、(c)raw表現の公開は明示オプションでのみ提供、(d)全件バッファリングはさらに別の
明示オプション（サイズ上限付き）とし既定にしない。`supportsAdditionalOperations()`等と同じ
パターンで`supportsStreamingResponses()` capability(default false)を新設する。**

**パイロット言語: Python（urllib3系ライブラリ、同期）を採用する。まずJSONL→次にSSEの順で実装する。**

### Codexレビューによる訂正の経緯

Fable単独のレビュー時点では「劣化生成（raw string/binaryとして無条件に生成）」を既定方針として
提案していた。Codexの独立レビューで、この方針には**正しさの問題**があると判明したため訂正した:
**`text/event-stream`(SSE)は無期限に持続する接続を想定した用途が多く、「レスポンス全体を
文字列として読み切ってから返す」という劣化実装は、EOFが来ないストリームに対して呼び出し側を
無期限にハングさせる。** これは「ワイヤー上で値が壊れる」(querystringのケース)とは異なる種類の
不正だが、同程度に深刻（ハングはユーザーから見れば停止したように見える障害）。
Fableの「劣化生成+warning」という大枠の判断基準（既存のskip方針の理由＝ワイヤー上で誤るか否か、
を一貫させる）自体は妥当だが、「劣化生成」の具体的な実装（文字列として読み切る）が
SSEに対して安全でないことまでは検証していなかった。Codexが指摘した「判定単位を
generator/library/方向/media type単位にする」「無いときはwarn+skip、rawは明示オプション」
という、より保守的な既定値を採用する。

## Options Considered（論点1: ゲーティング方針）

### Option A: skip + warning（既存のquery/additionalOperationsと同じ方針）

| Dimension | Assessment |
|---|---|
| 既存パターンとの一貫性 | 高い（コードレビューの負担が小さい） |
| 実装コスト | 中（5箇所の同期が必要: `DefaultGenerator` processPaths/processWebhooks、`DefaultCodegen.preprocessOpenAPI`、`fromCallback`、`InlineModelResolver.addOperationEntries`、`MergedSpecBuilder`。実際に3.2対応では追補が3件出た実績＝Fable指摘） |
| 既存spec互換性 | **悪い**。JSON+SSEを同一operationに併記する実在パターン（OpenAI等の実例、Fable指摘）で、operation自体が生成物から消える＝今回の変更が原因の新規退行になる |

**Pros:** 既存方針と一貫、判断がシンプル
**Cons:** 「ワイヤー上で誤る」わけではないのに消す。query/additionalOperationsがskipを選んだ理由
（劣化出力がワイヤー上で壊れる、`querystring`パラメータの劣化がエンコード済み値を破壊する等）とは
前提が違うのに、同じ結論を機械的に適用してしまう

### Option B(当初案、Fable単独レビュー時点): 劣化生成（string/binaryとして無条件に生成）+ warning

| Dimension | Assessment |
|---|---|
| 既存パターンとの一貫性 | 中（新しい判断軸だが、「ワイヤー上で誤るか否か」という既存方針の**理由**とは一貫する） |
| 実装コスト | 低。判定点は`fromResponse`/`handleMethodResponse`の1箇所に集約できる |
| 既存spec互換性 | 良い。operationは生成され続け、型が薄い（`string`/`binary`）だけになる |
| **正しさの欠陥(Codexが発見)** | **SSEのような無期限ストリームに対し、「レスポンス全体を文字列として
  読み切ってから返す」実装はEOFを待ち続け無期限にハングする。** 有限なJSONL/json-seqでも
  メモリ消費が増える。「ワイヤー上で誤らない劣化」のつもりが、別の種類の障害（ハング）を
  常に埋め込むことになり、「黙って壊れるならskip」という本来の判断基準に抵触する |

**Pros:** 既存specを壊さない。実装点が1箇所に集約できる
**Cons:** 無条件の劣化はSSEに対して安全ではない（Codex指摘）。**不採用、Option Dへ訂正**

### Option C: ユーザー設定オプション化（skip/劣化をCLIフラグで選べるようにする）

**Pros:** 両方の利用者に対応できる
**Cons:** テスト行列が倍になる（Fable指摘）。3.2対応は既にKotlin5巡・PHP3巡のレビューサイクルを
要した実績があり、さらに分岐を増やすことは収束を遅らせる。**却下**

### Option D: media type単位の判定 + 代替フォールバック + 既定warn+skip + raw表現は明示オプション（採用、Codex提案）

| Dimension | Assessment |
|---|---|
| 判定単位 | **operation全体ではなく、generator/library × 送受信方向 × media type単位**。
  `DefaultGenerator.java:1585`付近の入口で一律に落とす既存方式は、JSON+SSE併記operationには
  粗すぎる(Codex指摘) |
| 非対応時の既定動作 | (a)同一operationに通常`schema`を持つ代替media typeがあれば、それを使って
  通常どおり生成する(operationは消えない)。(b)代替が無ければ**既定はwarn+skip**(Option Aと同じ
  結論だが、判定がmedia type単位なので「JSON+SSE併記なら消えない」という既存spec互換性の
  問題が解消される) |
| raw公開 | 明示オプション（generatorオプション）でのみ提供。ユーザーが意図して選ぶので、
  ハングしうることをドキュメントで明示できる |
| 全件バッファリング | さらに別の明示オプションとし、サイズ上限を必須にする。既定にしない |
| CI向け | skipをエラーに昇格できるstrict設定を用意（省略したことに気付けるようにする。
  issue #24212=本PRの出発点と同じ哲学: 「黙って消える」より「分かる形で止まる」） |

**Pros:** SSEのハングという正しさの問題を回避。JSON+SSE併記specの互換性を保つ。既存の
「黙って消えるのを避ける」という本PR全体の哲学(issue #24212)と一貫する
**Cons:** 判定単位がoperation単位からmedia type単位になるため、既存のゲート実装
(`supportsAdditionalOperations()`等、operation単位で判定)とは実装の粒度が異なり、
横展開がOption Aよりやや複雑になる

## Trade-off Analysis（論点1）

当初Fableのレビューのみで「劣化生成」に決めかけたが、Codexの独立レビューで**正しさの欠陥**
（SSEでのハング）が見つかり訂正した。これは「2人以上の独立レビューを経ないまま設計を
確定しない」という本セッション全体の規律が実際に機能した例（Fable単独では見えていなかった
問題をCodexが発見）。最終的な判断基準は以下で一貫させる:

- 「黙って壊れる」(ワイヤー上で値を破壊する、ハングする)ならskip
- 「正しいが不便」(型が薄い、劣化表現になる)なら、ユーザーが明示的に選んだ場合のみ許可
- 判定はoperation単位ではなくmedia type単位にすることで、既存specの互換性問題
  (JSON+SSE併記で操作全体が消える)を避ける

## Options Considered（論点2: パイロット言語）

### Option A: Python（採用）

| Dimension | Assessment |
|---|---|
| 既存の足場 | 高い。`{{operationId}}_without_preload_content`が既に`preload_content=False`の
  生レスポンスを返す変種として存在（`python/api.mustache:202`、`rest.mustache:285-348`、
  `api_client.mustache:187`、Verified）。native実装は「その上に`yield`でSSE/JSONLパーサを
  被せる薄い層」として追加できる |
| イテレーション表現の明確さ | 高い。`Iterator[T]`/generatorで議論の余地が無い |
| ワイヤー検証のしやすさ | 高い。`http.server`を別スレッドで立て`wfile.flush()`で逐次送信するだけ |
| 既存ハーネスの有無 | **無い**。Rust/C#/PHPにはProcessBuilder型のワイヤーキャプチャハーネスが
  あるが、Python/Go/TypeScript/Kotlinは生成テキスト検査のみ（Fable指摘、Verified）。
  Python用に新設が必要 |

### Option B: Go（次点）

| Dimension | Assessment |
|---|---|
| イテレーション表現 | Go 1.23（`go/go.mod.mustache:3`で確認済み）なら`iter.Seq2[T, error]`が使えるが、
  channel方式・callback方式との選択で議論が分かれる余地がある |
| その他 | Pythonと比べて既存の劣化経路の足場が薄い |

### Option C: Rust/Kotlin/C#

**Cons:** ビルド環境が重い（dev-env、kotlinc/cargo/dotnet）。このセッションで何度も
dev-env障害（USB切断・メモリ確保失敗）を経験しており、パイロット段階の高速な反復には
向かない

### Option D: TypeScript

JS/TSの`ReadableStream`/`AsyncIterable`はストリーミングとの適合度が非常に高いが、
既存コードに劣化経路の足場（Pythonの`_without_preload_content`に相当するもの）が
確認されておらず、次点以下

## Consequences

- 楽になること: media type単位の判定により、JSON+SSE併記specの互換性が保たれる
  (operation全体は消えない)。既定がwarn+skip(raw/全件バッファリングは明示オプション)なので、
  SSEのハングを既定動作として埋め込むリスクが無い
- 難しくなること: ゲートの判定粒度がoperation単位(既存の`supportsAdditionalOperations()`等)から
  media type単位に変わるため、既存の横展開パターンをそのまま使えない箇所がある
- 持ち越す論点（Non-goals、本ADRでは決めない。Codex指摘分を含めて拡充）:
  1. **requestBody側のitemSchema**（JSONLアップロード）は対象外とする。`getContent`は
     request/response共有のため`CodegenMediaType`には載るが、native実装の設計は
     レスポンス側のみ検討する
  2. **JSON+SSE併記operationの分割方針**: `DefaultCodegen.splitOperationsByContentType`
     （1151行目）、`CppBoostBeastClientCodegen.inferConditionalSseOperations`
     （156-185行目）に先行実装の手がかりがある。パイロット実装時に決定する
  3. **`schema`と`itemSchema`が同一media typeに併存する場合の優先順位**: Codexが公式仕様
     （https://spec.openapis.org/oas/v3.2.0.html#encoding-by-position）を確認し**併記可能**と
     回答（Fable時点ではAssumedだったが解消）。優先順位の具体的な実装方針はパイロットで決定
  4. **`prefixEncoding`/`itemEncoding`**（multipart位置指定）は、`{{#encoding}}`を
     参照するテンプレートが現状0件（Fable grep確認済み）であるため、本ラウンドでは
     モデルフィールド追加のみに留め、テンプレート対応は別途。Codex指摘: multipartは
     「ストリーミング対応」の下位機能に収まらない——`prefixEncoding`/`itemEncoding`は
     MediaType/Encoding双方の入れ子構造に存在し、有限配列にも適用されるため、現状の
     平坦な`CodegenEncoding`に再帰構造が必要になる大きめの別作業
  5. **SSEのitemSchemaは「解析済みイベント全体」に適用され、`data:`内のJSONだけを指すのではない**
     （Codex指摘、公式仕様
     https://spec.openapis.org/oas/v3.2.0.html#special-considerations-for-server-sent-events）。
     `data`は通常文字列であり、複数data行・コメント・id/retryのパースと、`data`→JSONへの
     追加変換は別契約として設計する。パイロット実装の型モデリングで要反映
  6. **リクエスト送信側のストリーミング、サーバー側ジェネレータ、callback/webhook、
     キャンセル、ストリーム途中のパースエラー、再接続・再送時の重複排除**は、本ADRのスコープ外
     （Codex指摘）。初回実装を「Python同期クライアントのJSONL/SSE受信」に限定すること自体は
     妥当だが、これを3フィールド(itemSchema/itemEncoding/prefixEncoding)全体の対応完了とは
     扱わない
  7. **`axisOf()`(DefaultCodegen.java:1206)のcontent分割が`schema`のみで重複排除している**
     ため、`itemSchema`のみの複数表現が誤って同一視される可能性（Codex指摘）。要素型・
     フレーミング・encodingを区別し、Accept/実際のContent-Type/戻り値型を一致させる設計が
     コア実装で必要

## Action Items

1. [ ] `CodegenMediaType`に`itemSchema`(CodegenProperty)フィールドを追加
   (コンストラクタ・equals・hashCodeも拡張、Codex指摘)
2. [ ] `ModelUtils.getSchemaFromContent`等、itemSchemaを見ていない既存箇所を修正
   （`getSchemaFromContent:1482`、`visitContent:315-323`、`InlineModelResolver.flattenContent:587-610`、
   `OpenAPINormalizer.normalizeContent:556-571`、`axisOf:1209-1216`。Fable/Codex両方が指摘、要個別確認）
3. [ ] `CodegenConfig.supportsStreamingResponses()` capability追加（default false）。
   判定はmedia type単位（Decision参照）
4. [ ] `DefaultCodegen.fromResponse`/`handleMethodResponse`/`fromRequestBody`に、
   (a)対応時は型付き逐次APIを生成 (b)非対応時は代替schemaへフォールバック、無ければwarn+skip
   (c)raw公開・全件バッファリングは明示オプション、のロジックを実装
5. [ ] swagger-parser側の`ResponseProcessor`等が`itemSchema`内の外部`$ref`を解決していない
   問題を確認・必要なら上流フォークに修正を依頼（Fable指摘、Verified by grep）
6. [ ] Pythonでnative実装のプロトタイプ（JSONLを先に、次にSSEパーサ + 新規ワイヤー検証ハーネス）
7. [ ] 合格条件は「最後に全件読めた」ではなく**サーバーが接続を閉じる前に先頭要素を取得できたこと**
   （Codex指摘）。任意のチャンク分割・UTF-8の途中分割・途中終了時の接続解放も確認する
