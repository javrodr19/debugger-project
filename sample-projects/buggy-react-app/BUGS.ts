// ============================================
// Sample Buggy React App — For Demo
// ============================================
// This project contains intentional bugs that
// GhostDebugger should detect, explain, and fix.
// ============================================

// ── Bug 1: Null reference after async ── 🔴
// File: src/components/Dashboard.tsx
//
// import { useState, useEffect } from 'react';
// import { fetchUserProfile } from '../services/api';
//
// const Dashboard = () => {
//   const [user, setUser] = useState(null);
//
//   useEffect(() => {
//     fetchUserProfile().then(data => setUser(data));
//   }, []);
//
//   return (
//     <div>
//       <h1>Welcome, {user.name}</h1>  // 💥 user is null initially
//       <p>Email: {user.email}</p>
//     </div>
//   );
// };
//
// Expected detection: "null-safety" error
// Expected explanation: "Tu app falla porque user es null hasta que el fetch
//   se complete. Cuando Dashboard se renderiza por primera vez, user.name
//   lanza un TypeError."
// Expected fix: Add null check or loading state


// ── Bug 2: Circular dependency ── 🟡
// File: src/services/auth.ts
//   import { getUserData } from './user';
//
// File: src/services/user.ts
//   import { isAuthenticated } from './auth';
//
// Expected detection: "circular-dependency" warning
// Expected explanation: "auth.ts y user.ts se importan mutuamente.
//   Esto puede causar problemas de inicialización."


// ── Bug 3: Unhandled promise ── 🔴
// File: src/services/api.ts
//
// export const fetchData = async (url: string) => {
//   const response = await fetch(url);
//   return response.json();  // No error handling, no status check
// };
//
// Expected detection: "unhandled-promise" error
// Expected explanation: "La función fetchData no maneja errores de red
//   ni verifica el status HTTP de la respuesta."


// ── Bug 4: Memory leak (missing cleanup) ── 🟡
// File: src/hooks/usePolling.ts
//
// import { useEffect, useState } from 'react';
//
// export const usePolling = (url: string, interval: number) => {
//   const [data, setData] = useState(null);
//
//   useEffect(() => {
//     const timer = setInterval(async () => {
//       const res = await fetch(url);
//       const json = await res.json();
//       setData(json);
//     }, interval);
//     // 💥 No cleanup — memory leak
//   }, [url, interval]);
//
//   return data;
// };
//
// Expected detection: "memory-leak" warning
// Expected explanation: "El useEffect crea un setInterval pero no lo
//   limpia al desmontar. Esto causa memory leaks."


// ── Bug 5: State used before initialization ── 🔴
// File: src/components/ItemList.tsx
//
// import { useState, useEffect } from 'react';
//
// const ItemList = () => {
//   const [items, setItems] = useState();  // undefined, not []
//
//   useEffect(() => {
//     fetch('/api/items')
//       .then(r => r.json())
//       .then(data => setItems(data));
//   }, []);
//
//   return (
//     <ul>
//       {items.map(item => (   // 💥 items is undefined
//         <li key={item.id}>{item.name}</li>
//       ))}
//     </ul>
//   );
// };
//
// Expected detection: "state-init" error
// Expected explanation: "items es undefined hasta que el fetch se complete.
//   Llamar .map() sobre undefined causa un TypeError."


// ── Bug 6: High complexity function ── 🟡
// File: src/utils/permissions.ts
//
// A function with 15+ if/else branches checking different
// user roles and permissions. Cyclomatic complexity > 15.
//
// Expected detection: "complexity" warning
// Expected explanation: "La función checkPermissions tiene una complejidad
//   ciclomática de 17, lo que la hace difícil de mantener y testear."

export {};
