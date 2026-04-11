import React, { useState } from 'react';
import { useTheme } from '../hooks/useTheme';

export const Settings = () => {
  // Bug: State initialized with a complex calculation that runs every render
  const [prefs, setPrefs] = useState(() => {
    let sum = 0;
    for(let i=0; i<10000000; i++) sum++;
    return { level: sum };
  });

  const { theme, setTheme } = useTheme();

  return (
    <div>
      <h2>Settings</h2>
      <select value={theme} onChange={e => setTheme(e.target.value)}>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
    </div>
  );
};