// Triggers AEG-STATE-001 (StateInitAnalyzer) — and, as a documented side effect, AEG-NULL-001
// (NullSafetyAnalyzer) too. See "Known duplicate reporting" in DEMO.md: NullSafetyAnalyzer's
// useState pattern treats the null/undefined initializer as optional, so a bare `useState()`
// satisfies both rules' regexes on the same line.
export function List() {
  const [items, setItems] = useState()

  return <ul>{items.map(i => <li key={i}>{i}</li>)}</ul>
}
