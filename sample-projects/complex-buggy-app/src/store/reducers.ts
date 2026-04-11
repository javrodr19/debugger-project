const initialState = {
  events: []
};

export const rootReducer = (state = initialState, action: any) => {
  switch (action.type) {
    case 'ANALYTICS_EVENT':
       // Memory leak: just keeps appending forever
       return { ...state, events: [...state.events, action.payload] };
    default:
       return state;
  }
};