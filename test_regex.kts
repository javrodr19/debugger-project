val regexes = listOf(
    """const\s+\[(\w+),\s*set\w+\]\s*=\s*useState\s*\(\s*(?:null|undefined)?\s*\)""",
    """(?:let|var)\s+(\w+)\s*(?::\s*\w+)?\s*=\s*(?:null|undefined)""",
    """const\s+\[(\w+),\s*\w+\]\s*=\s*useState\s*\(\s*\)""",
    """return\s+(?:await\s+)?(?:response|res)\.json\s*\(\s*\)""",
    """(?:setInterval|setTimeout)\s*\(""",
    """^import\s+(?:\{([^}]+)\}|(\w+)|\*\s+as\s+(\w+))\s+from\s+['"]([^'"]+)['"]""",
    """^import\s+['"]([^'"]+)['"]""",
    """^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)""",
    """^(?:export\s+)?const\s+(\w+)\s*(?::\s*[\w<>|&\[\]]+)?\s*=\s*(?:async\s+)?\(""",
    """^export\s+(?:default\s+)?(?:function|class|const|let|var)\s+(\w+)""",
    """^export\s+default\s+(\w+)""",
    """^(?:export\s+)?(const|let|var)\s+(\w+)\s*(?::\s*[\w<>|&\[\]"' ]+)?\s*=\s*(?!(?:async\s+)?\()""",
    """^import\s+([\w.]+)(?:\s+as\s+\w+)?""",
    """(?:suspend\s+)?fun\s+(\w+)\s*\(""",
    """^\s*(?:private\s+|protected\s+|public\s+|internal\s+)?(?:override\s+)?(val|var)\s+(\w+)\s*(?::\s*[\w<>?]+)?\s*=""",
    """(?:class|object)\s+(\w+)""",
    """^import\s+([\w.]+);""",
    """(?:public|private|protected|static|\s)+[\w<>[\]]+\s+(\w+)\s*\([^)]*\)"""
)

for (r in regexes) {
    try {
        Regex(r)
        println("OK: $r")
    } catch (e: Exception) {
        println("FAIL: $r")
        println(e.message)
    }
}
