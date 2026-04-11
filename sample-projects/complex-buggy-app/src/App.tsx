import React, { Suspense } from 'react';
import { Provider } from 'react-redux';
import { store } from './store';
import { Dashboard } from './components/Dashboard';

export const App = () => {
  return (
    <Provider store={store}>
      <Dashboard />
    </Provider>
  );
};