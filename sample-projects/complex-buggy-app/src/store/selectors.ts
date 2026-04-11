export const selectEvents = (state: any) => state.events;
export const selectRecentEvents = (state: any) => state.events.slice(-10);