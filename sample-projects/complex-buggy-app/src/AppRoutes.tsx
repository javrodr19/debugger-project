import React from 'react';
import { Routes, Route } from 'react-router-dom';
import { Dashboard } from './components/Dashboard';
import { Settings } from './components/Settings';

export const AppRoutes = () => (
  <Routes>
    <Route path="/" element={<Dashboard />} />
    <Route path="/settings" element={<Settings />} />
  </Routes>
);