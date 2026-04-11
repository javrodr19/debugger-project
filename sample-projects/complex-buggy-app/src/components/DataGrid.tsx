import React, { useState, useEffect } from 'react';

export const DataGrid = () => {
  const [user, setUser] = useState(null);

  useEffect(() => {
    const intervalId = setInterval(() => {
      console.log("Polling...");
    }, 1000);

    return () => clearInterval(intervalId);
  }, []);

  return (
    <div>
      <h3>Welcome, {user ? user.name : 'Guest'}</h3>
    </div>
  );
};