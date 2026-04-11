import axios from 'axios';
import { syncWithStore } from '../store/sync';

export const fetchUserData = async () => {
    const response = await axios.get('/api/users/me');
    
    // Circular dependency incoming! api -> sync -> api
    syncWithStore(response.data);
    
    // Triggers MISSING_ERROR_HANDLING because there's no try/catch and we do:
    return response.json();
};

export const pingAnalytics = () => {
    // Triggers UNHANDLED_PROMISE (then without catch and ends with semicolon)
    axios.post('/ping').then(res => console.log(res));
};