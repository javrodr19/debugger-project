# 🎤 Pitch Deck — GhostDebugger

## Presentación para el Jurado del Hackathon JetBrains

---

## Slide 1: Título

<div align="center">

# 👻 GhostDebugger

### **Un desarrollador senior dentro de tu IDE.**

*Plugin para JetBrains que entiende, predice y corrige código como un senior.*

</div>

---

## Slide 2: El Problema

### Los desarrolladores pierden **hasta el 50% de su tiempo** debuggeando

- 😤 Entender proyectos grandes o legacy → **horas de navegación manual**
- 🔍 Encontrar la causa real de un error → **seguir flujos interminables**
- 🤞 Anticipar bugs por asincronía o estados → **solo se ven en producción**
- 📝 Revisar PRs sin saber el impacto real → **riesgo de romper cosas**

### Las herramientas actuales no entienden el sistema completo

| | IntelliJ Inspections | Debugger | Tests | **GhostDebugger** |
|---|---|---|---|---|
| Análisis global del sistema | ❌ | ❌ | ❌ | ✅ |
| Explicación humana (IA) | ❌ | ❌ | ❌ | ✅ |
| Detección de bugs de arquitectura | ❌ | parcial | parcial | ✅ |
| Visualización de arquitectura | ❌ | ❌ | ❌ | ✅ |
| Predicción de fallos | ❌ | ❌ | ❌ | ✅ |
| Fix contextual automático | ❌ | ❌ | ❌ | ✅ |

---

## Slide 3: La Solución

### GhostDebugger = Motor inteligente (Kotlin) + Mapa visual (React Flow) + IA (OpenAI)

```
           ┌────────────────┐
           │  Tu Proyecto   │
           │   en el IDE    │
           └───────┬────────┘
                   │
           ┌───────▼────────┐
           │   ANALIZA 🧠   │  Parsea con PSI API
           │   ENTIENDE      │  Construye el grafo
           │   DETECTA       │  Encuentra bugs reales
           └───────┬────────┘
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
   ┌─────────┐ ┌───────┐ ┌──────┐
   │VISUALIZA│ │EXPLICA│ │ FIJA │
   │   🗺️   │ │  💬   │ │  🔧  │
   │NeuroMap │ │OpenAI │ │ Auto │
   └─────────┘ └───────┘ └──────┘
```

**Todo integrado natively en IntelliJ** — sin cambiar de ventana.

---

## Slide 4: Demo en Vivo

### 🎬 Escena de la demo

1. **Abrimos un proyecto con bugs** en IntelliJ IDEA
2. **`Tools → GhostDebugger → Analyze`** — lanza el análisis
3. **Se genera el NeuroMap** en el Tool Window → nodos con colores
4. **Aparece un nodo rojo** 🔴 → Bug detectado
5. **Click en el nodo** → Explicación humana de OpenAI:
   > *"Tu app falla porque `user` no está listo cuando `Dashboard` renderiza..."*
6. **Pulsamos "Fix it"** 🔧
7. **El código se modifica automáticamente en el editor**
8. **El nodo se vuelve verde** ✅ → Bug corregido

> **Del bug al fix en un clic, sin salir del IDE.**

---

## Slide 5: Funcionalidades Clave

### 🧠 Motor Inteligente (Kotlin + PSI)
- **Análisis global** — No por archivo, sino todo el sistema
- **Detección profunda** — Nulls, race conditions, circular deps
- **Usa la PSI de IntelliJ** — Mismo motor de análisis que el IDE

### 🗺️ NeuroMap (React Flow en JCEF)
- **Grafo interactivo** dentro del IDE — nodos, edges, zoom
- **Semáforo visual** — 🔴 Error | 🟡 Riesgo | 🟢 Estable
- **Análisis de impacto** — "Si cambias esto, afecta a X módulos"

### 🤖 OpenAI GPT-4o Integration
- **Explicación humana** — Como te lo explicaría un senior
- **Fix contextual** — Respeta la arquitectura del proyecto
- **Modo CTO** — "¿Dónde puede romper? ¿Qué es más frágil?"

---

## Slide 6: Tecnología

### Stack sólido, nativo de JetBrains

