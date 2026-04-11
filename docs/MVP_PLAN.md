# ⚡ Plan MVP — GhostDebugger (24-48 horas)

**Objetivo:** Construir un plugin funcional para JetBrains que impresione al jurado del hackathon.

---

## Estrategia: Impacto Máximo, Scope Mínimo

> **Regla de oro:** No intentes hacer todo. Haz que lo que hagas se vea increíble.

### Lo que SÍ hay que hacer
1. ✅ UI visual del mapa dentro del IDE (impacto inmediato)
2. ✅ Explicación humana de errores con OpenAI (valor diferencial)
3. ✅ Fix automático con efecto visual (momento "wow")

### Lo que se puede simplificar
- Simulación → Animación visual básica de un flujo predefinido
- What If → Chat simple con OpenAI GPT-4o + contexto del grafo
- Reproducción de bugs → Skip para MVP

### Lo que se deja fuera del MVP
- ❌ Integración Git / timeline
- ❌ Modo equipo / PR review
- ❌ Autenticación / usuarios
- ❌ Persistencia en base de datos
- ❌ Soporte para lenguajes más allá de JS/TS/Kotlin/Java

---

## Cronograma

### Hora 0-3: Setup y Fundación

| Tarea | Responsable | Tiempo |
|---|---|---|
| Setup proyecto Gradle + IntelliJ Platform Plugin | Plugin/Backend | 1h |
| Registrar plugin.xml (actions, tool window, service) | Plugin/Backend | 30 min |
| Setup webview/ (Vite + React + TailwindCSS + React Flow) | Frontend | 45 min |
| Implementar JcefBridge básico (Kotlin ↔ JS) | Plugin/Backend | 45 min |
| Verificar que runIde abre IDE con tool window y JCEF | Todos | 15 min |

### Hora 3-7: Motor de Parsing (Kotlin)

| Tarea | Responsable | Tiempo |
|---|---|---|
| FileScanner: descubrir archivos del proyecto usando VFS | Backend | 30 min |
| SymbolExtractor: extraer funciones, clases, imports usando PSI | Backend | 1.5h |
| DependencyResolver: resolver imports y mapear dependencias | Backend | 1h |
| GraphBuilder: construir InMemoryGraph | Backend | 1h |

### Hora 3-7: Frontend Base (en paralelo)

| Tarea | Responsable | Tiempo |
|---|---|---|
| Layout principal dentro del JCEF panel | Frontend | 1h |
| NeuroMap con React Flow (nodos + edges) | Frontend | 2h |
| CustomNode component (con colores de estado) | Frontend | 30 min |
| Panel lateral (placeholder con datos hardcoded) | Frontend | 30 min |

### Hora 7-11: Análisis y Conexión

| Tarea | Responsable | Tiempo |
|---|---|---|
| Implementar 3 analyzers: NullSafety, CircularDeps, Complexity | Backend | 2h |
| Enviar grafo completo al frontend via bridge | Backend | 30 min |
| Compilar webview → src/main/resources/web/ | Frontend | 15 min |
| Conectar frontend con datos reales del bridge | Frontend | 1h |
| Verificar que el mapa renderiza con datos del proyecto real | Todos | 15 min |

### Hora 11-15: OpenAI Integration

| Tarea | Responsable | Tiempo |
|---|---|---|
| Setup OkHttp/Ktor client + OpenAI API calls | IA/Backend | 1h |
| PromptTemplates: explain issue + suggest fix | IA/Backend | 1h |
| ApiKeyManager: almacenar key en PasswordSafe + Settings UI | IA/Backend | 30 min |
| AICache con ConcurrentHashMap | IA/Backend | 30 min |
| Conectar explicación de OpenAI con panel lateral | Frontend | 30 min |
| Botón "Fix it" con diff preview | Frontend | 30 min |

### Hora 15-19: Pulido Visual

| Tarea | Responsable | Tiempo |
|---|---|---|
| Animaciones de nodos (pulsación en rojo, transición a verde) | Frontend | 1h |
| Animación de flujo/simulación básica | Frontend | 1h |
| Mejorar CustomNode design (glassmorphism, icons) | Frontend | 30 min |
| Loading states y feedback visual | Frontend | 30 min |
| Status bar con resumen del proyecto | Frontend | 30 min |
| Dark theme coherente con el IDE | Frontend | 30 min |

