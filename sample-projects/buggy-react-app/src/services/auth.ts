import { getUserData } from './user';

/**
 * Auth service — BUG: circular dependency with user.ts
 * 
 * Also contains BUG: unhandled promise in fetchUserProfile
 */

let authToken: string | null = null;

export const isAuthenticated = (): boolean => {
  return authToken !== null;
};

export const login = async (email: string, password: string) => {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });

  const data = await response.json();
  authToken = data.token;
  return data;
};

export const logout = () => {
  authToken = null;
};

export const getCurrentUser = async () => {
  return getUserData();
};
