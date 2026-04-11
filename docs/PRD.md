# 📋 Product Requirements Document (PRD)

## GhostDebugger — Sistema inteligente de análisis y debugging de código

**Versión:** 1.0  
**Fecha:** 2026-04-11  
**Estado:** Draft  
**Autor:** Equipo GhostDebugger  

---

## 1. Resumen Ejecutivo

GhostDebugger es un **plugin para la plataforma JetBrains** (IntelliJ IDEA, WebStorm, etc.) que analiza un proyecto completo, lo transforma en un modelo estructural, detecta errores y riesgos reales, los explica de manera comprensible usando **OpenAI GPT-4o**, visualiza la arquitectura como un mapa interactivo (NeuroMap) y propone fixes contextuales — todo dentro del IDE.

### Visión
> "Un desarrollador senior dentro de tu IDE."

### Misión
Reducir drásticamente el tiempo que los desarrolladores dedican a entender, depurar y corregir código, proporcionando una comprensión global del sistema con capacidades de detección, explicación y corrección automática.

---

## 2. Problema

Los desarrolladores y equipos técnicos pierden una cantidad significativa de tiempo en:

- **Comprensión de código:** Entender proyectos grandes o legacy requiere horas/días de navegación manual
- **Localización de causa raíz:** Los debuggers tradicionales muestran síntomas, no causas
- **Seguimiento de flujos:** Trazar ejecución entre frontend, backend y APIs es tedioso y propenso a error
- **Anticipación de fallos:** Los bugs por asincronía, estados o condiciones de carrera no se ven hasta producción
- **Revisión de PRs:** El impacto real de un cambio se desconoce hasta que rompe algo
- **Onboarding:** Nuevos miembros del equipo tardan semanas en entender la arquitectura

### Limitaciones de herramientas actuales

| Herramienta | Limitación |
|---|---|
| IntelliJ Inspections | Detectan errores locales, no bugs de arquitectura global |
| Linters (ESLint, detekt) | Solo errores de sintaxis/estilo, no bugs funcionales |
| Debuggers (IDE debugger) | Requieren ejecución, no predicen |
| Stack traces | Técnicos, sin contexto de negocio |
| Tests unitarios | Aislados, no ven relaciones entre módulos |
| Code review manual | Lento, subjetivo, no escalable |

---

## 3. Propuesta de Valor

### Diferenciadores clave

1. **Análisis global, no por archivo** — Comprensión de extremo a extremo del sistema
2. **Explicación humana** — Errores explicados como lo haría un senior
3. **Detección de bugs reales** — Más allá del linting: bugs funcionales y arquitecturales
4. **Visualización de arquitectura** — Mapa interactivo del sistema completo
5. **Simulación sin ejecutar** — Predicción de fallos sin necesidad de correr la app
6. **Fix contextual** — Correcciones que respetan la arquitectura del proyecto
7. **Análisis de impacto** — Saber qué rompe un cambio antes de hacerlo

---

## 4. Usuarios Objetivo

### 4.1 Persona primaria: Desarrollador individual
- **Nombre:** Alex, 28 años
- **Rol:** Full-stack developer
- **Dolor:** Pierde 2-3 horas diarias debuggeando y entendiendo código legacy
- **Necesita:** Entender rápido el sistema, encontrar y corregir bugs eficientemente

### 4.2 Persona secundaria: Tech Lead
- **Nombre:** María, 34 años
- **Rol:** Tech Lead de un equipo de 8
- **Dolor:** Revisar PRs sin visión de impacto, onboarding lento de nuevos miembros
- **Necesita:** Vista global del sistema, análisis de impacto de cambios, herramienta de onboarding

### 4.3 Persona terciaria: CTO / Arquitecto
- **Nombre:** Carlos, 40 años
- **Rol:** CTO de startup en escalado
- **Dolor:** Deuda técnica invisible, cuellos de botella desconocidos
- **Necesita:** Auditoría de arquitectura, detección de fragilidad, análisis "what if"