### Hora 19-22: Proyecto de Demo

| Tarea | Responsable | Tiempo |
|---|---|---|
| Crear proyecto de ejemplo con bugs intencionales | Full-stack | 1h |
| Asegurar que el análisis detecta los bugs correctamente | Backend | 1h |
| Asegurar que los fixes vía OpenAI funcionan end-to-end | Full-stack | 1h |

### Hora 22-24: Preparación Pitch

| Tarea | Responsable | Tiempo |
|---|---|---|
| Probar y ajustar flujo completo de demo | Todos | 30 min |
| Ensayar demo 3 veces | Presentador | 30 min |
| Preparar slides (si se necesitan) | Diseño | 30 min |
| Plan B si algo falla en demo | Todos | 15 min |
| Últimos ajustes visuales | Frontend | 15 min |

---

## Bugs Intencionales para la Demo

El proyecto de ejemplo (`sample-projects/buggy-react-app/`) incluye estos bugs controlados:

### Bug 1: Null reference después de async (🔴 CRÍTICO)
```tsx
// Dashboard.tsx
const Dashboard = () => {
  const [user, setUser] = useState(null);
  
  useEffect(() => {
    fetchUser().then(data => setUser(data));
  }, []);
  
  return <h1>Welcome, {user.name}</h1>; // 💥 user es null inicialmente
};
```
**Explicación OpenAI esperada:** "El componente Dashboard intenta acceder a `user.name` pero `user` es `null` hasta que el fetch se complete."  
**Fix esperado:** Añadir null check o loading state.

### Bug 2: Dependencia circular (🟡 WARNING)
```
// auth.ts importa desde user.ts
// user.ts importa desde auth.ts
```
**Explicación OpenAI esperada:** "Los módulos auth y user se importan mutuamente, lo que puede causar problemas de inicialización."

### Bug 3: Promise sin manejar error (🔴 CRÍTICO)
```tsx
export const fetchData = async () => {
  const response = await fetch('/api/data');
  return response.json(); // Sin catch ni verificación de status
};
```
**Explicación OpenAI esperada:** "Esta función no maneja errores de red ni verifica el status de la respuesta."

### Bug 4: useEffect sin cleanup (🟡 WARNING)
```tsx
useEffect(() => {
  const interval = setInterval(fetchData, 5000);
  // No hay cleanup → memory leak
}, []);
```

### Bug 5: Estado no inicializado usado en render (🔴 CRÍTICO)
```tsx
const [items, setItems] = useState();
return items.map(item => <div>{item}</div>); // 💥
```

---

## Checklist Pre-Demo

- [ ] `./gradlew runIde` abre IntelliJ con el plugin cargado
- [ ] `Tools → GhostDebugger → Analyze Project` lanza el análisis
- [ ] El NeuroMap se renderiza en el Tool Window con colores
- [ ] Al menos 3 bugs aparecen correctamente pintados (🔴🟡🟢)
- [ ] Click en nodo rojo muestra explicación humana de OpenAI
- [ ] Botón "Fix it" funciona y cambia el nodo de rojo a verde
- [ ] Sin errores en la consola del IDE
- [ ] OpenAI responde en <5 segundos
- [ ] La transición de error a fix se ve fluida
- [ ] La API key de OpenAI se configura en Settings del IDE
- [ ] Se ha ensayado el pitch 3+ veces

---

## Plan B

Si algo falla durante la demo:

| Fallo | Backup |
|---|---|
| OpenAI API no responde | Tener respuestas pre-cacheadas en el AICache |
| El parsing PSI falla | Tener un grafo pre-generado como JSON estático |
| JCEF no renderiza | Tener screenshots/video grabado previamente |
| Fix no se aplica | Tener el código pre-fixeado y hacer switch manual |
| runIde no arranca | Tener una build del plugin pre-compilada como .zip |

---

## Entregables del MVP

1. **Plugin para JetBrains funcional** que analiza el proyecto abierto en el IDE
2. **NeuroMap interactivo** con nodos coloreados por estado dentro del Tool Window
3. **Panel de detalle** con explicaciones humanas generadas por **OpenAI GPT-4o**
4. **Botón "Fix it"** que aplica correcciones contextuales al código
5. **Proyecto de ejemplo** con bugs intencionales para la demo
6. **Pitch deck** con tagline y mensajes clave
