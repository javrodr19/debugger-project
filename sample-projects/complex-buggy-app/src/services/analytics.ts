import { store } from '../store';

export const processEvent = (event: any) => {
  console.log("Analytics processed", event);
  // Using store directly creates implicit dependencies
  store.dispatch({ type: 'ANALYTICS_EVENT', payload: event });
};