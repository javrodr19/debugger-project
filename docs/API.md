# 🔌 Services & Bridge API — GhostDebugger

**Tipo:** Plugin interno para JetBrains (no REST API)  
**Comunicación:** Kotlin Services + JCEF Bridge (JSON messages)  
**Versión:** 2.0  

---

## Arquitectura de Comunicación

GhostDebugger **no expone una API REST**. Al ser un plugin de JetBrains, toda la lógica se ejecuta dentro de la JVM del IDE. La comunicación entre el motor (Kotlin) y la UI visual (React/JCEF) se realiza mediante un **bridge JCEF** con mensajes JSON.

```
┌──────────────────┐        JSON messages        ┌──────────────────┐
│  Plugin (Kotlin) │  ◄──────────────────────►   │  NeuroMap (JCEF) │
│                  │  bridge.sendToUI(data)       │                  │
│  GhostDebugger   │  window.__jcefQuery__(msg)   │  React + Flow    │
│  Service         │                              │                  │
└──────────────────┘                              └──────────────────┘
```

---

## 1. Plugin Services (Kotlin)

### GhostDebuggerService — Servicio Principal

El punto de entrada del plugin. Registrado como `projectService` en `plugin.xml`.

```kotlin
@Service(Service.Level.PROJECT)
class GhostDebuggerService(private val project: Project) {
    
    /** Analiza el proyecto completo */
    suspend fun analyzeProject(): AnalysisResult
    
    /** Obtiene el grafo actual */
    fun getGraph(): ProjectGraph?
    
    /** Obtiene detalle de un nodo */
    fun getNodeDetail(nodeId: String): NodeDetail?
    
    /** Solicita explicación IA de un issue */
    suspend fun explainIssue(issueId: String): ExplainIssueResponse
    
    /** Solicita explicación global del sistema */
    suspend fun explainSystem(): ExplainSystemResponse
    
    /** Sugiere un fix para un issue */
    suspend fun suggestFix(issueId: String): SuggestFixResponse
    
    /** Aplica un fix al código fuente */
    suspend fun applyFix(fixId: String): ApplyFixResponse
    
    /** Simula un flujo de ejecución */
    suspend fun simulate(entryNodeId: String): SimulationResult
    
    /** Calcula impacto de un cambio */
    fun analyzeImpact(nodeId: String): ImpactResult
    
    /** Responde pregunta "what if" */
    suspend fun whatIf(question: String): WhatIfResponse
    
    /** Obtiene métricas del proyecto */
    fun getMetrics(): ProjectMetrics
}
```

---

## 2. JCEF Bridge — Kotlin → JavaScript

Mensajes que el plugin envía al frontend JCEF:

### `onGraphUpdate`

Envía el grafo completo al frontend para renderizar en React Flow.

```json
{
  "type": "graph:update",
  "data": {
    "nodes": [
      {
        "id": "node_001",
        "type": "component",
        "name": "Dashboard",
        "filePath": "src/components/Dashboard.tsx",
        "lineStart": 1,
        "lineEnd": 85,
        "complexity": 7,
        "status": "error",
        "issueCount": 1,
        "position": { "x": 250, "y": 100 }
      }
    ],
    "edges": [
      {
        "id": "edge_001",
        "source": "node_001",
        "target": "node_002",
        "type": "import",
        "animated": false
      }
    ],
    "metadata": {
      "projectName": "buggy-react-app",
      "totalFiles": 47,
      "analyzedAt": "2026-04-11T10:30:00Z",
      "analysisTimeMs": 2340
    }
  }
}
```

### `onNodeDetail`

Detalle completo de un nodo cuando el usuario hace click.

```json
{
  "type": "node:detail",
  "data": {
    "node": {
      "id": "node_001",
      "type": "component",
      "name": "Dashboard",
      "filePath": "src/components/Dashboard.tsx",
      "lineStart": 1,
      "lineEnd": 85,
      "complexity": 7,
      "status": "error",
      "code": "const Dashboard = () => {\n  const [user, setUser] = useState(null);\n  ...",
      "dependencies": ["node_002", "node_003"],
      "dependents": ["node_010", "node_011"],
      "issues": [
        {
          "id": "issue_001",
          "type": "null-after-async",
          "severity": "error",
          "title": "Possible null reference after async operation",
          "description": "Variable 'user' may be null when accessed at line 20",
          "line": 20,
          "confidence": 0.92
        }
      ],
      "metrics": {
        "complexity": 7,
        "couplingIn": 2,
        "couplingOut": 5,
        "linesOfCode": 85,
        "dependencyDepth": 3
      }
    }
  }
}
```