---

## 5. Requisitos Funcionales

### 5.1 Análisis Global del Proyecto

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-001 | El plugin debe parsear proyectos abiertos en el IDE usando PSI (JS/TS/Kotlin/Java) | Alta | ✅ |
| RF-002 | El sistema debe identificar imports/exports entre módulos | Alta | ✅ |
| RF-003 | El sistema debe detectar funciones, clases y componentes | Alta | ✅ |
| RF-004 | El sistema debe mapear llamadas entre módulos | Alta | ✅ |
| RF-005 | El sistema debe analizar flujo de datos entre componentes | Media | ❌ |
| RF-006 | El sistema debe detectar relaciones frontend/backend/API | Media | ❌ |
| RF-007 | El sistema debe construir un grafo de dependencias completo | Alta | ✅ |

### 5.2 Detección de Errores

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-010 | Detectar variables potencialmente null/undefined | Alta | ✅ |
| RF-011 | Detectar dependencias circulares | Alta | ✅ |
| RF-012 | Detectar estados no inicializados | Alta | ✅ |
| RF-013 | Detectar condiciones de carrera potenciales | Media | ❌ |
| RF-014 | Detectar memory leaks potenciales | Media | ❌ |
| RF-015 | Detectar errores por asincronía | Alta | ✅ |
| RF-016 | Detectar rutas de ejecución no controladas | Media | ❌ |
| RF-017 | Detectar código frágil ante inputs extremos | Baja | ❌ |
| RF-018 | Detectar errores lógicos comunes | Alta | ✅ |

### 5.3 Explicación Humana

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-020 | Cada error debe incluir una explicación en lenguaje natural | Alta | ✅ |
| RF-021 | La explicación debe incluir el contexto del flujo donde ocurre | Alta | ✅ |
| RF-022 | La explicación debe indicar qué componentes se ven afectados | Media | ✅ |
| RF-023 | La explicación debe incluir un ejemplo del escenario de fallo | Alta | ✅ |
| RF-024 | La explicación debe ser comprensible para un junior | Media | ❌ |

### 5.4 Fix Automático

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-030 | El sistema debe sugerir un fix para cada error detectado | Alta | ✅ |
| RF-031 | El fix debe respetar la arquitectura y patrones del proyecto | Alta | ✅ |
| RF-032 | El fix debe mostrarse como diff antes de aplicarse | Alta | ✅ |
| RF-033 | El usuario debe poder aplicar el fix con un clic | Alta | ✅ |
| RF-034 | El fix debe poder deshacerse | Media | ❌ |

### 5.5 Simulación

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-040 | El sistema debe poder simular rutas de ejecución | Media | ❌ |
| RF-041 | La simulación debe mostrar estados posibles del sistema | Media | ❌ |
| RF-042 | La simulación debe detectar fallos ante condiciones límite | Media | ❌ |
| RF-043 | La simulación debe visualizarse en el mapa | Alta | ✅* |

> *✅* = simplificado para MVP: animación visual del flujo de una ruta crítica

### 5.6 NeuroMap (Mapa Visual)

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-050 | Visualizar el proyecto como un grafo interactivo | Alta | ✅ |
| RF-051 | Los nodos representan archivos/funciones/componentes | Alta | ✅ |
| RF-052 | Las conexiones representan dependencias/llamadas | Alta | ✅ |
| RF-053 | Los nodos deben mostrar estado (verde/amarillo/rojo) | Alta | ✅ |
| RF-054 | Click en nodo muestra panel de detalle | Alta | ✅ |
| RF-055 | Zoom, pan y navegación fluida | Alta | ✅ |
| RF-056 | Filtros por tipo de nodo (frontend/backend/API) | Media | ❌ |
| RF-057 | Búsqueda de nodos | Media | ✅ |
| RF-058 | Animación de flujo de ejecución | Alta | ✅ |
| RF-059 | Análisis de impacto visual al seleccionar un nodo | Alta | ✅ |

