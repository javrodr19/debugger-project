import React from 'react';
import { useAuth } from '../hooks/useAuth';
import { UserAvatar } from './UserAvatar';

export const Header = () => {
  const { user, logout } = useAuth();
  
  return (
    <header>
      <div className="logo">Acme Corp</div>
      {user ? (
        <div className="user-menu">
          <UserAvatar user={user} />
          <button onClick={logout}>Logout</button>
        </div>
      ) : (
        <button>Login</button>
      )}
    </header>
  );
};