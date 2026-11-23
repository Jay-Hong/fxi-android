# S8-7 tether free preview closure — review record

`S87PreviewClosureTest` compares its report with the files in this directory. Each file is the analyzer's output, copied byte
for byte from `build/s87_closure_report/<buildType>/`. None of them was written by hand.

The roots are the classes of these sources:

- `ui/free/FreeSourceAlertPreviewQuotes.kt`
- `ui/free/FreeSourceAlertPreviewSession.kt`
- `ui/free/FreeSourceAlertPreviewSection.kt`
- `ui/free/FreeSourceAlertPreviewSheet.kt`
- `ui/alert/SourceAlertDraftRules.kt`
- `ui/components/SubscribeCTA.kt`

## Files and variants

| file | variants | sha256 |
|---|---|---|
| closure_app_classes.txt | debug, ciMinified, benchmark, s5Benchmark (identical) | 48ee508c66493ee9e933696f814d9a5a219188721e6dc5202756f9b09c3f8a58 |
| closure_callbacks.txt | all four (identical) | e8e738a8ae060339992f550fff5231b3522e686444d98ed89f8be86660836af3 |
| closure_sources.txt | all four (identical) | 7ba6f147b6c8359752196c62d8f2e6ff3e9694db34edee357d095feb15ad9d16 |
| closure_versions.txt | all four (identical) | 7491fa63d0dab9261842f9b8f7d51e95f5191e381f25a717707614134a3a4faa |
| closure_uses.txt | debug | b0d47eda0fcadd34141ee6008b782c09c2cc18f3a258aefd439d1509ebaa491e |
| closure_uses.release.txt | ciMinified, benchmark, s5Benchmark (identical) | 616c6574be9e0b1fde64d01706de135c322a907cbf3aa4c6268d216ef98b1d02 |

The two uses lists differ in two members only. Both differences are library versions, not new capabilities:

- `ClickableKt.clickable-…$default` has a different mangled name and descriptor.
- `ComposablesKt.getCurrentCompositeKeyHash…` is the `Code`/`Hash` variant.

The reason is the one recorded for the bank preview closure (`s7e5d/closure_review.md` 개정 4): debug compiles against foundation
1.9.2, and the release variants against 1.7.2.

## Review of what is reached

- **Unresolved, and static writes outside a class initialiser:** none in any variant (`unresolved_and_writes.txt` is empty).
- **App classes reached (66).** All of them are these:
  - the tether preview's own classes: session, state, signal, host lambdas, `requestHide`, the sheet and its
    `ComposableSingletons`, the section, the quotes and the tags;
  - the shared `SourceAlertDraftRules` and the value type `SourceAlertEditorInput`;
  - `PreviewHostContext` and `PreviewSignalOrigin`, the bank file's read-only seam values;
  - `SourceAlertRowPolicy` and its companion;
  - pure domain models: `AlertCondition`, `Exchange`, `FreeRate.Grouped`, `SourceRate`, `RateSanity`, `RepeatInterval`, `FreeTab`,
    `SupportedCurrency`, `Bank` and `ExchangeRate`;
  - `RepeatIntervalChange`;
  - `AlertConditionExtensionsKt`;
  - the CTA and its tags;
  - the theme colours.

  A few classes arrive because the analyzer follows a whole class at a time:
  - `SourceAlertRowPolicy.adopt` pulls in `SourceAlertSetting`, its serializer and `InstantSerializer`;
  - `FreeTab` pulls in `SupportedCurrency` and `Bank`;
  - `FreeRate.Grouped` pulls in `ExchangeRate`.

  They are pure models and serializers. None of them is an owner, repository, store, permission, push, topic or network class.
- **Not reached:** `SourceAlertEditor` (the premium object, with its submit and result types), `SourceAlertEditorSheet`,
  `SourceAlertSection*`, `SourceAlertSettingsOwner`, `SourceHistory*` and `LastSelectedSourceMemory`. `C02` keeps all of them out
  whether or not they appear in these lists.
- **Sources (23).** The list names `ui/alert/SourceAlertEditor.kt` because the value type `SourceAlertEditorInput` is declared in
  that file. Its other classes are not reached. The list names `ui/free/FreeAlertPreviewSession.kt` because `PreviewHostContext`
  and `PreviewSignalOrigin` are declared there.
- **Library members.** They are Compose UI, Material 3 sheet, kotlinx serialization (from the reached serializers), kotlinx
  datetime, collections, `BigDecimal`, `NumberFormat` and `R$drawable`. There is no saving, notification manager, system service,
  `Context`, SharedPreferences, reflection, Firebase or Hilt member. `C02` checks this with `FORBIDDEN_USE`.
- **Callbacks (15).** Rows 1–11 of `closure_callbacks.txt` belong to the session/host SourceFile, rows 12–15 to the sheet's.
  Mapped by owner, descriptor and the source (`FreeSourceAlertPreviewSession.kt:205–311`):
  - row 1: the default `hide` seam invoking its `realHide`;
  - rows 2–4: the delivery effect invoking `onSubscribe`, `onEvent` and `beforeDeliver`;
  - rows 5–6: the host's and the modal's disposal reports (`onContext(null)`, `removed`);
  - row 7: `requestHide` invoking `hide`;
  - row 8: the context report;
  - row 9: `content(opener)`;
  - row 10: `finish` passing the signal on;
  - row 11: the `shown` report;
  - rows 12–15: the sheet's threshold, condition, repeat and source inputs, each through `onInput`.

  The screen supplies its own `onSubscribe` and uses the default seams (`FreeSnapshotScreen.kt:169–175`). No other class invokes a
  function value. `C02` also rejects any callback site outside these two files or with another function arity. The reachable
  `RepeatIntervalChange.Keep` is the edit rejection's reference (`FreeSourceAlertPreviewSession.kt:125`); it is not a choice.

## Regeneration

These files depend on the hashes of the reached production sources, which are listed in `closure_sources.txt`. If any of those
bytes change, the files must be regenerated from the test's report and reviewed again. A failing first run is not approved by
copying it automatically.
