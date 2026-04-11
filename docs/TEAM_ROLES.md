# 👥 Reparto de Roles — GhostDebugger

**Configuración recomendada: 3-5 personas**

---

## Distribución por Roles

### ⚙️ Rol 1: Plugin/Backend Lead (Kotlin)

**Responsabilidad principal:** Todo el motor del plugin — parsing, grafo, análisis, IntelliJ integration.

**Tareas:**
- [ ] Setup proyecto Gradle + IntelliJ Platform Gradle Plugin
- [ ] Configurar plugin.xml (actions, tool window, service)
- [ ] FileScanner — descubrir archivos del proyecto (VFS/PSI)
- [ ] SymbolExtractor — extraer funciones, clases, imports con PSI
- [ ] DependencyResolver — resolver imports y dependencias
- [ ] GraphBuilder — construir InMemoryGraph
- [ ] InMemoryGraph — implementar operaciones del grafo
- [ ] Implementar analyzers:
  - [ ] NullSafetyAnalyzer
  - [ ] CircularDependencyAnalyzer
  - [ ] ComplexityAnalyzer
- [ ] AnalysisEngine — orquestar los analyzers
- [ ] IntelliJ Actions (AnalyzeProjectAction, etc.)
- [ ] GhostDebuggerService (project-level service)

**Stack:** Kotlin, IntelliJ Platform SDK, Gradle, Coroutines

**Dependencias:** Independiente — puede empezar desde el minuto 0

---

### 🎨 Rol 2: Frontend Lead (NeuroMap / JCEF)

**Responsabilidad principal:** Todo lo visual — el NeuroMap en React Flow, la UI dentro del IDE, las animaciones.

**Tareas:**
- [ ] Setup webview/ (Vite + React + TailwindCSS)
- [ ] Configurar build para output a src/main/resources/web/
- [ ] NeuroMap con React Flow
  - [ ] CustomNode component (con colores de estado)
  - [ ] CustomEdge component (con animación)
  - [ ] Controles (zoom, fit, filtros)
  - [ ] MiniMap
- [ ] Panel lateral de detalle
  - [ ] Vista de información del nodo
  - [ ] Vista de explicación del error (IA)
  - [ ] Vista de fix con diff
  - [ ] Vista de impacto
- [ ] Bridge JavaScript side (recibir datos de Kotlin, enviar eventos)
- [ ] Animaciones y transiciones
  - [ ] Nodo rojo pulsante
  - [ ] Transición rojo → verde al aplicar fix
  - [ ] Animación de flujo en simulación
- [ ] Loading states y feedback
- [ ] Dark theme coherente con el IDE de JetBrains

**Stack:** React, TypeScript, React Flow, TailwindCSS, Framer Motion

**Dependencias:** Necesita el bridge del Plugin Lead para datos reales (puede empezar con datos mock)

---

### 🤖 Rol 3: IA Lead (OpenAI + Bridge)

**Responsabilidad principal:** Integración con OpenAI, prompts, JCEF bridge, caché.

**Tareas:**
- [ ] Setup OkHttp/Ktor client para llamadas a OpenAI
- [ ] OpenAIConfig + ApiKeyManager (PasswordSafe)
- [ ] Settings UI para configurar API key en el IDE
- [ ] Diseñar prompts:
  - [ ] PromptTemplates.explainIssue()
  - [ ] PromptTemplates.suggestFix()
  - [ ] PromptTemplates.explainSystem()
  - [ ] PromptTemplates.whatIf()
- [ ] OpenAIService — orquestar llamadas al LLM
- [ ] AICache — cachear respuestas con ConcurrentHashMap
- [ ] JcefBridge — comunicación bidireccional Kotlin ↔ JCEF
  - [ ] Enviar datos de grafo/issues al frontend
  - [ ] Recibir eventos del frontend (clicks, fix requests)
- [ ] NeuroMapPanel — JBCefBrowser setup dentro de Tool Window
- [ ] Rate limiting y error handling para OpenAI
- [ ] Crear proyecto de ejemplo con bugs intencionales

**Stack:** Kotlin, OkHttp/Ktor Client, OpenAI API, JCEF, Kotlinx Serialization

**Dependencias:** Necesita el grafo del Plugin Lead para contexto

---

### 🎯 Rol 4: Full-Stack / Integración (si hay 4+ personas)

