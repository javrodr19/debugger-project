import { isAuthenticated } from './auth';

/**
 * User service — BUG: circular dependency with auth.ts
 * 
 * auth.ts imports from user.ts, and user.ts imports from auth.ts
 */

export interface UserData {
  id: string;
  name: string;
  email: string;
  role: string;
}

export const getUserData = async (): Promise<UserData | null> => {
  if (!isAuthenticated()) {
    return null;
}

try {
  const response = await fetch('/api/user/me');
  if (!response.ok) {
    throw new Error('Error en la respuesta del servidor');
  }
  return response.json();
} catch (error) {
  console.error('Error al obtener los datos del usuario:', error);
  throw error;
};

export const updateUserProfile = async (data: Partial<UserData>) => {
  const response = await fetch('/api/user/me', {
    method: 'PUT',
headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(data),
});
if (!response.ok) {
  throw new Error('Network response was not ok');
}
return response.json();
