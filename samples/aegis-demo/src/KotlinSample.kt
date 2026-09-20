package demo

// Triggers AEG-CAST-KT-001 (KotlinUnsafeCastAnalyzer): `Any` is not a subtype of `String`, so
// this `as` downcast is not provably safe and is not an upcast/identity cast either.
fun parseConfigValue(value: Any): String = value as String

// Triggers AEG-REDUNDANT-LET-KT-001 (KotlinRedundantLetAnalyzer): `value` is already a
// non-nullable `String` parameter, so the `?.` safe call and the single-statement `let` block
// are redundant. Both functions in this file compile cleanly — the unnecessary-safe-call
// diagnostic Kotlin emits here is a warning, not an error, so CompilationErrorAnalyzer does not
// exclude this file from the late analysis pass.
fun logConfigValue(value: String) {
    value?.let { println(it) }
}
