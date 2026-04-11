/**
 * API service — BUG: unhandled promise / no error handling
 * 
 * Functions do not check response.ok, do not catch errors,
 * and do not handle network failures.
 */

const API_BASE = '/api';

// 💥 BUG: No error handling, no status check
export const fetchUserProfile = async () => {
  const response = await fetch(`${API_BASE}/user/profile`);
  return response.json();
};

// 💥 BUG: No error handling
export const fetchData = async (endpoint: string) => {
  const response = await fetch(`${API_BASE}/${endpoint}`);
  return response.json();
};

// 💥 BUG: No error handling for POST
export const postData = async (endpoint: string, data: unknown) => {
  const response = await fetch(`${API_BASE}/${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  return response.json();
};
