import React, { createContext, useContext, useState, useEffect } from 'react';
import { fetchUserData } from '../services/api';

const AuthContext = createContext<any>(null);

export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [user, setUser] = useState(null);

  useEffect(() => {
    fetchUserData().then(setUser);
  }, []);

  const logout = () => setUser(null);

  return (
    <AuthContext.Provider value={{ user, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);