### `onExplanation`

Explicación humana de un error generada por OpenAI.

```json
{
  "type": "issue:explanation",
  "data": {
    "issueId": "issue_001",
    "humanExplanation": "Tu app puede fallar porque la variable `user` todavía no tiene valor cuando el componente `Dashboard` intenta renderizar el nombre del usuario. Esto ocurre porque el fetch de datos del perfil es una operación asíncrona que puede tardar, y el componente no espera a que termine antes de intentar acceder a `user.name`.",
    "scenario": "Cuando un usuario hace login y la API tarda más de lo habitual en responder, el Dashboard se renderiza antes de tener los datos listos.",
    "affectedFlow": ["Login", "AuthService", "Dashboard.render()", "UserProfile"],
    "riskLevel": "high"
  }
}
```

### `onFixSuggestion`

Fix contextual sugerido por OpenAI.

```json
{
  "type": "fix:suggestion",
  "data": {
    "fixId": "fix_001",
    "issueId": "issue_001",
    "description": "Añadir una comprobación de null antes de acceder a user y un estado de loading",
    "changes": [
      {
        "filePath": "src/components/Dashboard.tsx",
        "diff": "--- a/src/components/Dashboard.tsx\n+++ b/src/components/Dashboard.tsx\n@@ -18,4 +18,6 @@\n+  if (!user) return <div>Loading...</div>;\n+\n   return (\n-    <h1>Welcome, {user.name}</h1>",
        "lineStart": 18,
        "lineEnd": 22
      }
    ],
    "confidence": 0.88,
    "sideEffects": ["Se mostrará un indicador de carga brevemente"]
  }
}
```

### `onFixApplied`

Confirmación de que se aplicó un fix exitosamente.

```json
{
  "type": "fix:applied",
  "data": {
    "fixId": "fix_001",
    "nodeId": "node_001",
    "newStatus": "healthy"
  }
}
```

### `onImpactResult`

Resultado del análisis de impacto.

```json
{
  "type": "impact:result",
  "data": {
    "sourceNode": "node_001",
    "impactedNodes": [
      { "nodeId": "node_010", "name": "Dashboard", "impactType": "direct", "riskLevel": "high" },
      { "nodeId": "node_015", "name": "UserMenu", "impactType": "transitive", "riskLevel": "medium" }
    ],
    "totalImpact": 12,
    "riskScore": 0.75,
    "summary": "Modificar este componente afecta directamente a 4 módulos e indirectamente a 8 más."
  }
}
```

### `onSimulationStep`

Paso individual de una simulación (enviado secuencialmente para animar).

```json
{
  "type": "simulation:step",
  "data": {
    "simulationId": "sim_001",
    "nodeId": "node_005",
    "status": "pass",
    "description": "AuthService.authenticate() called"
  }
}
```

### `onSimulationResult`

Resultado completo de una simulación.

```json
{
  "type": "simulation:complete",
  "data": {
    "simulationId": "sim_001",
    "result": "failure",
    "failurePoint": "node_010",
    "explanation": "El flujo falla porque el Dashboard intenta renderizar antes de que los datos del usuario estén disponibles.",
    "steps": [
      { "nodeId": "node_001", "status": "pass", "description": "Login component renders" },
      { "nodeId": "node_005", "status": "pass", "description": "AuthService.authenticate() called" },
      { "nodeId": "node_010", "status": "fail", "description": "Dashboard.render() fails — user is null" }
    ]
  }
}
```

### `onSystemExplanation`

Explicación global del sistema generada por OpenAI.

```json
{
  "type": "system:explanation",
  "data": {
    "architecture": "Este proyecto es una aplicación React con un backend Express. Usa autenticación JWT y conecta con una API externa para datos de usuario.",
    "mainModules": [
      { "name": "Authentication", "description": "Gestiona login, logout y tokens JWT", "files": 5, "health": "warning" }
    ],
    "criticalFlows": ["Login → Dashboard → Profile fetch"],
    "weakPoints": ["El módulo de autenticación tiene alta complejidad y no maneja bien los timeouts"],
    "metrics": { "overallHealth": 72, "complexity": "medium", "coupling": "high" }
  }
}
```

### `onWhatIfResponse`

Respuesta a pregunta "what if" generada por OpenAI.

```json
{
  "type": "whatif:response",
  "data": {
    "answer": "Con 10.000 usuarios concurrentes, los principales cuellos de botella serían...",
    "bottlenecks": ["AuthService", "DashboardQueries"],
    "recommendations": ["Implementar caché de tokens JWT", "Añadir paginación"],
    "confidence": 0.72
  }
}
```

