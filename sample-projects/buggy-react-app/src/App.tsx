import Dashboard from './components/Dashboard';
import ItemList from './components/ItemList';

/**
 * Main App component
 * This app contains several intentional bugs for GhostDebugger demo.
 */
function App() {
  return (
    <div className="app">
      <nav>
        <h2>Buggy App</h2>
      </nav>
      <main>
        <Dashboard />
        <ItemList />
      </main>
    </div>
  );
}

export default App;
