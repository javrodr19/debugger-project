package com.ghostdebugger.fix

import com.ghostdebugger.model.Issue
import com.ghostdebugger.model.IssueSeverity
import com.ghostdebugger.model.IssueType
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FixerContractTest {
    @Test
    fun `all registered fixers have non-blank ruleId and description`() {
        for (fixer in FixerRegistry.all()) {
            assertTrue(fixer.ruleId.isNotBlank(),
                "${fixer::class.simpleName} has blank ruleId")
            assertTrue(fixer.description.isNotBlank(),
                "${fixer::class.simpleName} has blank description")
        }
    }

    @Test
    fun `NullSafetyFixer generates a non-null fix on its positive fixture`() {
        val fixer = FixerRegistry.all().first { it.ruleId == "AEG-NULL-001" }
        // Minimal fixture that triggers NullSafetyAnalyzer.
        val content = "const [u, setU] = useState(null);\nreturn <div>{u.name}</div>;"
        val issue = Issue(
            id = "c1", type = IssueType.NULL_SAFETY, severity = IssueSeverity.ERROR,
            title = "Null reference: u.may be null", description = "",
            filePath = "/a.tsx", line = 2, ruleId = "AEG-NULL-001"
        )
        assertNotNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `StateInitFixer generates a non-null fix on its positive fixture`() {
        val fixer = FixerRegistry.all().first { it.ruleId == "AEG-STATE-001" }
        val content = "const [items, setItems] = useState();\nitems.map(i => i);"
        val issue = Issue(
            id = "c2", type = IssueType.STATE_BEFORE_INIT, severity = IssueSeverity.ERROR,
            title = "Uninitialized state used: items", description = "",
            filePath = "/a.tsx", line = 2, ruleId = "AEG-STATE-001"
        )
        assertNotNull(fixer.generateFix(issue, content))
    }

    @Test
    fun `AsyncFlowFixer generates a non-null fix on its positive fixture`() {
        val fixer = FixerRegistry.all().first { it.ruleId == "AEG-ASYNC-001" }
        val content = "fetchUser().then(u => setUser(u));"
        val issue = Issue(
            id = "c3", type = IssueType.UNHANDLED_PROMISE, severity = IssueSeverity.ERROR,
            title = "Unhandled promise rejection", description = "",
            filePath = "/a.tsx", line = 1, ruleId = "AEG-ASYNC-001"
        )
        assertNotNull(fixer.generateFix(issue, content))
    }
}
