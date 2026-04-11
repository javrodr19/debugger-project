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

  const response = await fetch('/api/user/me');
  return response.json();
};

export const updateUserProfile = async (data: Partial<UserData>) => {
  const response = await fetch('/api/user/me', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  return response.json();
};
