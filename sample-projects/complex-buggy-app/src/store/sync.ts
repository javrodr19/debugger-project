import { fetchUserData } from '../services/api';

export const syncWithStore = (data: any) => {
    console.log("Syncing to store:", data);
    fetchUserData();
};