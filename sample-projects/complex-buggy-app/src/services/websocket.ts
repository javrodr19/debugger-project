import { io } from 'socket.io-client';
import { processEvent } from './analytics';

let socket: any = null;

export const connectWebsocket = () => {
  socket = io('wss://stream.acme.com');
  
  socket.on('message', (data: any) => {
    // Data flow trace
    processEvent(data);
  });
};

export const disconnectWebsocket = () => {
  if (socket) socket.disconnect();
};