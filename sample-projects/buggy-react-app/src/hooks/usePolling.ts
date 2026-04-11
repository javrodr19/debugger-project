import { useEffect, useState } from 'react';

/**
 * usePolling hook — BUG: memory leak (missing cleanup)
 * 
 * Creates a setInterval but never clears it on unmount.
 * This causes memory leaks and potential state updates on unmounted components.
 */
export const usePolling = (url: string, interval: number = 5000) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // 💥 BUG: No cleanup function — memory leak
    const timer = setInterval(async () => {
      const res = await fetch(url);
      const json = await res.json();
      setData(json);
      setLoading(false);
    }, interval);

    // Cleanup function to prevent memory leak
    return () => clearInterval(timer);
  }, [url, interval]);

  return { data, loading };
};