import React from 'react';
import { Link } from 'react-router-dom';

export const Sidebar = () => {
  return (
    <aside>
      <nav>
        <Link to="/">Dashboard</Link>
        <Link to="/settings">Settings</Link>
      </nav>
    </aside>
  );
};