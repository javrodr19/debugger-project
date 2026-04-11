# 🤝 Guía de Contribución — GhostDebugger

---

## Bienvenido

¡Gracias por querer contribuir a GhostDebugger! 👻  
Esta guía te ayudará a configurar el entorno y seguir las convenciones del proyecto.

---

## Setup del Entorno

### Requisitos previos
- **JDK** 17+ (recomendado: JetBrains Runtime o Corretto)
- **Gradle** 8.x (wrapper incluido, no necesitas instalar)
- **Node.js** >= 22.x LTS (para compilar el frontend / NeuroMap)
- **npm** >= 10.x
- **Git**
- **API Key de OpenAI** (para features de IA)
- **IntelliJ IDEA** (Community o Ultimate, para desarrollo del plugin)

### Instalación

```bash
# 1. Clonar el repo
git clone https://github.com/your-org/ghostdebugger.git
cd ghostdebugger

# 2. Copiar configuración
cp .env.example .env
# Editar .env con tu API key de OpenAI

# 3. Compilar el frontend (NeuroMap)
cd webview
npm install
npm run build   # Output va a src/main/resources/web/
cd ..

# 4. Compilar el plugin
./gradlew build

# 5. Ejecutar IDE sandbox con el plugin cargado
./gradlew runIde
```

Esto abre una instancia de IntelliJ IDEA con GhostDebugger instalado.

### Desarrollo Iterativo

```bash
# Terminal 1: Frontend en modo watch (recompila al guardar)
cd webview && npm run dev

# Terminal 2: Re-compilar y relanzar el plugin
./gradlew runIde
```

---

## Estructura del Proyecto

```
ghostdebugger/
├── src/main/kotlin/       # Plugin core (Kotlin)
├── src/main/resources/    # Plugin resources (plugin.xml, icons, web/)
├── src/test/kotlin/       # Tests
├── webview/               # Frontend React (fuentes del NeuroMap)
├── docs/                  # Documentación
└── sample-projects/       # Proyectos de ejemplo para testing
```

---

## Convenciones de Código