### 5.7 Modo CTO / What If

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-060 | Responder preguntas sobre escalabilidad | Media | ❌ |
| RF-061 | Identificar cuellos de botella | Media | ❌ |
| RF-062 | Identificar módulos más frágiles | Alta | ✅ |
| RF-063 | Calcular complejidad por módulo | Alta | ✅ |

### 5.8 Integración Git

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-070 | Analizar historial de commits | Baja | ❌ |
| RF-071 | Detectar cuándo se introdujo un bug | Baja | ❌ |
| RF-072 | Slider temporal de evolución del proyecto | Baja | ❌ |

### 5.9 Modo Equipo / PR Review

| ID | Requisito | Prioridad | MVP |
|---|---|---|---|
| RF-080 | Analizar cambios de un PR | Baja | ❌ |
| RF-081 | Comentar PRs con análisis de impacto | Baja | ❌ |
| RF-082 | Bloquear PRs con riesgo alto | Baja | ❌ |

---

## 6. Requisitos No Funcionales

| ID | Requisito | Prioridad |
|---|---|---|
| RNF-001 | El análisis de un proyecto de <500 archivos debe completarse en <30 segundos | Alta |
| RNF-002 | El NeuroMap (JCEF) debe renderizar el grafo de forma fluida con >200 nodos | Alta |
| RNF-003 | El plugin debe ser compatible con IntelliJ IDEA 2024.x+ | Alta |
| RNF-004 | La API key de OpenAI debe almacenarse en IntelliJ PasswordSafe (encriptada) | Alta |
| RNF-005 | El plugin no debe degradar el rendimiento del IDE | Alta |
| RNF-006 | El motor core del plugin debe estar escrito en Kotlin | Alta |
| RNF-007 | El plugin debe tener tests unitarios para los analyzers | Media |
| RNF-008 | El mapa visual debe soportar interacciones fluidas (60fps) dentro de JCEF | Alta |
| RNF-009 | Los errores de OpenAI deben manejarse gracefully (fallback a caché) | Alta |
| RNF-010 | El plugin debe funcionar offline para análisis estático (excepto OpenAI features) | Baja |

---

## 7. Flujos de Usuario Principales

### 7.1 Flujo: Análisis inicial de proyecto

```
1. Usuario abre un proyecto en IntelliJ IDEA
2. Selecciona Tools → GhostDebugger → Analyze Project (o Ctrl+Alt+G)
3. El plugin parsea el proyecto usando PSI (loading con progreso en el Tool Window)
4. Se genera el NeuroMap en el panel derecho del IDE
5. Los nodos se colorean según estado (verde/amarillo/rojo)
6. Usuario ve el mapa completo del sistema sin salir del IDE
```

### 7.2 Flujo: Investigación de un bug

```
1. Usuario ve nodo rojo en el mapa
2. Click en el nodo
3. Se abre panel lateral con:
   - Nombre del archivo/función
   - Tipo de error detectado
   - Explicación humana del error
   - Componentes afectados
   - Fix sugerido con diff
4. Usuario pulsa "Fix it"
5. Se aplica el fix
6. El nodo cambia de rojo a verde
7. Los nodos dependientes se re-evalúan
```

### 7.3 Flujo: Explicación del sistema

```
1. Usuario pulsa "Explícame el sistema"
2. Sistema genera un resumen con:
   - Arquitectura general
   - Módulos principales
   - Flujos críticos
   - Puntos débiles
   - Áreas de mayor complejidad
3. Se muestra en panel dedicado
```

### 7.4 Flujo: Simulación de ejecución

```
1. Usuario selecciona un punto de entrada (ej: "login")
2. Pulsa "Simular ejecución"
3. El mapa anima el recorrido del flujo
4. Los nodos se iluminan secuencialmente
5. Si encuentra un punto de fallo, lo marca y explica
6. Se muestra resumen de la simulación
```