### `onAnalysisProgress`

Progreso del análisis (para mostrar loading en UI).

```json
{
  "type": "analysis:progress",
  "data": {
    "step": "Parsing files...",
    "percent": 35,
    "currentFile": "src/App.tsx"
  }
}
```

### `onMetrics`

Métricas globales del proyecto.

```json
{
  "type": "metrics:update",
  "data": {
    "totalFiles": 47,
    "totalFunctions": 234,
    "totalComponents": 28,
    "averageComplexity": 4.2,
    "maxComplexity": { "nodeId": "node_023", "value": 18 },
    "couplingScore": 0.65,
    "healthScore": 72,
    "issuesByType": {
      "null-safety": 3,
      "async-flow": 2,
      "circular-dependency": 1
    }
  }
}
```

---

## 3. JCEF Bridge — JavaScript → Kotlin

Eventos que el frontend envía al plugin cuando el usuario interactúa:

```typescript
// Tipos de eventos que el frontend puede enviar al plugin
type UIEvent =
  | { type: "node:clicked"; nodeId: string }
  | { type: "fix:requested"; issueId: string }
  | { type: "fix:confirmed"; fixId: string }
  | { type: "simulate:requested"; entryNodeId: string }
  | { type: "impact:requested"; nodeId: string }
  | { type: "whatif:question"; question: string }
  | { type: "explain:system" }
  | { type: "explain:issue"; issueId: string }
  | { type: "analysis:cancel" }
  | { type: "navigate:file"; filePath: string; line: number };
```

### Enviar evento al plugin

```typescript
function sendToPlugin(event: UIEvent): void {
  // window.__jcefQuery__ es inyectado por el JBCefJSQuery del plugin
  if (window.__jcefQuery__) {
    window.__jcefQuery__(JSON.stringify(event));
  }
}
```

### Ejemplo: Click en un nodo

```typescript
// En CustomNode.tsx de React Flow
const onNodeClick = (nodeId: string) => {
  sendToPlugin({ type: "node:clicked", nodeId });
};
```

### Ejemplo: Solicitar fix

```typescript
const onFixIt = (issueId: string) => {
  sendToPlugin({ type: "fix:requested", issueId });
};
```

### Ejemplo: Navegar al archivo (abre el archivo en el editor del IDE)

```typescript
const onNavigateToFile = (filePath: string, line: number) => {
  sendToPlugin({ type: "navigate:file", filePath, line });
};
```

---

## 4. OpenAI API Usage

### Endpoints de OpenAI utilizados

| Endpoint | Uso en GhostDebugger |
|---|---|
| `POST /v1/chat/completions` | Explicaciones, fixes, what-if, system summary |

### Modelo

- **Modelo principal:** `gpt-4o` (mejor balance calidad/velocidad)
- **Fallback:** `gpt-4o-mini` (si se necesita mayor velocidad o hay rate limiting)

### Parámetros de configuración

```kotlin
data class OpenAIConfig(
    val apiKey: String,
    val model: String = "gpt-4o",
    val baseUrl: String = "https://api.openai.com/v1",
    val maxTokens: Int = 4096,
    val temperature: Double = 0.3,    // Bajo para respuestas consistentes
    val timeout: Duration = 30.seconds
)
```

### Estimación de uso de tokens

| Operación | Tokens input (aprox) | Tokens output (aprox) |
|---|---|---|
| Explicar un issue | ~1,500 | ~500 |
| Sugerir un fix | ~2,000 | ~800 |
| Explicar el sistema | ~3,000 | ~1,000 |
| Responder what-if | ~2,500 | ~600 |

---

## 5. Error Handling

Todos los errores se manejan internamente en el plugin y se comunican al usuario:

```kotlin
sealed class GhostDebuggerError {
    data class ParseError(val file: String, val message: String) : GhostDebuggerError()
    data class AnalysisError(val analyzer: String, val message: String) : GhostDebuggerError()
    data class OpenAIError(val statusCode: Int, val message: String) : GhostDebuggerError()
    data class OpenAIRateLimited(val retryAfterSeconds: Int) : GhostDebuggerError()
    data class OpenAIKeyMissing(val message: String = "OpenAI API key not configured") : GhostDebuggerError()
    data class FixApplicationError(val fixId: String, val message: String) : GhostDebuggerError()
}
```

Los errores se muestran al usuario usando el sistema de **Notifications** de IntelliJ:

```kotlin
NotificationGroupManager.getInstance()
    .getNotificationGroup("GhostDebugger")
    .createNotification("Error: ${error.message}", NotificationType.ERROR)
    .notify(project)
```
