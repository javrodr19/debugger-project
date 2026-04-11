# 🎨 Design System — GhostDebugger

---

## 1. Colores

### Paleta Principal

```css
:root {
  /* -- Background -- */
  --bg-primary: #0a0a0f;         /* Fondo principal (casi negro) */
  --bg-secondary: #12121a;       /* Fondo secundario */
  --bg-tertiary: #1a1a2e;        /* Fondo de paneles */
  --bg-elevated: #1e1e32;        /* Fondo elevado (cards, modales) */
  --bg-hover: #252540;           /* Hover state */
  --bg-glass: rgba(26, 26, 46, 0.75);  /* Glassmorphism */

  /* -- Accent / Brand -- */
  --accent-primary: #6c5ce7;     /* Morado principal (GhostDebugger) */
  --accent-secondary: #a29bfe;   /* Morado claro */
  --accent-glow: rgba(108, 92, 231, 0.3); /* Glow effect */

  /* -- Status -- */
  --status-error: #ff4757;       /* Rojo - Error */
  --status-error-bg: rgba(255, 71, 87, 0.12);
  --status-error-glow: rgba(255, 71, 87, 0.4);
  
  --status-warning: #ffa502;     /* Amarillo - Warning */
  --status-warning-bg: rgba(255, 165, 2, 0.12);
  --status-warning-glow: rgba(255, 165, 2, 0.4);
  
  --status-healthy: #2ed573;     /* Verde - Healthy */
  --status-healthy-bg: rgba(46, 213, 115, 0.12);
  --status-healthy-glow: rgba(46, 213, 115, 0.4);
  
  --status-info: #1e90ff;        /* Azul - Info */
  --status-info-bg: rgba(30, 144, 255, 0.12);

  /* -- Text -- */
  --text-primary: #e8e8f0;       /* Texto principal */
  --text-secondary: #9090a8;     /* Texto secundario */
  --text-muted: #5a5a72;         /* Texto deshabilitado */
  --text-accent: #a29bfe;        /* Texto con accent */

  /* -- Border -- */
  --border-default: rgba(255, 255, 255, 0.06);
  --border-hover: rgba(255, 255, 255, 0.12);
  --border-accent: rgba(108, 92, 231, 0.3);

  /* -- Gradients -- */
  --gradient-ghost: linear-gradient(135deg, #6c5ce7 0%, #a29bfe 50%, #6c5ce7 100%);
  --gradient-dark: linear-gradient(180deg, #0a0a0f 0%, #1a1a2e 100%);
  --gradient-card: linear-gradient(135deg, rgba(26, 26, 46, 0.8) 0%, rgba(30, 30, 50, 0.6) 100%);
}
```

### Colores de Nodos del NeuroMap

| Estado | Color de Fondo | Color de Borde | Glow |
|---|---|---|---|
| Healthy (🟢) | `#1a2e1e` | `#2ed573` | `0 0 12px rgba(46,213,115,0.4)` |
| Warning (🟡) | `#2e2a1a` | `#ffa502` | `0 0 12px rgba(255,165,2,0.4)` |
| Error (🔴) | `#2e1a1e` | `#ff4757` | `0 0 12px rgba(255,71,87,0.4)` + pulsación |
| Selected | `#1e1e32` | `#6c5ce7` | `0 0 16px rgba(108,92,231,0.5)` |

---

## 2. Tipografía

### Fuentes
- **Headings:** `'Inter', system-ui, sans-serif` — Weight 700
- **Body:** `'Inter', system-ui, sans-serif` — Weight 400/500
- **Code/Mono:** `'JetBrains Mono', 'Fira Code', monospace` — Weight 400

### Escala Tipográfica

| Nombre | Size | Weight | Line Height | Uso |
|---|---|---|---|---|
| `display` | 32px | 700 | 1.2 | Título principal |
| `h1` | 24px | 700 | 1.3 | Sección principal |
| `h2` | 20px | 600 | 1.3 | Sub-sección |
| `h3` | 16px | 600 | 1.4 | Título de card |
| `body` | 14px | 400 | 1.6 | Texto general |
| `body-sm` | 13px | 400 | 1.5 | Texto secundario |
| `caption` | 12px | 500 | 1.4 | Labels, badges |
| `code` | 13px | 400 | 1.6 | Código inline |
| `code-block` | 13px | 400 | 1.7 | Bloques de código |

### Google Fonts Import
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
```

---

## 3. Espaciado

```css
--space-1: 4px;
--space-2: 8px;
--space-3: 12px;
--space-4: 16px;
--space-5: 20px;
--space-6: 24px;
--space-8: 32px;
--space-10: 40px;
--space-12: 48px;
--space-16: 64px;
```

---

## 4. Border Radius

```css
--radius-sm: 6px;
--radius-md: 8px;
--radius-lg: 12px;
--radius-xl: 16px;
--radius-full: 9999px;
```

---

## 5. Sombras

```css
--shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.3);
--shadow-md: 0 4px 12px rgba(0, 0, 0, 0.4);
--shadow-lg: 0 8px 24px rgba(0, 0, 0, 0.5);
--shadow-glow-accent: 0 0 20px rgba(108, 92, 231, 0.3);
--shadow-glow-error: 0 0 16px rgba(255, 71, 87, 0.4);
--shadow-glow-healthy: 0 0 16px rgba(46, 213, 115, 0.3);
```

---

## 6. Animaciones

### Pulso de Error (nodo rojo)
```css
@keyframes pulse-error {
  0%, 100% { box-shadow: 0 0 8px rgba(255, 71, 87, 0.3); }
  50% { box-shadow: 0 0 20px rgba(255, 71, 87, 0.6); }
}

