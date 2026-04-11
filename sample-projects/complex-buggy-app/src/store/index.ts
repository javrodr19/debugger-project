import { configureStore } from '@reduxjs/toolkit';
import { rootReducer } from './reducers';

let store;

try {
  store = configureStore({
    reducer: rootReducer
  });
} catch (error) {
  console.error('Error al configurar el store:', error);
  // Aquí podrías manejar el error de otra manera, como enviar un reporte o mostrar un mensaje al usuario
}

export { store };