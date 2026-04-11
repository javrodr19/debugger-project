// userData.ts
export const getUserData = async () => {
  // Implementación de la función para obtener datos del usuario
  // Simulación de un posible error
  throw new Error('Error al obtener datos del usuario');
};

// auth.ts
import { getUserData } from './userData';

/**
 * Auth service
 * 
 * Maneja la autenticación de usuarios.
 */

let authToken: string | null = null;

export const isAuthenticated = (): boolean => {
  return authToken !== null;
};

export const login = async (email: string, password: string) => {
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });

    if (!response.ok) {
      throw new Error(`Error en la autenticación: ${response.statusText}`);
    }

    const data = await response.json();
    authToken = data.token;

    // Manejo de errores para getUserData
    try {
      const userData = await getUserData();
      console.log('Datos del usuario obtenidos:', userData);
    } catch (error) {
      console.error('Error al obtener datos del usuario:', error.message);
    }

  } catch (error) {
    console.error('Error en el proceso de inicio de sesión:', error.message);
  }
};