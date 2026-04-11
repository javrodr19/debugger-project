# 📖 Historias de Usuario — GhostDebugger

---

## Epic 1: Análisis de Proyecto

### US-001: Cargar un repositorio para análisis
**Como** desarrollador  
**Quiero** poder seleccionar un directorio de mi sistema  
**Para** que GhostDebugger analice mi proyecto completo  

**Criterios de aceptación:**
- [ ] Puedo seleccionar un directorio mediante un input de ruta
- [ ] El sistema muestra un indicador de progreso durante el análisis
- [ ] Al completarse, se muestra el mapa del proyecto
- [ ] Si el directorio no existe o está vacío, se muestra un mensaje de error claro
- [ ] Los archivos en node_modules, dist, y .git se excluyen automáticamente

**Prioridad:** Alta | **Sprint:** MVP

---

### US-002: Ver resumen del proyecto analizado
**Como** desarrollador  
**Quiero** ver un resumen del proyecto después del análisis  
**Para** entender rápidamente el estado del sistema  

**Criterios de aceptación:**
- [ ] Se muestra número total de archivos analizados
- [ ] Se muestra número de issues por severidad (error, warning, info)
- [ ] Se muestra tiempo de análisis
- [ ] El resumen está visible en una barra de estado

**Prioridad:** Alta | **Sprint:** MVP

---

## Epic 2: Mapa Visual (NeuroMap)

### US-003: Visualizar el proyecto como un grafo interactivo
**Como** desarrollador  
**Quiero** ver mi proyecto representado como un grafo de nodos y conexiones  
**Para** entender la arquitectura y dependencias de un vistazo  

**Criterios de aceptación:**
- [ ] Cada archivo/componente/función aparece como un nodo
- [ ] Las dependencias entre nodos aparecen como conexiones
- [ ] Puedo hacer zoom in/out y navegar el mapa
- [ ] Hay un minimapa para orientación
- [ ] La disposición de nodos es automática y legible

**Prioridad:** Alta | **Sprint:** MVP

---

### US-004: Ver estado de salud de cada nodo
**Como** desarrollador  
**Quiero** que los nodos del mapa tengan colores según su estado  
**Para** identificar problemas de un vistazo sin leer código  

**Criterios de aceptación:**
- [ ] Nodos con errores se muestran en rojo 🔴
- [ ] Nodos con warnings se muestran en amarillo 🟡
- [ ] Nodos sin problemas se muestran en verde 🟢
- [ ] Los nodos en rojo tienen una animación sutil de pulsación
- [ ] Al pasar el mouse, se muestra tooltip con resumen del estado

**Prioridad:** Alta | **Sprint:** MVP

---

### US-005: Inspeccionar un nodo del mapa
**Como** desarrollador  
**Quiero** poder hacer click en cualquier nodo del mapa  
**Para** ver información detallada sobre ese archivo/componente/función  

**Criterios de aceptación:**
- [ ] Al hacer click se abre un panel lateral de detalle
- [ ] Se muestra: nombre, tipo, archivo, líneas de código
- [ ] Se muestran sus dependencias (de quién depende)
- [ ] Se muestran sus dependientes (quién depende de él)
- [ ] Se muestra su complejidad y métricas
- [ ] Si tiene issues, se listan

**Prioridad:** Alta | **Sprint:** MVP

---

### US-006: Buscar nodos en el mapa
**Como** desarrollador  
**Quiero** poder buscar un archivo o función por nombre  
**Para** encontrar rápidamente lo que necesito sin navegar todo el mapa  

**Criterios de aceptación:**
- [ ] Hay un campo de búsqueda en el header
- [ ] Al escribir, se filtran/resaltan los nodos que coinciden
- [ ] Al seleccionar un resultado, el mapa centra ese nodo
- [ ] La búsqueda funciona por nombre de archivo, función o componente

**Prioridad:** Media | **Sprint:** MVP

---

## Epic 3: Detección de Errores

### US-007: Detectar errores de null safety
**Como** desarrollador  
**Quiero** que el sistema detecte variables que pueden ser null/undefined  
**Para** evitar crashes por referencias nulas  

**Criterios de aceptación:**
- [ ] Se detectan variables usadas sin null check después de operaciones async
- [ ] Se detectan estados de React usados antes de ser inicializados
- [ ] Se detectan accesos a propiedades de objetos potencialmente null
- [ ] Cada detección tiene una confianza (%) indicada

**Prioridad:** Alta | **Sprint:** MVP

---

### US-008: Detectar dependencias circulares
**Como** desarrollador  
**Quiero** que el sistema detecte dependencias circulares entre módulos  
**Para** evitar problemas de inicialización y mantenibilidad  

**Criterios de aceptación:**
- [ ] Se detectan ciclos de import entre módulos
- [ ] Se visualizan los ciclos en el mapa con un indicador visual
- [ ] Se lista la cadena completa del ciclo (A → B → C → A)

**Prioridad:** Alta | **Sprint:** MVP

---

### US-009: Detectar problemas de async/await
**Como** desarrollador  
**Quiero** que el sistema detecte errores comunes con promesas y async/await  
**Para** evitar bugs difíciles de reproducir  

**Criterios de aceptación:**
- [ ] Se detectan promesas sin catch/try-catch
- [ ] Se detectan await faltantes
- [ ] Se detectan race conditions potenciales
- [ ] Se detectan useEffect sin cleanup que pueden causar memory leaks

**Prioridad:** Alta | **Sprint:** MVP

---

## Epic 4: Explicación Humana

### US-010: Ver explicación humana de un error
**Como** desarrollador  
**Quiero** que cada error detectado venga con una explicación en lenguaje natural  
**Para** entender rápidamente qué pasa sin tener que analizar el código yo mismo  

