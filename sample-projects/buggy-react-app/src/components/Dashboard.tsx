import { useState, useEffect } from 'react';
import { fetchUserProfile } from '../services/api';

/**
 * Dashboard component — BUG: null reference
 * 
 * This component tries to access user.name before the fetch completes.
 * user is null initially, causing a TypeError.
 */
const Dashboard = () => {
  const [user, setUser] = useState(null);

  useEffect(() => {
    fetchUserProfile().then(data => setUser(data));
  }, []);

  // 💥 BUG: user is null on first render
  return (
    <div className="dashboard">
      <h1>Welcome, {user.name}</h1>
      <p>Email: {user.email}</p>
      <p>Role: {user.role}</p>
    </div>
  );
};

export default Dashboard;
