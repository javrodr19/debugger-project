import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { connectWebsocket } from './services/websocket';

connectWebsocket();

const root = createRoot(document.getElementById('root')!);
root.render(<App />);