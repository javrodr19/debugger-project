import React, { useEffect, useState } from 'react';
import { useData } from '../hooks/useData';
import { DataGrid } from './DataGrid';

export const Dashboard = () => {
  // Inicializamos items como un arreglo vacío para evitar problemas de null safety
  const [items, setItems] = useState([]);

  return (
    <div className="dashboard-grid">
      <h2>Dashboard</h2>
      {/* Ahora items.map no causará un error porque items siempre es un arreglo */}
      {items.map(i => <div key={i}>{i}</div>)}
      <DataGrid />
    </div>
  );
};