Compile-Stub fuer onnxruntime-genai-android-0.6.0.aar (nur CI, Refs #198)

Warum: Die Download-URL in app/build.gradle (GitHub-Release v0.6.0) liefert 404; die AAR ist gitignored.
Der Stub bildet NUR die von PhiModule.kt genutzte API nach (Model, Tokenizer, TokenizerStream,
GeneratorParams, Generator, Sequences, GenAIException; Signaturen aus der echten AAR 0.6.0). Er wird nie ausgefuehrt.

Synchron halten: Aendert PhiModule.kt die genutzte genai-API oder wird die AAR-Version angehoben,
muessen die Stubs hier angepasst werden (sonst Kompilierfehler im CI-Job android-unit).

Entfaellt: sobald die Downloadquelle in app/build.gradle repariert ist - dann Ordner und CI-Schritt loeschen.