**Responsabilidad principal:** Conectar plugin con frontend, testing, demo.

**Tareas:**
- [ ] Conectar NeuroMap con datos reales del bridge
- [ ] Conectar panel lateral con explicaciones de OpenAI
- [ ] Implementar flujo "Fix it" end-to-end (UI → Bridge → Kotlin → Editor → UI)
- [ ] WriteCommandAction para aplicar fixes al código del IDE
- [ ] Implementar navigate:file (click en nodo → abrir archivo en editor)
- [ ] Testing del flujo completo
- [ ] Crear y ajustar proyecto de demo
- [ ] Preparar datos de backup (JSON estático para caché)
- [ ] Verificar que la demo funciona sin errores

**Stack:** Todo el stack

**Dependencias:** Necesita que plugin y frontend tengan bridge definido

---

### 🎤 Rol 5: Pitch / Demo (si hay 5 personas, o compartido)

**Responsabilidad principal:** Preparar y dar la presentación.

**Tareas:**
- [ ] Preparar el pitch deck final
- [ ] Ensayar la presentación (3+ veces)
- [ ] Preparar el guión de la demo en IntelliJ
- [ ] Asegurar que la demo funciona con `./gradlew runIde`
- [ ] Preparar plan B (video grabado, caché de OpenAI)
- [ ] Coordinar timing del equipo
- [ ] Documentar el proyecto (README, etc.)

---

## Configuraciones de Equipo

### Equipo de 3 personas

| Persona | Roles |
|---|---|
| **A** | Plugin/Backend Lead (Kotlin) |
| **B** | Frontend Lead (NeuroMap) + parte de Bridge |
| **C** | IA Lead (OpenAI) + Pitch |

### Equipo de 4 personas

| Persona | Roles |
|---|---|
| **A** | Plugin/Backend Lead (Kotlin) |
| **B** | Frontend Lead (NeuroMap) |
| **C** | IA Lead (OpenAI + Bridge) |
| **D** | Integración + Pitch |

### Equipo de 5 personas

| Persona | Roles |
|---|---|
| **A** | Plugin/Backend Lead (Kotlin) |
| **B** | Frontend Lead (NeuroMap) |
| **C** | IA Lead (OpenAI) |
| **D** | Bridge + Integración |
| **E** | Demo / Pitch + Diseño |

---

## Timeline por Rol

```
Horas:    0    3    6    9    12   15   18   21   24
          │    │    │    │    │    │    │    │    │
Plugin:   ├─gradle+xml─├──parser/PSI──├──analyzers──├─actions─├demo─┤
Frontend: ├─vite+react─├──neuromap────├──panel──├──anims──├─polish──┤
IA:       ├─okhttp─────├──prompts─────├──bridge──├──cache──├─demo───┤
Integr:   ├─types──────├──mock data───├──connect all─────├──e2e─┤demo┤
Pitch:    ├────────────├──────────────├──research──├──deck──├ensayo┤go!┤
```

---

## Puntos de Sincronización

Momentos donde **todos se reúnen**:

| Hora | Checkpoint | Objetivo |
|---|---|---|
| **0** | Kickoff | Alinear visión, repartir tareas, `./gradlew build` funciona |
| **3** | Check 1 | Plugin arranca con `runIde`, JCEF muestra React app |
| **6** | Check 2 | PSI extrae símbolos, NeuroMap renderiza nodos mock |
| **9** | Check 3 | Bridge conecta plugin con frontend |
| **12** | Check 4 | Grafo real se muestra en NeuroMap |
| **15** | Check 5 | OpenAI genera explicaciones, "Fix it" funciona |
| **18** | Check 6 | Demo completa funciona end-to-end |
| **21** | Check 7 | Pulido visual y ensayo de demo |
| **22** | Final | Ensayo general del pitch |

---

## Comunicación

- **Canal principal:** Discord/Slack con canal #ghostdebugger
- **Updates:** Cada 2-3 horas, mensaje breve de status
- **Bloqueantes:** Avisar inmediatamente si algo bloquea tu trabajo
- **Git:** Feature branches, merge a main solo cuando funciona
- **Build:** Verificar con `./gradlew build` antes de cada merge
- **Regla de oro:** Si llevas >30 min atascado, pide ayuda
