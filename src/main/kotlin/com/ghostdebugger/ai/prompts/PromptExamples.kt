package com.ghostdebugger.ai.prompts

/**
 * Worked examples for the `detectIssues` JSON-producing prompt.
 *
 * Each example has a matching "OUTPUT_JSON" constant so tests can assert
 * the example bodies are valid JSON — accidental edits break loudly.
 */
object PromptExamples {

    // ---- detectIssues examples -------------------------------------------------

    val EXAMPLE_NULL_SAFETY_OUTPUT_JSON: String = """
[
  {
    "type": "NULL_SAFETY",
    "severity": "ERROR",
    "title": "user may be null when accessing .id",
    "description": "Variable 'user' is initialized as null and accessed without a null check.",
    "line": 3
  }
]
""".trim()

    val EXAMPLE_NULL_SAFETY_BLOCK: String = """
Example 1 — null-safety finding:

Input:
```
const [user, setUser] = useState(null);
if (!loading) {
  return <div>{user.id}</div>;
}
```
Output:
$EXAMPLE_NULL_SAFETY_OUTPUT_JSON
""".trim()

    val EXAMPLE_CIRCULAR_DEP_OUTPUT_JSON: String = """
[
  {
    "type": "CIRCULAR_DEPENDENCY",
    "severity": "WARNING",
    "title": "userService imports orderService which imports userService",
    "description": "Circular import between userService.ts and orderService.ts; one side should depend on a shared abstraction.",
    "line": 1
  }
]
""".trim()

    val EXAMPLE_CIRCULAR_DEP_BLOCK: String = """
Example 2 — circular-dependency finding:

Input:
```
// userService.ts
import { getOrders } from './orderService';
```
Output:
$EXAMPLE_CIRCULAR_DEP_OUTPUT_JSON
""".trim()

    val EXAMPLE_CLEAN_FILE_OUTPUT_JSON: String = "[]"

    val EXAMPLE_CLEAN_FILE_BLOCK: String = """
Example 3 — clean file (no findings):

Input:
```
export function add(a: number, b: number): number {
  return a + b;
}
```
Output:
$EXAMPLE_CLEAN_FILE_OUTPUT_JSON
""".trim()

    val DETECT_ISSUES_EXAMPLES: String = listOf(
        EXAMPLE_NULL_SAFETY_BLOCK,
        EXAMPLE_CIRCULAR_DEP_BLOCK,
        EXAMPLE_CLEAN_FILE_BLOCK
    ).joinToString("\n\n")
}