**Criterios de aceptación:**
- [ ] La explicación describe QUÉ ocurre
- [ ] La explicación describe POR QUÉ ocurre
- [ ] La explicación describe EN QUÉ ESCENARIO se produce
- [ ] La explicación menciona los componentes afectados
- [ ] El tono es claro, como si un senior lo explicara
- [ ] Se muestra en el panel lateral al hacer click en un error

**Prioridad:** Alta | **Sprint:** MVP

---

### US-011: Explicación global del sistema
**Como** nuevo miembro del equipo / CTO  
**Quiero** pulsar un botón y recibir una explicación general del proyecto  
**Para** entender la arquitectura sin leer todo el código  

**Criterios de aceptación:**
- [ ] Botón "Explícame el sistema" visible en la UI
- [ ] La explicación incluye: arquitectura general, módulos principales, flujos críticos
- [ ] Se identifican puntos débiles y áreas de mayor complejidad
- [ ] Se muestra en un modal o panel dedicado

**Prioridad:** Media | **Sprint:** MVP

---

## Epic 5: Fix Automático

### US-012: Ver sugerencia de fix para un error
**Como** desarrollador  
**Quiero** que el sistema me sugiera una corrección para cada error  
**Para** no tener que pensar la solución yo mismo  

**Criterios de aceptación:**
- [ ] Cada error con severidad "error" tiene una sugerencia de fix
- [ ] El fix se muestra como un diff (antes/después)
- [ ] El fix incluye una descripción de qué hace
- [ ] El fix respeta los patrones y estilo del proyecto

**Prioridad:** Alta | **Sprint:** MVP

---

### US-013: Aplicar un fix con un clic
**Como** desarrollador  
**Quiero** poder aplicar una corrección sugerida pulsando un botón  
**Para** corregir errores de forma rápida sin editar código manualmente  

**Criterios de aceptación:**
- [ ] Hay un botón "Fix it" en el panel de detalle del error
- [ ] Al pulsar, se aplica el cambio en el archivo fuente
- [ ] El nodo en el mapa cambia de rojo a verde con una transición suave
- [ ] Se muestra confirmación de que el fix se aplicó
- [ ] Los nodos dependientes se re-evalúan

**Prioridad:** Alta | **Sprint:** MVP

---

## Epic 6: Simulación

### US-014: Simular la ejecución de un flujo
**Como** desarrollador  
**Quiero** poder simular la ejecución de un flujo sin correr la aplicación  
**Para** identificar dónde puede fallar antes de que ocurra  

**Criterios de aceptación:**
- [ ] Puedo seleccionar un punto de entrada y pulsar "Simular"
- [ ] El mapa muestra una animación del recorrido del flujo
- [ ] Los nodos se iluminan secuencialmente según el flujo
- [ ] Si se encuentra un punto de fallo, se marca y explica
- [ ] Al final se muestra un resumen de la simulación

**Prioridad:** Media | **Sprint:** Post-MVP

---

## Epic 7: Análisis de Impacto

### US-015: Ver el impacto de un cambio
**Como** desarrollador  
**Quiero** seleccionar un nodo y ver qué partes del sistema se ven afectadas  
**Para** saber el riesgo antes de hacer un cambio  

**Criterios de aceptación:**
- [ ] Al seleccionar un nodo, hay opción de "Ver impacto"
- [ ] Se resaltan los nodos afectados (directos e indirectos)
- [ ] Se muestra un conteo total de módulos afectados
- [ ] Se indica el nivel de riesgo

**Prioridad:** Alta | **Sprint:** MVP

---

## Epic 8: Modo What If

### US-016: Hacer preguntas sobre el sistema
**Como** CTO / Tech Lead  
**Quiero** poder hacer preguntas tipo "¿qué pasa si..." sobre mi sistema  
**Para** tomar decisiones informadas sobre arquitectura y escalabilidad  

**Criterios de aceptación:**
- [ ] Hay un chat/input donde puedo escribir preguntas
- [ ] El sistema responde con análisis basado en el grafo + IA
- [ ] Las respuestas incluyen recomendaciones concretas
- [ ] Puedo preguntar sobre escalabilidad, fragilidad, cuellos de botella

**Prioridad:** Media | **Sprint:** Post-MVP

---

## Epic 9: Métricas y Dashboard

### US-017: Ver métricas del proyecto
**Como** desarrollador / Tech Lead  
**Quiero** ver métricas generales del proyecto  
**Para** tener una visión cuantitativa de la salud del código  

**Criterios de aceptación:**
- [ ] Se muestra la salud general del proyecto (score)
- [ ] Se muestra la complejidad promedio y máxima
- [ ] Se muestra el acoplamiento entre módulos
- [ ] Se listan los módulos más frágiles
- [ ] Se listan los módulos más complejos

**Prioridad:** Media | **Sprint:** MVP

---

## Resumen de Priorización

### MVP (Hackathon)
| ID | Historia | Prioridad |
|---|---|---|
| US-001 | Cargar repositorio | Alta |
| US-002 | Ver resumen | Alta |
| US-003 | Grafo interactivo | Alta |
| US-004 | Colores de estado | Alta |
| US-005 | Inspeccionar nodo | Alta |
| US-007 | Null safety | Alta |
| US-008 | Deps circulares | Alta |
| US-009 | Async/await issues | Alta |
| US-010 | Explicación humana | Alta |
| US-012 | Sugerencia de fix | Alta |
| US-013 | Aplicar fix | Alta |
| US-015 | Impacto de cambio | Alta |

### Post-MVP
| ID | Historia | Prioridad |
|---|---|---|
| US-006 | Búsqueda | Media |
| US-011 | Explicación global | Media |
| US-014 | Simulación | Media |
| US-016 | What If mode | Media |
| US-017 | Métricas | Media |