.node-error {
  animation: pulse-error 2s ease-in-out infinite;
}
```

### Transición Error → Fixed
```css
@keyframes fix-transition {
  0% { 
    border-color: var(--status-error);
    box-shadow: var(--shadow-glow-error);
  }
  50% { 
    border-color: var(--accent-primary);
    box-shadow: var(--shadow-glow-accent);
    transform: scale(1.05);
  }
  100% { 
    border-color: var(--status-healthy);
    box-shadow: var(--shadow-glow-healthy);
    transform: scale(1);
  }
}
```

### Flow Animation (simulación)
```css
@keyframes flow-pulse {
  0% { stroke-dashoffset: 100; opacity: 0.3; }
  50% { opacity: 1; }
  100% { stroke-dashoffset: 0; opacity: 0.3; }
}
```

### Fade In (paneles)
```css
@keyframes fade-in {
  from { opacity: 0; transform: translateX(20px); }
  to { opacity: 1; transform: translateX(0); }
}
```

### Durations
```css
--duration-fast: 150ms;
--duration-normal: 300ms;
--duration-slow: 500ms;
--duration-very-slow: 1000ms;
--easing-default: cubic-bezier(0.4, 0, 0.2, 1);
--easing-bounce: cubic-bezier(0.34, 1.56, 0.64, 1);
```

---

## 7. Componentes

### Nodo del NeuroMap

```
┌──────────────────────────────┐
│  📦 ComponentName            │  ← tipo + nombre
│  ─────────────────────────── │
│  src/components/Name.tsx     │  ← archivo
│  Lines: 42    Complexity: 7  │  ← métricas
│  ⚠️ 1 issue                  │  ← badge de issues
└──────────────────────────────┘
```

**Dimensiones:** 240px × 120px (aproximado, se adapta)
**Border:** 2px solid, color según estado
**Background:** glassmorphism con backdrop-blur

### Panel Lateral

```
┌────────────────────────────────┐
│  ✕  Dashboard.tsx              │  ← header + close
├────────────────────────────────┤
│  Type: Component               │
│  Lines: 85                     │
│  Complexity: 7                 │
│                                │
│  ┌──── Dependencies ─────┐    │
│  │  AuthService           │    │
│  │  UserStore             │    │
│  │  ApiClient             │    │
│  └────────────────────────┘    │
│                                │
│  🔴 1 Error                    │
│  ┌──── Issue ─────────────┐   │
│  │  Possible null ref     │   │
│  │                        │   │
│  │  "Tu app falla porque  │   │
│  │   userData no está     │   │
│  │   listo cuando..."     │   │
│  │                        │   │
│  │  [Fix it] [Ignore]    │   │
│  └────────────────────────┘   │
│                                │
│  📊 Impact: 12 modules        │
└────────────────────────────────┘
```

**Width:** 380px
**Background:** #12121a con borde izquierdo
**Animación:** slide-in desde la derecha

### Botón "Fix it"

```css
.btn-fix {
  background: linear-gradient(135deg, #6c5ce7, #a29bfe);
  color: white;
  border: none;
  border-radius: 8px;
  padding: 10px 24px;
  font-weight: 600;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.3s ease;
  box-shadow: 0 4px 12px rgba(108, 92, 231, 0.3);
}

.btn-fix:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(108, 92, 231, 0.5);
}

.btn-fix:active {
  transform: translateY(0);
}
```

### Badge de Severidad

| Severidad | Color | Icono |
|---|---|---|
| Error | `#ff4757` sobre `rgba(255,71,87,0.12)` | 🔴 |
| Warning | `#ffa502` sobre `rgba(255,165,2,0.12)` | ⚠️ |
| Info | `#1e90ff` sobre `rgba(30,144,255,0.12)` | ℹ️ |
| Healthy | `#2ed573` sobre `rgba(46,213,115,0.12)` | ✅ |

### Status Bar

```
┌─────────────────────────────────────────────────────────────────┐
│  📊 47 files  │  🔴 3 errors  │  ⚠️ 5 warnings  │  ✅ 39 ok  │  ⏱️ 2.3s  │
└─────────────────────────────────────────────────────────────────┘
```

**Height:** 36px
**Background:** #0a0a0f con borde superior
**Font:** 12px mono

---

## 8. Glassmorphism Effect

```css
.glass {
  background: rgba(26, 26, 46, 0.75);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
}
```

---

## 9. Responsive Breakpoints

| Nombre | Breakpoint | Uso |
|---|---|---|
| `sm` | 640px | Móvil (no prioritario) |
| `md` | 768px | Tablet |
| `lg` | 1024px | Desktop pequeño |
| `xl` | 1280px | Desktop |
| `2xl` | 1536px | Desktop grande |

**Nota:** La app es desktop-first. En móvil se muestra un mensaje sugiriendo usar desktop.

---

## 10. Iconografía

Usar **Lucide React** para iconos:

| Concepto | Icono Lucide |
|---|---|
| Archivo | `FileCode` |
| Función | `Braces` |
| Componente | `Component` |
| Error | `AlertCircle` |
| Warning | `AlertTriangle` |
| Fix | `Wrench` |
| Simular | `Play` |
| Buscar | `Search` |
| Impacto | `GitBranch` |
| Métricas | `BarChart3` |
| Explicar | `MessageCircle` |
| Cerrar | `X` |
| Settings | `Settings` |
| Ghost (logo) | `Ghost` |