---

## 8. Wireframes / Mockups

### Layout principal

```
┌─────────────────────────────────────────────────────────────────┐
│  👻 GhostDebugger          [Search...]     [Simulate] [Explain] │
├──────────────────────────────────┬──────────────────────────────┤
│                                  │                              │
│                                  │  📋 Detail Panel             │
│                                  │                              │
│       🗺️ NeuroMap               │  ┌──────────────────────┐   │
│       (Interactive Graph)        │  │ UserAuth.tsx          │   │
│                                  │  │ ──────────────────    │   │
│    ┌──┐     ┌──┐                │  │ 🔴 Error detected     │   │
│    │🟢│────▶│🔴│                │  │                        │   │
│    └──┘     └──┘                │  │ "This variable can be │   │
│      │       │                  │  │  null if the API takes │   │
│      ▼       ▼                  │  │  more than 2s..."      │   │
│    ┌──┐     ┌──┐                │  │                        │   │
│    │🟢│     │🟡│                │  │ [Fix it] [Ignore]     │   │
│    └──┘     └──┘                │  └──────────────────────┘   │
│                                  │                              │
│                                  │  📊 Impact Analysis          │
│                                  │  Affects: 12 modules         │
│                                  │  Risk: High                  │
├──────────────────────────────────┴──────────────────────────────┤
│  Summary: 47 files | 12 issues (3🔴 5🟡 4🟢) | Last: 2min ago │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. Métricas de Éxito

### Para el Hackathon
- El jurado entiende la propuesta de valor en <30 segundos
- La demo fluye sin errores técnicos
- El efecto "wow" del mapa visual y el fix automático

### Para Producto
- Reducción del 40% en tiempo de debugging
- Adopción por >100 desarrolladores en 3 meses
- NPS > 50 entre usuarios beta

---

## 10. Riesgos y Mitigaciones

| Riesgo | Impacto | Probabilidad | Mitigación |
|---|---|---|---|
| PSI parsing falla en proyectos complejos | Alto | Media | Usar PSI del IDE (ya probado), manejo graceful de errores |
| OpenAI genera explicaciones incorrectas | Medio | Media | Validar con análisis estático, disclaimer en UI |
| El grafo se vuelve inmanejable con muchos nodos | Alto | Alta | Clustering, filtros, zoom semántico |
| Latencia alta por llamadas a OpenAI | Medio | Alta | AICache, explicaciones pre-generadas, loading states |
| JCEF no renderiza correctamente React Flow | Medio | Baja | Probar JCEF temprano, tener captura de video como backup |
| No da tiempo a terminar todo para el hackathon | Alto | Alta | Priorizar MVP (ver MVP_PLAN.md) |

---

## 11. Cronograma

Ver [MVP_PLAN.md](MVP_PLAN.md) para el plan detallado de ejecución en 24-48h.

---

## 12. Apéndices

### A. Glosario

- **NeuroMap:** Componente de visualización del grafo del proyecto
- **Nodo:** Representación visual de un archivo, función, clase o componente
- **Edge/Conexión:** Relación entre dos nodos (import, llamada, dependencia)
- **Fix contextual:** Corrección de código que respeta los patrones del proyecto
- **Simulación:** Ejecución virtual de un flujo sin correr la aplicación
- **Análisis de impacto:** Cálculo de qué partes del sistema se ven afectadas por un cambio

### B. Referencias

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [IntelliJ PSI (Program Structure Interface)](https://plugins.jetbrains.com/docs/intellij/psi.html)
- [JCEF (JetBrains Chromium Embedded Framework)](https://plugins.jetbrains.com/docs/intellij/jcef.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [React Flow Documentation](https://reactflow.dev/)
- [OpenAI API Documentation](https://platform.openai.com/docs)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)
