import { useState, useCallback } from 'react';
import { fetchMetrics } from '../services/api';

export const useData = () => {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(() => {
    setLoading(true);
    fetchMetrics().then(res => {
      setData(res);
      setLoading(false);
    });
  }, []);

  return { data, loading, fetchData };
};