| Capa | Tecnología |
|---|---|
| Plugin Core | **Kotlin** + IntelliJ Platform SDK |
| Parsing | **PSI API** (mismo motor del IDE) |
| Visualización | **React Flow** en JCEF (Chromium embebido) |
| IA | **OpenAI GPT-4o** (explicación, fix, what-if) |
| Build | **Gradle** + IntelliJ Platform Gradle Plugin |
| Grafo | Estructura propia en memoria (`ConcurrentHashMap`) |

### Enfoque híbrido de análisis

```
PSI API            →  estructura real del código (parsing nativo del IDE)
Heurísticas Kotlin →  detección de bugs comunes
Grafo in-memory    →  relaciones e impacto
OpenAI GPT-4o      →  explicación + predicción + fix
```

---

## Slide 7: Diferenciadores

### ¿Por qué GhostDebugger es diferente?

1. **No es un linter** → Detecta bugs funcionales y de arquitectura
2. **No es un chatbot** → Tiene comprensión estructural del sistema (grafo)
3. **No es un debugger clásico** → No necesita ejecutar la app
4. **No es un diagrama estático** → Es un gemelo digital vivo del proyecto
5. **Nativo del IDE** → No una web aparte, sino un plugin integrado en JetBrains

### Frase clave:
> **"Es el sistema nervioso del código: piensa, ve y actúa — dentro de tu IDE."**

---

## Slide 8: Potencial de Negocio

### Mercado

- **$15B** — mercado global de herramientas de desarrollo
- **73%** de devs reportan que debugging es su mayor pain point
- **30M+** desarrolladores usan IDEs de JetBrains

### Modelo de negocio

| Segmento | Producto | Pricing |
|---|---|---|
| **Dev individual** | Plugin en JetBrains Marketplace | Freemium → $9/mes |
| **Equipo** | Plugin + dashboard web de equipo | $29/usuario/mes |
| **Enterprise** | On-premise + auditoría + soporte | Custom |

### Casos de uso B2B
- Repos legacy grandes
- Equipos distribuidos
- Startups en escalado
- Empresas con deuda técnica

---

## Slide 9: Equipo

### Somos [X] personas que combinan:

- ⚙️ **Kotlin / Plugin** — Motor de análisis nativo de JetBrains
- 🎨 **Frontend** — Experiencia visual impactante con React Flow
- 🤖 **IA** — Integración inteligente con OpenAI
- 📊 **Producto** — Visión de negocio y pitch

---

## Slide 10: Cierre

<div align="center">

# 👻 GhostDebugger

### Plugin inteligente para JetBrains = **Sistema nervioso del código**

- El **motor Kotlin** piensa 🧠
- El **NeuroMap** muestra 🗺️  
- **OpenAI** explica 💬
- El **fix automático** resuelve 🔧

---

> **"No solo detecta errores: entiende el sistema completo y predice fallos antes de que ocurran."**

---

### Gracias.

*¿Preguntas?*

</div>

---

## Notas para el Presentador

### Timing
- **Total:** 5-7 minutos
- Slides 1-2: 1 minuto (problema)
- Slide 3: 30 segundos (solución)
- Slide 4: 2 minutos (demo en vivo en IntelliJ)
- Slides 5-6: 1 minuto (features + tech)
- Slides 7-8: 1 minuto (diferenciales + negocio)
- Slides 9-10: 30 segundos (equipo + cierre)

### Tips
- **Empezar con dolor:** "¿Cuántas horas habéis perdido debuggeando?"
- **Destacar que es nativo JetBrains:** "No es una web aparte. Vive en tu IDE."
- **La demo es el centro:** El momento "Fix it" → verde debe ser el clímax
- **Mencionar Kotlin:** "Hecho en Kotlin, el lenguaje de JetBrains"
- **Hablar de visión, no solo de tech:** "Esto no es un debugger, es un desarrollador senior"
- **Cerrar con ambición:** "Esto puede ser el futuro del debugging en JetBrains"

### Frases de impacto para usar
- "No solo debuggea. Entiende."
- "Ve tu sistema como piensa un senior."
- "Del bug al fix en un clic."
- "El sistema nervioso de tu software."
- "Detecta errores antes de que ocurran."
- "Hecho en Kotlin, para Kotlin developers."
