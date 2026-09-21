// Half of a two-file import cycle that triggers AEG-CYCLE-001 (CircularDependencyAnalyzer).
// Relative imports are the only specifiers DependencyResolver turns into internal graph edges
// (see DEMO.md and README.md "Supported Languages"), which is why this sample is TypeScript.
import { b } from './cycleB'

export const a = () => b()
