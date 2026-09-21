// Triggers AEG-ASYNC-001 (AsyncFlowAnalyzer) twice over, from its two independent sub-rules:
//   1. `setInterval` inside `useEffect` with no `clear*` call anywhere in the effect body.
//   2. `.then(` with no `.catch(` in the following 12 lines.
import { useEffect } from 'react'

export function Feed() {
  useEffect(() => {
    setInterval(() => console.log('poll'), 1000)
  }, [])

  const load = () => {
    fetch('/api/feed').then(r => r.json())
  }

  return <button onClick={load}>Load</button>
}
