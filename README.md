<p align="center">
  <img src="docs/assets/logo-placeholder.png" alt="GhostDebugger Logo" width="200"/>
</p>

<h1 align="center">👻 GhostDebugger</h1>

<p align="center">
  <strong>Un desarrollador senior dentro de tu IDE.</strong><br/>
  Plugin para JetBrains que entiende, predice y corrige código como un senior.
</p>

<p align="center">
  <a href="#características">Características</a> •
  <a href="#arquitectura">Arquitectura</a> •
  <a href="#instalación">Instalación</a> •
  <a href="#uso">Uso</a> •
  <a href="#contribuir">Contribuir</a>
</p>

---

## 🎯 ¿Qué es GhostDebugger?

GhostDebugger **no es un debugger tradicional**. Es un plugin para el ecosistema **JetBrains** (IntelliJ IDEA, WebStorm, PyCharm, etc.) que analiza el proyecto completo, detecta errores reales, explica por qué ocurren en lenguaje humano, simula fallos antes de que pasen y propone correcciones alineadas con la arquitectura del proyecto.

> *"No solo debuggea. Entiende."*

### ¿Qué lo hace diferente?

| Herramienta tradicional | GhostDebugger |
|---|---|
| Analiza archivo por archivo | Analiza el proyecto completo |
| Muestra logs técnicos | Explica errores en lenguaje humano (OpenAI) |
| Solo detecta errores de sintaxis | Detecta bugs funcionales y de arquitectura |
| Sin contexto visual | Mapa interactivo del sistema (NeuroMap) |
| Fixes genéricos | Fixes contextuales respetando la arquitectura |

## ✨ Características

### 🧠 Motor Inteligente de Debugging (Kotlin)
- **Análisis global del proyecto** — Parsea todo el repositorio usando la IntelliJ PSI y construye una visión global del sistema
- **Detección de errores reales** — Nulls probables, condiciones de carrera, memory leaks, dependencias circulares
- **Explicación humana** — Cada error viene explicado de forma clara y comprensible vía OpenAI GPT-4o
- **Fix automático contextual** — Correcciones que respetan la arquitectura y patrones del proyecto
- **Simulación sin ejecutar** — Predice fallos simulando caminos de ejecución

### 🗺️ NeuroMap: Mapa Visual del Sistema (JCEF + React Flow)
- **Grafo vivo del proyecto** — Nodos y conexiones representando archivos, funciones y dependencias
- **Semáforo visual** — 🔴 Error | 🟡 Riesgo | 🟢 Estable
- **Simulación visual de flujo** — Ve cómo se ejecuta el código paso a paso
- **Análisis de impacto** — "Si cambias esto, afecta a X partes del sistema"
- **Explicación global automática** — "Explícame el sistema" en un clic

### 🔮 Modo CTO / What If (OpenAI)
- "¿Qué pasa si esto escala a 10.000 usuarios?"
- "¿Dónde puede romper?"
- "¿Qué módulo es más frágil?"

## 🏗️ Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│                    JetBrains IDE (IntelliJ Platform)          │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Plugin Core (Kotlin)                     │   │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │   │
│  │  │  Parser  │  │  Graph   │  │  Analysis Engine  │  │   │
│  │  │(PSI API) │  │  Builder │  │  (Heurísticas)    │  │   │
│  │  └──────────┘  └──────────┘  └───────────────────┘  │   │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │   │
│  │  │ AI Layer │  │ Fix      │  │  Impact / Sim     │  │   │
│  │  │(OpenAI)  │  │ Applier  │  │  Engine           │  │   │
│  │  └──────────┘  └──────────┘  └───────────────────┘  │   │
│  └────────────────────┬─────────────────────────────────┘   │
│                       │ JCEF Bridge (JSON messages)          │
│  ┌────────────────────┴─────────────────────────────────┐   │
│  │          NeuroMap UI (JCEF / React Flow)               │   │
│  │  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │   │
│  │  │ NeuroMap  │  │ Bug Panel│  │  Fix/Explain UI   │  │   │
│  │  │(ReactFlow)│  │          │  │                   │  │   │
│  │  └──────────┘  └──────────┘  └───────────────────┘  │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

## 🚀 Instalación

### Desarrollo del Plugin

```bash
# Clonar el repositorio
git clone https://github.com/your-org/ghostdebugger.git
cd ghostdebugger

# Configurar OpenAI API key
cp .env.example .env
# Editar .env con tu API key de OpenAI

# Construir el plugin
./gradlew build

# Ejecutar IDE sandbox con el plugin
./gradlew runIde
```

### Requisitos
- **JDK** 17+
- **Gradle** 8.x (wrapper incluido)
- **Node.js** 22.x (para el frontend del NeuroMap)
- **API Key de OpenAI** (para explicaciones y fixes con IA)

## 📖 Uso

1. **Abrir un proyecto** en IntelliJ IDEA u otro IDE de JetBrains
2. **Activar GhostDebugger** — `Tools → GhostDebugger → Analyze Project`
3. **Explorar el NeuroMap** — Se abre un tool window con el grafo visual
4. **Inspeccionar nodos** — Click en cualquier nodo para ver detalles
5. **Corregir errores** — Pulsa "Fix it" en nodos rojos
6. **Simular flujos** — Activa el modo simulación para predecir fallos

## 📂 Estructura del Proyecto

```
ghostdebugger/
├── docs/                          # Documentación completa
│   ├── PRD.md                     # Product Requirements Document
│   ├── ARCHITECTURE.md            # Documento de arquitectura
│   ├── API.md                     # Documentación de servicios internos
│   ├── CONTRIBUTING.md            # Guía de contribución
│   ├── MVP_PLAN.md                # Plan MVP 24-48h
│   ├── TEAM_ROLES.md              # Reparto de roles
│   ├── PITCH.md                   # Pitch para el jurado
│   ├── USER_STORIES.md            # Historias de usuario
│   └── DESIGN_SYSTEM.md           # Sistema de diseño
├── src/main/kotlin/               # Plugin core (Kotlin)
│   └── com/ghostdebugger/
│       ├── parser/                # Motor de parsing (PSI)
│       ├── graph/                 # Constructor de grafo
│       ├── analysis/              # Motor de análisis
│       ├── ai/                    # Integración con OpenAI
│       ├── actions/               # IntelliJ Actions
│       ├── toolwindow/            # Tool Window registration
│       └── bridge/                # JCEF ↔ Kotlin bridge
├── src/main/resources/
│   ├── META-INF/plugin.xml        # Plugin descriptor
│   └── web/                       # Frontend web (NeuroMap)
├── webview/                       # Fuentes del frontend (React)
│   ├── src/
│   ├── package.json
│   └── vite.config.ts
├── src/test/kotlin/               # Tests
├── build.gradle.kts               # Build config (Gradle + IntelliJ Plugin)
├── settings.gradle.kts
├── gradle.properties
├── sample-projects/               # Proyectos de ejemplo para demo
│   └── buggy-react-app/
├── .env.example
├── .gitignore
├── LICENSE
└── README.md
```

## 🤝 Contribuir

Ver [CONTRIBUTING.md](docs/CONTRIBUTING.md) para guía detallada.

## 📜 Licencia

MIT License — ver [LICENSE](LICENSE) para detalles.

---

<p align="center">
  <strong>👻 GhostDebugger</strong> — El sistema nervioso de tu código.<br/>
  <em>Plugin para JetBrains • Kotlin + OpenAI + React Flow</em>
</p>
