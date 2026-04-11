/**
 * API service — BUG: unhandled promise / no error handling
 * 
 * Functions do not check response.ok, do not catch errors,
 * and do not handle network failures.
 */

const API_BASE = '/api';

// 💥 BUG: No error handling, no status check
export const fetchUserProfile = async () => {
  try {
    const response = await fetch(`${API_BASE}/user/profile`);
    if (!response.ok) {
      throw new Error(`Error: ${response.statusText}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Fetch error: ', error);
    throw error;
  }
};

export const fetchData = async (endpoint: string) => {
  try {
    const response = await fetch(`${API_BASE}/${endpoint}`);
    if (!response.ok) {
      throw new Error(`Error: ${response.statusText}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Fetch error: ', error);
    throw error;
  }
};

// 💥 BUG: No error handling for POST
export const postData