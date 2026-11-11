# 速記録の修正アプリ

Iさん専用のAndroidアプリ。Googleドライブ「速記録議事録」フォルダの速記録を開き、
赤字の［要確認］［不明］をスマホで直して「_修正済」として別名保存する。

- パッケージ名: `com.kojiiino.sokkiroku`
- ビルド: main に push すると GitHub Actions が APK を作り、Releases に置く
- 署名鍵: リポジトリには置かない。Actions のシークレット `KEYSTORE_BASE64` と `KEYSTORE_PASSWORD` から復元する
- ビルドログ: `build-log` ブランチの `build-log.txt`