### Kotlin (Plugin Core)
- Seguir las [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Usar **data classes** para modelos de datos
- Usar **sealed classes** para jerarquías cerradas (eventos, errores)
- Usar **coroutines** para operaciones asíncronas (llamadas a OpenAI, análisis largo)
- Evitar `!!` (non-null assertion) — usar `?.let {}`, `?: return`, etc.
- Preferir `val` sobre `var`
- Anotar servicios con `@Service`

```kotlin
// ✅ Bueno
data class GraphNode(
    val id: String,
    val name: String,
    val status: NodeStatus
)

sealed class NodeStatus {
    data object Healthy : NodeStatus()
    data object Warning : NodeStatus()
    data class Error(val issues: List<Issue>) : NodeStatus()
}

// ✅ Bueno — coroutine para operación async
suspend fun explainIssue(issue: Issue): String {
    return openAIService.callCompletion(
        prompt = PromptTemplates.explainIssue(issue)
    )
}
```

### Naming (Kotlin)
- **Archivos/Clases:** PascalCase (`GraphBuilder.kt`, `NullSafetyAnalyzer.kt`)
- **Funciones/Propiedades:** camelCase (`analyzeProject()`, `nodeCount`)
- **Constantes:** SCREAMING_SNAKE_CASE (`MAX_RETRY_COUNT`)
- **Packages:** lowercase (`com.ghostdebugger.analysis`)
- **Enums:** PascalCase values (`NodeStatus.HEALTHY`)

### TypeScript/React (Frontend / NeuroMap)
- Usar **functional components** con hooks
- Un componente por archivo
- Props tipadas con interface
- Destructurar props en la firma

```tsx
interface DetailPanelProps {
  node: GraphNode;
  onClose: () => void;
}

export const DetailPanel = ({ node, onClose }: DetailPanelProps) => {
  // ...
};
```

### Estilos (Frontend)
- Usar **TailwindCSS** para estilos
- Animaciones con Framer Motion o clases de Tailwind
- Dark theme por defecto (coherente con el IDE)

---

## Git Workflow

### Branches

```
main              # Producción / estable
├── develop       # Integración
├── feature/*     # Nuevas funcionalidades
├── fix/*         # Corrección de bugs
└── docs/*        # Documentación
```

### Naming de branches

```
feature/neuromap-zoom
feature/null-safety-analyzer
feature/openai-explain-issue
fix/jcef-bridge-crash
docs/architecture-update
```

### Commits

Formato de commit messages (Conventional Commits):

```
<type>(<scope>): <description>

[body opcional]
```

**Tipos:**
- `feat` — Nueva funcionalidad
- `fix` — Corrección de bug
- `docs` — Documentación
- `style` — Formato (sin cambio de lógica)
- `refactor` — Refactoring (sin cambio de funcionalidad)
- `test` — Añadir o corregir tests
- `chore` — Tareas de mantenimiento

**Ejemplos:**
```
feat(parser): add PSI-based symbol extraction for TypeScript
feat(ai): implement explainIssue prompt with OpenAI GPT-4o
fix(bridge): fix JSON serialization for GraphNode sealed class
refactor(graph): simplify cycle detection with DFS
```

### Pull Requests

1. Crea una branch desde `develop`
2. Haz tus cambios con commits descriptivos
3. Asegúrate de que `./gradlew build` pasa sin errores
4. Crea PR hacia `develop`
5. Describe qué cambios hiciste y por qué
6. Espera aprobación de al menos 1 persona

---

## Testing

### Kotlin (Plugin)
```bash
./gradlew test              # Ejecutar tests
./gradlew test --info       # Con output detallado
```

### Frontend (NeuroMap)
```bash
cd webview
npm run test
```

### Qué testear
- **Analyzers:** Cada regla heurística debe tener tests unitarios
- **Parser:** Tests con snippets de código como fixtures
- **Graph:** Tests de operaciones del grafo (cycles, impact, paths)
- **AI:** Tests mockeando las respuestas de OpenAI
- **Bridge:** Tests de serialización/deserialización de mensajes

---

## Estructura de un Analyzer

Para añadir un nuevo analyzer:

```kotlin
// src/main/kotlin/com/ghostdebugger/analysis/analyzers/MyNewAnalyzer.kt

class MyNewAnalyzer : Analyzer {
    override val name = "my-new-analyzer"
    
    override fun analyze(context: AnalysisContext): List<Issue> {
        val issues = mutableListOf<Issue>()
        
        for (node in context.graph.nodes) {
            if (detectProblem(node, context)) {
                issues.add(
                    Issue(
                        id = "${name}-${node.id}",
                        type = IssueType.CUSTOM,
                        severity = IssueSeverity.WARNING,
                        nodeId = node.id,
                        title = "Descriptive title",
                        description = "Technical description",
                        affectedNodes = emptyList(),
                        confidence = 0.85
                    )
                )
            }
        }
        
        return issues
    }
    
    private fun detectProblem(node: GraphNode, context: AnalysisContext): Boolean {
        // Tu lógica de detección aquí
        return false
    }
}
```

Luego registra en el engine:

```kotlin
// src/main/kotlin/com/ghostdebugger/analysis/AnalysisEngine.kt

val analyzers = listOf(
    NullSafetyAnalyzer(),
    CircularDependencyAnalyzer(),
    ComplexityAnalyzer(),
    MyNewAnalyzer()  // ← Añadir aquí
)
```

---

## OpenAI Integration

### Configuración

La API key de OpenAI se puede configurar de dos formas:

1. **IDE Settings:** `Settings → Tools → GhostDebugger → API Key`
2. **Variable de entorno:** `OPENAI_API_KEY` en `.env`

La key se almacena de forma segura usando el **PasswordSafe** de IntelliJ (encriptada).

### Añadir un nuevo prompt

```kotlin
// src/main/kotlin/com/ghostdebugger/ai/prompts/PromptTemplates.kt

object PromptTemplates {
    fun myNewPrompt(context: String) = """
        You are a senior software developer.
        
        ## Context
        $context
        
        ## Task
        Your task description here.
        
        Respond in Spanish. Be concise.
    """.trimIndent()
}
```

### Rate Limiting

Las llamadas a OpenAI están rate-limited a 20 requests/minuto por defecto. Ajustable en settings.

---

## Variables de Entorno

```env
# .env
OPENAI_API_KEY=sk-...          # API key de OpenAI (alternativa a IDE Settings)
OPENAI_MODEL=gpt-4o            # Modelo a usar (default: gpt-4o)
LOG_LEVEL=debug                 # Nivel de logs (debug | info | warn | error)
AI_CACHE_TTL=3600              # TTL del caché de IA en segundos
MAX_FILES=1000                  # Máximo de archivos a analizar
```

---

## Build Commands

```bash
# Compilar el plugin
./gradlew build

# Ejecutar IDE sandbox
./gradlew runIde

# Ejecutar tests
./gradlew test

# Generar plugin zip (para distribuir)
./gradlew buildPlugin

# Verificar compatibilidad con IntelliJ
./gradlew verifyPlugin

# Compilar frontend
cd webview && npm run build

# Frontend en modo desarrollo
cd webview && npm run dev
```

---

## Troubleshooting

### La API de OpenAI no responde
- Verifica que tu API key es correcta en Settings o `.env`
- Verifica que tienes créditos en tu cuenta de OpenAI
- El sistema funciona sin IA pero no genera explicaciones ni fixes
- Revisa los logs de IntelliJ: `Help → Show Log in Explorer/Finder`

### PSI no encuentra archivos
- Verifica que el proyecto está abierto y indexado en IntelliJ
- Espera a que termine la indexación (`Scanning files...`)
- Verifica que los tipos de archivo están soportados

### JCEF no renderiza el NeuroMap
- Verifica que compilaste el frontend: `cd webview && npm run build`
- Verifica que los archivos están en `src/main/resources/web/`
- Revisa la consola de JCEF en `Help → Show Log in Explorer/Finder`
- JCEF requiere hardware acceleration en algunos sistemas

### `./gradlew runIde` falla
- Verifica que tienes JDK 17+: `java -version`
- Intenta con `./gradlew clean build runIde`
- Revisa que `gradle.properties` tiene el `platformVersion` correcto

---

## Contacto

- **Issues:** Usa GitHub Issues para bugs y feature requests
- **Canal:** #ghostdebugger en Discord/Slack del equipo
